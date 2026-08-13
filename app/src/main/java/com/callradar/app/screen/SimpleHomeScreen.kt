package com.callradar.app.screen

// [심플 홈 · 옵트인 B안] 카카오식 무탭 홈. classic(기존)과 완전 분리된 신규 파일 → 회귀 격리.
// 근무세션은 기존과 동일한 prefs 키/서버 엔드포인트를 공유해 모드를 바꿔도 상태가 이어진다.
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.callradar.app.WorkSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private data class SimpleCard(val id: String, val icon: String, val label: String)

// 홈 4칸 기본값(사용 감사 상위: 기록·레이더·공항·정산). 추후 편집 가능.
private val SIMPLE_CARD_REGISTRY = listOf(
    SimpleCard("records", "📋", "기록"),
    SimpleCard("radar", "📡", "레이더"),
    SimpleCard("airport", "✈️", "공항"),
    SimpleCard("settlement", "🧮", "정산"),
    SimpleCard("track", "🗺️", "궤적"),
    SimpleCard("stats", "📊", "분석")
)

@Composable
fun SimpleHomeScreen(
    userId: String,
    onOpenMenu: () -> Unit,
    onOpenCard: (String) -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val accent = Color(0xFFF59E0B); val green = Color(0xFF10B981); val red = Color(0xFFEF4444); val muted = Color(0xFF6B7280)
    val SERVER_URL = Config.SERVER_URL

    // 근무세션 상태 (classic과 동일 prefs)
    var workStart by remember { mutableStateOf(prefs.getLong("work_start", 0L)) }
    var pausedTotal by remember { mutableStateOf(prefs.getLong("work_paused_total", 0L)) }
    var pauseStart by remember { mutableStateOf(prefs.getLong("work_pause_start", 0L)) }
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    var workDist by remember { mutableStateOf(prefs.getFloat("work_distance_m", 0f)) }
    var lastLocalChange by remember { mutableStateOf(0L) }
    var todayFare by remember { mutableStateOf(0) }
    var showEndConfirm by remember { mutableStateOf(false) }
    val distEnabled = prefs.getBoolean("work_dist_enabled", true)
    val active = workStart > 0L
    val paused = pauseStart > 0L
    val driverType = prefs.getString("driver_type", "personal") ?: "personal"

    fun startMeter() { try { ContextCompat.startForegroundService(context, Intent(context, WorkSessionService::class.java)) } catch (e: Exception) {} }
    fun stopMeter() { try { context.stopService(Intent(context, WorkSessionService::class.java)) } catch (e: Exception) {} }

    fun workDayKey(): Long {
        val h = prefs.getInt("day_start_hour", 0)
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"))
        if (c.get(java.util.Calendar.HOUR_OF_DAY) < h) c.add(java.util.Calendar.DAY_OF_YEAR, -1)
        c.set(java.util.Calendar.HOUR_OF_DAY, h); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
    fun pushWorkSession(ws: Long, pt: Long, ps: Long, sf: Int) {
        lastLocalChange = System.currentTimeMillis()
        if (userId.isEmpty()) return
        scope.launch { try { withContext(Dispatchers.IO) {
            val json = JSONObject().apply { put("user_id", userId); put("work_start", ws); put("paused_total", pt); put("pause_start", ps); put("start_fare", sf) }
            val conn = (URL("$SERVER_URL/api/work-session").openConnection().apply { com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 8000; readTimeout = 15000 }
            conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }; conn.responseCode
        } } catch (e: Exception) {} }
    }

    // 오늘 매출 폴링 (classic /api/today와 동일: 콜제보 제외 서버측 처리)
    LaunchedEffect(Unit) {
        while (true) {
            if (userId.isNotEmpty()) {
                try {
                    val ds = prefs.getInt("day_start_hour", 0)
                    val o = withContext(Dispatchers.IO) {
                        val conn = (URL("$SERVER_URL/api/today/$userId?dayStart=$ds").openConnection().apply { com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 15000 }
                        JSONObject(conn.inputStream.bufferedReader().readText())
                    }
                    todayFare = o.optInt("todayFare", 0)
                } catch (e: Exception) {}
            }
            delay(30000)
        }
    }
    // 라이브 타이머 + 거리 갱신
    LaunchedEffect(active, paused) {
        while (active && !paused) { nowTick = System.currentTimeMillis(); workDist = prefs.getFloat("work_distance_m", 0f); delay(1000) }
    }
    // 투폰 근무세션 pull (classic과 동일 30초 가드)
    LaunchedEffect(Unit) {
        if (userId.isEmpty()) return@LaunchedEffect
        while (true) {
            try {
                val o = withContext(Dispatchers.IO) { JSONObject((URL("$SERVER_URL/api/work-session/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 15000 }.inputStream.bufferedReader().readText()) }
                val rws = o.optLong("work_start", workStart); val rpt = o.optLong("paused_total", pausedTotal); val rps = o.optLong("pause_start", pauseStart)
                if ((rws != workStart || rpt != pausedTotal || rps != pauseStart) && System.currentTimeMillis() - lastLocalChange > 30000L) {
                    workStart = rws; pausedTotal = rpt; pauseStart = rps; nowTick = System.currentTimeMillis()
                    prefs.edit().putLong("work_start", rws).putLong("work_paused_total", rpt).putLong("work_pause_start", rps).apply()
                    if (rws > 0L && rps == 0L && distEnabled) startMeter() else if (rws == 0L) stopMeter()
                }
            } catch (e: Exception) {}
            delay(20000)
        }
    }

    val doStart = {
        val t = System.currentTimeMillis(); workStart = t; pausedTotal = 0L; pauseStart = 0L; nowTick = t
        val dayKey = workDayKey(); val newDay = prefs.getLong("work_day_key", 0L) != dayKey
        val e = prefs.edit().putLong("work_start", t).putLong("work_paused_total", 0L).putLong("work_pause_start", 0L).putInt("work_start_fare", todayFare)
        if (newDay) { e.putLong("work_day_key", dayKey).putLong("work_day_net_ms", 0L).putLong("work_day_gross_ms", 0L).putInt("work_day_start_fare", todayFare).putFloat("work_distance_m", 0f) }
        e.apply(); workDist = if (newDay) 0f else prefs.getFloat("work_distance_m", 0f)
        pushWorkSession(t, 0L, 0L, todayFare); com.callradar.app.Telemetry.log(context, "shift_start", "simple_home"); if (distEnabled) startMeter()
    }
    val doPauseResume = {
        val t = System.currentTimeMillis()
        if (paused) { pausedTotal += (t - pauseStart); pauseStart = 0L; nowTick = t; prefs.edit().putLong("work_paused_total", pausedTotal).putLong("work_pause_start", 0L).apply(); pushWorkSession(workStart, pausedTotal, 0L, prefs.getInt("work_start_fare", 0)); if (distEnabled) startMeter() }
        else { pauseStart = t; prefs.edit().putLong("work_pause_start", t).apply(); pushWorkSession(workStart, pausedTotal, t, prefs.getInt("work_start_fare", 0)); stopMeter() }
    }
    val doEnd = {
        try {
            val now = System.currentTimeMillis()
            val netMs = ((now - workStart) - pausedTotal - (if (paused) now - pauseStart else 0L)).coerceAtLeast(0L)
            val grossMs = (now - workStart).coerceAtLeast(0L)
            val dayKey = workDayKey(); val sameDay = prefs.getLong("work_day_key", 0L) == dayKey
            val realSession = grossMs < 16L * 3600000L
            val dayNetMs = (if (sameDay) prefs.getLong("work_day_net_ms", 0L) else 0L) + (if (realSession) netMs else 0L)
            val dayGrossMs = (if (sameDay) prefs.getLong("work_day_gross_ms", 0L) else 0L) + (if (realSession) grossMs else 0L)
            val dayStartFare = if (sameDay) prefs.getInt("work_day_start_fare", prefs.getInt("work_start_fare", 0)) else prefs.getInt("work_start_fare", 0)
            prefs.edit().putLong("work_day_key", dayKey).putLong("work_day_net_ms", dayNetMs).putLong("work_day_gross_ms", dayGrossMs).putInt("work_day_start_fare", dayStartFare).apply()
            val sFare = (todayFare - dayStartFare).coerceAtLeast(0)
            val pH = if (dayNetMs > 3000000L) (sFare / (dayNetMs / 3600000.0)).toInt() else 0
            val dKm = prefs.getFloat("work_distance_m", 0f) / 1000f
            workStart = 0L; pausedTotal = 0L; pauseStart = 0L
            prefs.edit().putLong("work_start", 0L).putLong("work_paused_total", 0L).putLong("work_pause_start", 0L).apply()
            pushWorkSession(0L, 0L, 0L, 0); stopMeter()
            com.callradar.app.Telemetry.log(context, "shift_end", "simple_home", meta = sFare.toString())
            // 서버 근무세션 요약 저장 (classic과 동일)
            if (userId.isNotEmpty()) scope.launch { try { withContext(Dispatchers.IO) {
                val j = JSONObject().apply { put("user_id", userId); put("started_at", workStart); put("ended_at", now); put("gross_min", dayGrossMs / 60000L); put("net_min", dayNetMs / 60000L); put("dist_km", dKm.toDouble()); put("fare", sFare); put("per_hour", pH) }
                val conn = (URL("$SERVER_URL/api/work-session/close").openConnection().apply { com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 8000; readTimeout = 15000 }
                conn.outputStream.use { it.write(j.toString().toByteArray(Charsets.UTF_8)) }; conn.responseCode
            } } catch (e: Exception) {} }
        } catch (e: Exception) {
            try { workStart = 0L; pausedTotal = 0L; pauseStart = 0L; prefs.edit().putLong("work_start", 0L).putLong("work_paused_total", 0L).putLong("work_pause_start", 0L).apply(); stopMeter() } catch (e2: Exception) {}
        }
    }

    // 계산값
    val sameDayLive = prefs.getLong("work_day_key", 0L) == workDayKey()
    val dayNetPrev = if (sameDayLive) prefs.getLong("work_day_net_ms", 0L).coerceAtMost(16L * 3600000L) else 0L
    val curNet = if (!active) 0L else ((nowTick - workStart) - pausedTotal - (if (paused) nowTick - pauseStart else 0L)).coerceAtLeast(0L)
    val workedMin = (dayNetPrev + curNet) / 60000L
    val hh = workedMin / 60; val mm = workedMin % 60
    val sStartFare = if (sameDayLive) prefs.getInt("work_day_start_fare", prefs.getInt("work_start_fare", 0)) else prefs.getInt("work_start_fare", 0)
    val sessionFare = (todayFare - sStartFare).coerceAtLeast(0)
    val workedHours = (dayNetPrev + curNet).toDouble() / 3600000.0
    val perHour = if (workedHours > 0.05) (sessionFare / workedHours).toInt() else 0
    val distKm = workDist / 1000f

    if (showEndConfirm) {
        AlertDialog(
            onDismissRequest = { showEndConfirm = false },
            title = { Text("퇴근할까요?", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Text("지금까지 근무 ${hh}시간 ${mm}분. 퇴근하면 세션이 끝나요.", fontSize = 13.sp, color = muted) },
            confirmButton = { Button(onClick = { showEndConfirm = false; doEnd() }, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("퇴근", color = Color.White, fontWeight = FontWeight.Bold) } },
            dismissButton = { OutlinedButton(onClick = { showEndConfirm = false }) { Text("계속 근무") } },
            containerColor = AppTheme.card
        )
    }

    val cardIds = (prefs.getString("simple_home_cards", "records,radar,airport,settlement") ?: "records,radar,airport,settlement").split(",").mapNotNull { id -> SIMPLE_CARD_REGISTRY.find { it.id == id.trim() } }.take(4)

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.bg).padding(14.dp)) {
        // 헤더: 공지 · 메뉴
        Row(modifier = Modifier.fillMaxWidth().padding(top = 34.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("콜레이더", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = accent)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://open.kakao.com/o/gsyuVMCi")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {} }) { Text("📢 공지", fontSize = 13.sp, color = muted) }
            TextButton(onClick = onOpenMenu) { Text("☰ 메뉴", fontSize = 14.sp, color = AppTheme.text, fontWeight = FontWeight.Bold) }
        }

        // 히어로
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(18.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (!active) "근무 시작 전" else if (paused) "근무 일시정지" else "근무 중 · ${hh}시간 ${mm}분", fontSize = 12.sp, color = if (active && !paused) green else muted)
                Spacer(Modifier.height(8.dp))
                Text("${String.format("%,d", todayFare)}원", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = if (todayFare > 0) green else muted)
                Text("오늘 매출", fontSize = 11.sp, color = muted)
                if (active) {
                    Spacer(Modifier.height(6.dp))
                    Text("시간당 ${String.format("%,d", perHour)}원 · ${String.format("%.1f", distKm)}km", fontSize = 12.sp, color = muted)
                }
                Spacer(Modifier.height(18.dp))
                if (!active) {
                    Button(onClick = doStart, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = green), shape = RoundedCornerShape(14.dp)) { Text("🟢 출근하기", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = doPauseResume, modifier = Modifier.weight(1f).height(46.dp), shape = RoundedCornerShape(12.dp)) { Text(if (paused) "▶ 재개" else "⏸ 일시정지", color = accent, fontWeight = FontWeight.Bold) }
                        Button(onClick = { showEndConfirm = true }, modifier = Modifier.weight(1f).height(46.dp), colors = ButtonDefaults.buttonColors(containerColor = red), shape = RoundedCornerShape(12.dp)) { Text("퇴근", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 기능 4카드 (2×2)
        cardIds.chunked(2).forEach { rowCards ->
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowCards.forEach { c ->
                    Card(modifier = Modifier.weight(1f).height(96.dp).clickable { onOpenCard(c.id) }, colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(16.dp)) {
                        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Text(c.icon, fontSize = 24.sp)
                            Text(c.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                        }
                    }
                }
                if (rowCards.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        Spacer(Modifier.weight(1f))
        Text("심플 모드(베타) · 메뉴 › 홈 모드에서 기본으로 되돌릴 수 있어요", fontSize = 10.sp, color = muted, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp))
    }
}
