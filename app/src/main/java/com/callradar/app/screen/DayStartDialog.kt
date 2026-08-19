package com.callradar.app.screen

// [영업일 단일화] 영업일 시작시각 설정 다이얼로그 — 기본홈·간편메뉴·더보기가 전부 이 하나를 씀.
//  (설정 UI가 3곳에서 제각각이라 유저 혼란 + 순환탭 오조작 사고(#103)가 났던 것의 근본 정리)
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DayStartDialog(onDismiss: () -> Unit, onSaved: (Int) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    val accent = Color(0xFFF59E0B); val muted = Color(0xFF6B7280)
    var h by remember { mutableStateOf(prefs.getInt("day_start_hour", 0)) }
    fun label(v: Int) = if (v == 0) "자정 (0시)" else if (v < 12) "오전 ${v}시" else "낮 12시"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("영업일 시작 시각", color = AppTheme.text, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("하루의 시작을 이 시각으로 잡아요. 자정 넘긴 운행을 어느 날 매출로 묶을지 기준입니다.", fontSize = 12.sp, color = muted)
                Spacer(Modifier.height(4.dp))
                Text("· 일차 기사님: 오전 9~10시 → '오전 교대 ~ 다음날 교대'가 하루\n· 야간 기사님: 새벽 5~6시 → 심야 운행이 한 '오늘'\n· 주간 기사님: 자정 그대로", fontSize = 12.sp, color = muted, lineHeight = 18.sp)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { h = (h + 23) % 24; if (h > 12) h = 12 }, shape = androidx.compose.foundation.shape.CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("−", fontSize = 18.sp, color = accent) }
                    Text(label(h), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                    OutlinedButton(onClick = { h = (h + 1) % 13 }, shape = androidx.compose.foundation.shape.CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("+", fontSize = 18.sp, color = accent) }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "자정", 5 to "새벽5시", 6 to "오전6시", 9 to "오전9시", 10 to "10시").forEach { (v, lb) ->
                        FilterChip(selected = h == v, onClick = { h = v }, label = { Text(lb, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                prefs.edit().putInt("day_start_hour", h).putBoolean("day_start_set", true).apply()
                // [투폰 동기화] 계정에도 저장 → 서브폰(2·3폰)도 같은 영업일 기준 사용 (기존 더보기 동작 흡수)
                val uid = prefs.getString("user_id", "") ?: ""
                if (uid.isNotEmpty()) Thread {
                    try {
                        val conn = (java.net.URL("https://callradar-server.onrender.com/api/user-settings").openConnection() as java.net.HttpURLConnection).apply {
                            requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 6000
                            com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") }
                        }
                        conn.outputStream.use { it.write(org.json.JSONObject().apply { put("user_id", uid.toIntOrNull() ?: uid); put("day_start", h) }.toString().toByteArray()) }
                        conn.responseCode
                    } catch (e: Exception) {}
                }.start()
                onSaved(h); onDismiss()
            }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("취소") } },
        containerColor = AppTheme.card
    )
}
