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
    var fare by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf("") }
    var payType by remember { mutableStateOf("card") }
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

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("나중에", color = muted) }
                    Button(onClick = {
                        if (busy) return@Button
                        busy = true
                        val f = fare.toIntOrNull() ?: 0
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val json = JSONObject().apply { put("user_id", userId); if (f > 0) put("fare", f); if (platform.isNotEmpty()) put("platform", platform); put("payment_type", payType) }
                                    val conn = (URL("${Config.SERVER_URL}/api/trips/$tripId").openConnection() as HttpURLConnection).apply { requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 8000 }
                                    conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                                    conn.responseCode
                                }
                            } catch (e: Exception) {}
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
