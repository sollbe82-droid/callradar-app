package com.callradar.app.screen

// [영업일 단일화] 영업일 시작시각 설정 다이얼로그 — 기본홈·간편메뉴·더보기가 전부 이 하나를 씀.
//  (설정 UI가 3곳에서 제각각이라 유저 혼란 + 순환탭 오조작 사고(#103)가 났던 것의 근본 정리)
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.withContext
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

    /* [2026-09-02 유저 제보] "출근이 6시인데 5시 50분에 태우면 매출에 안 잡힌다."
     *  버그가 아니라 이 설정이었다. 6시로 두면 05:50 운행은 계산상 **어제 영업일**이 된다.
     *  잘못은 기사가 아니라 **우리 안내 문구**였다 — 예전엔 "출근 시각에 맞추라"는 뜻으로 읽혔다.
     *  경계는 근무 시작점이 아니라 **하루 중 운행이 비는 골짜기**에 있어야 한다.
     *  그래서 그 기사의 최근 8주 시간대별 운행 수를 받아 추천하고, 고른 값이 근무 한가운데면 경고한다. */
    var hours by remember { mutableStateOf<IntArray?>(null) }
    var best by remember { mutableStateOf(-1) }
    LaunchedEffect(Unit) {
        val uid = prefs.getString("user_id", "") ?: ""
        if (uid.isBlank()) return@LaunchedEffect
        try {
            val body = withContext(kotlinx.coroutines.Dispatchers.IO) {
                (java.net.URL("${Config.SERVER_URL}/api/day-start-hint/$uid").openConnection().apply {
                    com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") }
                } as java.net.HttpURLConnection).apply { connectTimeout = 7000; readTimeout = 12000 }
                    .inputStream.bufferedReader().use { it.readText() }
            }
            val o = org.json.JSONObject(body)
            if (o.optBoolean("enough", false)) {
                val arr = o.getJSONArray("hours")
                val hh = IntArray(24) { arr.optInt(it, 0) }
                hours = hh
                /* 서버의 best 를 그대로 쓰지 않는다 — 이 화면의 스테퍼가 **0~12시만** 표현한다.
                 * 13시가 추천으로 나오면 칩을 눌러도 라벨이 '낮 12시'로 보여 값과 화면이 어긋난다.
                 * 그래서 표현 가능한 범위 안에서 가장 조용한 시각을 고른다. */
                var b = 0; var bs = Int.MAX_VALUE
                for (x in 0..12) {
                    val s = hh[(x + 23) % 24] + hh[x] + hh[(x + 1) % 24]
                    if (s < bs) { bs = s; b = x }
                }
                best = b
            }
        } catch (e: Exception) {}
    }
    // 고른 시각과 앞뒤 1시간의 운행 수 — 0이 아니면 하루가 근무 한가운데서 잘린다는 뜻이다.
    val nearPicked = hours?.let { it[(h + 23) % 24] + it[h] + it[(h + 1) % 24] } ?: -1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("영업일 시작 시각", color = AppTheme.text, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("하루를 자르는 기준 시각이에요. 자정 넘긴 운행을 어느 날 매출로 묶을지 정합니다.", fontSize = 12.sp, color = muted)
                Spacer(Modifier.height(6.dp))
                Text("⚠️ 출근 시각으로 맞추지 마세요.\n경계에 걸친 운행은 전날 매출로 넘어갑니다. 6시 출근인데 6시로 두면, 5시 50분에 태운 손님이 어제 매출이 됩니다.\n운행을 거의 안 하는 시각으로 두세요.",
                    fontSize = 12.sp, color = AppTheme.text, lineHeight = 18.sp)
                Spacer(Modifier.height(10.dp))
                // 그 기사의 최근 8주 기록으로 판단한다 — 시각을 고정해 경고하면 야간 기사에게 오탐이 난다.
                if (best >= 0) {
                    Text("📊 내 기록으로 보면 ${best}시가 가장 조용해요 (최근 8주)",
                        fontSize = 12.sp, color = accent, fontWeight = FontWeight.Bold)
                }
                if (nearPicked > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text("고른 ${h}시 전후에 최근 8주 운행이 ${nearPicked}건 있어요. 하루가 근무 한가운데서 잘립니다.",
                        fontSize = 12.sp, color = Color(0xFFEF4444), lineHeight = 17.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { h = (h + 23) % 24; if (h > 12) h = 12 }, shape = androidx.compose.foundation.shape.CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("−", fontSize = 18.sp, color = accent) }
                    Text(label(h), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                    OutlinedButton(onClick = { h = (h + 1) % 13 }, shape = androidx.compose.foundation.shape.CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("+", fontSize = 18.sp, color = accent) }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 추천값을 맨 앞에 둔다. 없으면(표본 부족) 기존 고정 칩만.
                    (listOfNotNull(best.takeIf { it >= 0 }?.let { it to "추천 ${it}시" }) +
                     listOf(0 to "자정", 4 to "새벽4시", 5 to "새벽5시", 9 to "오전9시")).forEach { (v, lb) ->
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
