package com.callradar.app

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
        val dest = intent.getStringExtra("dest") ?: ""
        val userId = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getString("user_id", "") ?: ""
        if (tripId <= 0 || userId.isEmpty()) { finish(); return }
        setContent { QuickEntry(tripId, dest, userId) { finish() } }
    }
}

@Composable
private fun QuickEntry(tripId: Int, dest: String, userId: String, onClose: () -> Unit) {
    val accent = Color(0xFFF59E0B); val green = Color(0xFF10B981); val muted = Color(0xFF6B7280)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val qPrefs = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    var fare by remember { mutableStateOf("") }
    var tip by remember { mutableStateOf("") }
    var promo by remember { mutableStateOf("") }
    var promoType by remember { mutableStateOf("프로모션") }
    // 마지막 쓴 플랫폼·결제 기억 (다중플랫폼 기사도 매번 안 고르게)
    var platform by remember { mutableStateOf(qPrefs.getString("last_platform", "") ?: "") }
    var payType by remember { mutableStateOf(qPrefs.getString("last_paytype", "card") ?: "card") }
    var showExtra by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(18.dp), color = AppTheme.card) {
            Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                Text("운행 완료 — 요금 입력", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                if (dest.isNotBlank() && dest != "도착(미상)") Text("도착: ${dest}", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(14.dp))

                OutlinedTextField(value = fare, onValueChange = { fare = it.filter { c -> c.isDigit() } }, label = { Text("금액 (원)", color = muted) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
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
                        listOf("프로모션", "포인트콜").forEach { t -> FilterChip(selected = promoType == t, onClick = { promoType = t }, label = { Text(t, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted)) }
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
                    Button(onClick = {
                        if (busy) return@Button
                        busy = true
                        val f = fare.toIntOrNull() ?: 0
                        val t = tip.toIntOrNull() ?: 0
                        val pr = promo.toIntOrNull() ?: 0
                        qPrefs.edit().putString("last_platform", platform).putString("last_paytype", payType).apply()
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val json = JSONObject().apply { put("user_id", userId); if (f > 0) put("fare", f); if (t > 0) put("tip", t); if (pr > 0) { put("promo", pr); put("promo_type", promoType) }; if (platform.isNotEmpty()) put("platform", platform); put("payment_type", payType) }
                                    val conn = (URL("${Config.SERVER_URL}/api/trips/$tripId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 8000 }
                                    conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                                    conn.responseCode
                                }
                                com.callradar.app.Telemetry.log(ctx, "quick_save", "floating", ok = true, meta = platform)
                            } catch (e: Exception) { com.callradar.app.Telemetry.log(ctx, "quick_save", "floating", ok = false) }
                            onClose()
                        }
                    }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = green), shape = RoundedCornerShape(10.dp)) {
                        Text(if (busy) "저장 중" else "저장", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
