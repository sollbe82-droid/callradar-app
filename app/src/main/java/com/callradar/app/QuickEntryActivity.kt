package com.callradar.app

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.callradar.app.screen.AppTheme
import com.callradar.app.screen.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * [v18] 운행완료 후 즉시 금액·플랫폼 입력 팝업. 플로팅 완료 → GPS 기록 후 이 팝업이 떠서
 *  앱을 안 열고도 요금·플랫폼을 바로 넣는다. (설정 '완료 후 금액 팝업' 켬일 때만)
 *  자동화(MediaProjection 금액캡처)가 붙기 전 인터림 — 붙으면 토글로 끄면 됨.
 */
class QuickEntryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tripId = intent.getIntExtra("trip_id", 0)
        val localId = intent.getLongExtra("local_id", -1L)   // [v31] 오프라인 폴백용 로컬 트립 ID
        val dest = intent.getStringExtra("dest") ?: ""
        val ocrFare = intent.getIntExtra("ocr_fare", 0)      // [v24] 종료 OCR가 뽑은 금액(ai)
        val ocrRaw = intent.getStringExtra("ocr_raw") ?: ""  // [v24] 종료 화면 원문(학습용)
        val startPlatform = intent.getStringExtra("start_platform") ?: ""  // [v43] 시작 때 판별된 플랫폼(팝업 기본값)
        val userId = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getString("user_id", "") ?: ""
        if (userId.isEmpty() || (tripId <= 0 && localId <= 0)) { finish(); return }
        setContent { QuickEntry(tripId, localId, dest, userId, ocrFare, ocrRaw, startPlatform) { finish() } }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QuickEntry(tripId: Int, localId: Long, dest: String, userId: String, ocrFare: Int, ocrRaw: String, startPlatform: String = "", onClose: () -> Unit) {
    val accent = Color(0xFFF59E0B); val green = Color(0xFF10B981); val muted = Color(0xFF6B7280)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val qPrefs = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    var fare by remember { mutableStateOf(if (ocrFare > 0) ocrFare.toString() else "") }  // [v43] 종료 금액(카드승인 캡처 포함) 자동 프리필
    var tip by remember { mutableStateOf("") }
    var promo by remember { mutableStateOf("") }
    var promoType by remember { mutableStateOf("프로모션") }
    // 마지막 쓴 플랫폼·결제 기억 (다중플랫폼 기사도 매번 안 고르게)
    // [v43] 종료 팝업 플랫폼 기본값 = 시작 때 판별된 플랫폼(카카오T 등 자동판별 또는 길빵/예약). 값 없을 때만 마지막 사용값.
    var platform by remember { mutableStateOf(if (startPlatform.isNotBlank()) startPlatform else (qPrefs.getString("last_platform", "") ?: "")) }
    var payType by remember { mutableStateOf(qPrefs.getString("last_paytype", "card") ?: "card") }
    var showExtra by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // [v43] 직접결제 카드승인(택시투데이 등) 금액 자동 프리필. 팝업이 뜬 뒤 결제돼도 최대 120초 감시해 채움.
    //   사용자가 직접 입력을 시작하면(fare 비어있지 않음) 덮어쓰지 않음.
    LaunchedEffect(Unit) {
        if (ocrFare > 0) { qPrefs.edit().remove("pending_fare").remove("pending_fare_ts").apply(); return@LaunchedEffect }
        var waited = 0L
        while (waited < 120000L && fare.isBlank()) {
            val pf = qPrefs.getInt("pending_fare", 0); val pts = qPrefs.getLong("pending_fare_ts", 0L)
            if (pf > 0 && System.currentTimeMillis() - pts < 300000L) {
                fare = pf.toString(); qPrefs.edit().remove("pending_fare").remove("pending_fare_ts").apply(); break
            }
            kotlinx.coroutines.delay(1500L); waited += 1500L
        }
    }
    val focusRequester = remember { FocusRequester() }

    // 저장: 로컬 우선(즉시·유실0) → 팝업 즉시 닫기 → 서버 반영·학습은 백그라운드.
    // 예전엔 서버 응답(최대 30초)을 기다린 뒤 닫아서 콜드스타트 때 체감이 느렸다. 이제 안 기다린다.
    val saveAndClose: () -> Unit = save@{
        if (busy) return@save
        busy = true
        val f = fare.toIntOrNull() ?: 0
        val t = tip.toIntOrNull() ?: 0
        val pr = promo.toIntOrNull() ?: 0
        qPrefs.edit().putString("last_platform", platform).putString("last_paytype", payType).apply()
        if (localId > 0) { try { com.callradar.app.LocalTripDatabase.getInstance(ctx).updateFare(localId, f, platform.ifEmpty { null }) } catch (e: Exception) {} }
        val appCtx = ctx.applicationContext
        val userFare = if (f > 0) f else ocrFare
        Thread {
            var ok = false
            try {
                if (tripId > 0) {
                    val json = JSONObject().apply { put("user_id", userId); if (f > 0) put("fare", f); if (t > 0) put("tip", t); if (pr > 0) { put("promo", pr); put("promo_type", promoType) }; if (platform.isNotEmpty()) put("platform", platform); put("payment_type", payType) }
                    val conn = (URL("${Config.SERVER_URL}/api/trips/$tripId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 8000; readTimeout = 8000 }
                    conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode in 200..299) ok = true
                }
            } catch (e: Exception) {}
            try { com.callradar.app.Telemetry.log(appCtx, "quick_save", "floating", ok = ok, meta = platform) } catch (e: Exception) {}
            try { com.callradar.app.Feedback.send(appCtx, "amount", platform.ifEmpty { null }, ocrRaw, if (ocrFare > 0) ocrFare.toString() else null, if (userFare > 0) userFare.toString() else null) } catch (e: Exception) {}
        }.start()
        onClose()
    }

    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(18.dp), color = AppTheme.card) {
            Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                Text("운행 완료 — 요금 입력", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                if (dest.isNotBlank() && dest != "도착(미상)") Text("도착: ${dest}", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(14.dp))

                OutlinedTextField(value = fare, onValueChange = { fare = it.filter { c -> c.isDigit() } }, label = { Text("금액 (원)", color = muted) }, modifier = Modifier.fillMaxWidth().focusRequester(focusRequester), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { saveAndClose() }), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                // [v31] 팝업 열리면 금액칸에 바로 포커스 → 타이핑 후 엔터(완료)로 즉시 저장
                LaunchedEffect(Unit) { try { focusRequester.requestFocus() } catch (e: Exception) {} }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    listOf(1000, 5000, 10000, 50000).forEach { amt ->
                        OutlinedButton(onClick = { val cur = fare.toIntOrNull() ?: 0; fare = (cur + amt).toString() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) {
                            Text(if (amt >= 10000) "+${amt / 10000}만" else "+${amt / 1000}천", fontSize = 12.sp)
                        }
                    }
                    OutlinedButton(onClick = { fare = "" }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = muted)) { Text("C", fontSize = 12.sp) }
                }

                Spacer(Modifier.height(10.dp))
                Text("플랫폼", fontSize = 12.sp, color = muted)
                // [v32] 칩 4개가 한 줄에 안 들어가 마지막 '길빵/예약'이 잘리던 문제 → 줄바꿈(FlowRow)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    listOf("카카오T", "우버", "티머니고", "길빵/예약").forEach { p ->
                        FilterChip(selected = platform == p, onClick = { platform = p }, label = { Text(p, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("결제", fontSize = 12.sp, color = muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    listOf("카드" to "card", "현금" to "cash", "자동결제" to "auto").forEach { (lbl, v) ->
                        FilterChip(selected = payType == v, onClick = { payType = v }, label = { Text(lbl, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
                    }
                }

                Spacer(Modifier.height(10.dp))
                if (!showExtra) {
                    TextButton(onClick = { showExtra = true }, contentPadding = PaddingValues(vertical = 2.dp)) { Text("+ 추가금·팁", color = accent, fontSize = 13.sp) }
                } else {
                    Text("추가금", fontSize = 12.sp, color = muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        listOf("프로모션", "포인트콜", "팁").forEach { t -> FilterChip(selected = promoType == t, onClick = { promoType = t }, label = { Text(t, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted)) }
                    }
                    OutlinedTextField(value = promo, onValueChange = { promo = it.filter { c -> c.isDigit() } }, label = { Text("추가금 (원)", color = muted) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                    Spacer(Modifier.height(8.dp))
                    Text("팁", fontSize = 12.sp, color = muted)
                    OutlinedTextField(value = tip, onValueChange = { tip = it.filter { c -> c.isDigit() } }, label = { Text("팁 (원)", color = muted) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFFBBF24), unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        listOf(1000, 2000, 3000).forEach { amt ->
                            OutlinedButton(onClick = { val cur = tip.toIntOrNull() ?: 0; tip = (cur + amt).toString() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFBBF24))) { Text("팁+${amt / 1000}천", fontSize = 11.sp) }
                        }
                    }
                }
                val liveTotal = (fare.toIntOrNull() ?: 0) + (tip.toIntOrNull() ?: 0) + (promo.toIntOrNull() ?: 0)
                if (liveTotal > 0) { Spacer(Modifier.height(10.dp)); Text("합계 ${String.format("%,d", liveTotal)}원", fontSize = 15.sp, color = accent, fontWeight = FontWeight.Bold) }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("나중에", color = muted) }
                    Button(onClick = saveAndClose, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = green), shape = RoundedCornerShape(10.dp)) {
                        Text("저장 (Enter)", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
