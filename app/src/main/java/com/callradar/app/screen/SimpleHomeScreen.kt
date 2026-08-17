package com.callradar.app.screen

// [심플 홈 · 옵트인 B안] 카카오식 무탭 홈. classic(기존)과 완전 분리된 신규 파일 → 회귀 격리.
// 근무세션은 기존과 동일한 prefs 키/서버 엔드포인트를 공유해 모드를 바꿔도 상태가 이어진다.
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

private data class SimpleCard(val id: String, val icon: String, val label: String, val color: Long = 0xFFF59E0B, val desc: String = "")

// [목업 반영] 콜카드에 색·설명 추가. 편집에서 켜고 끌 수 있음.
private val SIMPLE_CARD_REGISTRY = listOf(
    SimpleCard("record_settings", "🤖", "자동설정", 0xFF10B981, "운행버튼·자동기록·금액입력"),
    SimpleCard("radar", "📡", "레이더", 0xFFF59E0B, "지금 콜 잘 잡히는 자리·핫존"),
    SimpleCard("airport", "✈️", "공항", 0xFF38BDF8, "인천공항 실시간 입국·수요"),
    SimpleCard("records", "📋", "기록·정산", 0xFF3B82F6, "운행 기록·월별 정산·지출"),
    // [기록·정산 통합] '정산' 콜카드 제거 — 기록 안에 월별 탭 존재 + 퇴근 시 일일정산 자동 표시
    SimpleCard("track", "🗺️", "궤적", 0xFF4ADE80, "오늘 실차·공차 경로"),
    SimpleCard("stats", "📊", "분석", 0xFF22D3EE, "수입 추세·시간대 통계"),
    SimpleCard("ranking", "🏆", "랭킹", 0xFFFBBF24, "내 순위·지역 랭킹"),
    SimpleCard("knowhow", "📝", "내 노하우", 0xFFA78BFA, "🔒 폰에만 저장 · 나만의 영업수첩"),
    SimpleCard("salary", "💰", "월급 예상", 0xFF34D399, "사납·기본급 규칙으로 실수령 계산"),
    SimpleCard("tax", "🧾", "세무 리포트", 0xFFF87171, "개인·도급 종소세와 경비 한눈에")
)

@Composable
fun SimpleHomeScreen(
    userId: String,
    onOpenMenu: () -> Unit,
    onOpenCard: (String) -> Unit,
    onToggleFloating: (Boolean) -> Unit = {},
    isOverlayGranted: () -> Boolean = { false },
    onToggleNotifCapture: (Boolean) -> Unit = {},
    isNotifAccessGranted: () -> Boolean = { false }
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
    // [로딩개선] 마지막 값을 캐시해 열자마자 표시(0원 깜빡임·서버 슬립 30~60초 공백 제거), 서버 응답 오면 갱신
    LaunchedEffect(Unit) {
        val dayKeyNow = (System.currentTimeMillis() + 9 * 3600_000L) / 86400_000L   // KST 날짜 키
        if (prefs.getLong("cache_today_day", -1L) == dayKeyNow) {
            val c = prefs.getInt("cache_today_fare", -1); if (c >= 0) todayFare = c
        }
        while (true) {
            if (userId.isNotEmpty()) {
                try {
                    val ds = prefs.getInt("day_start_hour", 0)
                    val o = withContext(Dispatchers.IO) {
                        val conn = (URL("$SERVER_URL/api/today/$userId?dayStart=$ds").openConnection().apply { com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 15000 }
                        JSONObject(conn.inputStream.bufferedReader().readText())
                    }
                    todayFare = o.optInt("todayFare", 0)
                    try { prefs.edit().putInt("cache_today_fare", todayFare).putLong("cache_today_day", dayKeyNow).apply() } catch (e: Exception) {}
                } catch (e: Exception) {}
            }
            delay(30000)
        }
    }
    // 라이브 타이머 + 거리 갱신
    // [버그수정] 예전엔 매초 nowTick을 갱신해 히어로 전체가 초당 재구성 → 근무중 '일시정지/퇴근' 버튼 탭이
    //  재구성 프레임에 씹혀 반응 안 하던 문제. 이제 '분'이 바뀔 때만(=표시값 변할 때만) 갱신 → 재구성 분당 1회로 급감.
    LaunchedEffect(active, paused) {
        while (active && !paused) {
            val now = System.currentTimeMillis()
            if ((now - workStart) / 60000L != (nowTick - workStart) / 60000L) nowTick = now
            val d = prefs.getFloat("work_distance_m", 0f); if (d != workDist) workDist = d
            delay(1000)
        }
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
                    // [km폭주 수정③] pull은 미터 자동시작 안 함(유휴 보조폰 유령거리 차단). 원격 퇴근 시 중지+거리리셋.
                    if (rws == 0L) { stopMeter(); prefs.edit().putBoolean("meter_local", false).putFloat("work_distance_m", 0f).apply(); workDist = 0f }
                }
            } catch (e: Exception) {}
            delay(20000)
        }
    }
    // [km폭주③] 앱 재시작 시: 소유폰(로컬 출근한 폰)만 미터 재개. pull은 미터 안 켜므로 여기서 복원.
    LaunchedEffect(Unit) { if (workStart > 0L && pauseStart == 0L && prefs.getBoolean("meter_local", false) && distEnabled) startMeter() }

    val doStart = {
        val t = System.currentTimeMillis(); workStart = t; pausedTotal = 0L; pauseStart = 0L; nowTick = t
        val dayKey = workDayKey(); val newDay = prefs.getLong("work_day_key", 0L) != dayKey
        // [km폭주 수정③] meter_local=true → 이 폰이 미터 소유자(로컬 출근). pull은 미터 안 켜므로 소유폰만 거리 누적.
        val e = prefs.edit().putLong("work_start", t).putLong("work_paused_total", 0L).putLong("work_pause_start", 0L).putInt("work_start_fare", todayFare).putBoolean("meter_local", true)
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
            val startedAtMs = workStart   // 리셋 전 시작시각 보존(서버 요약용)
            val netMs = ((now - workStart) - pausedTotal - (if (paused) now - pauseStart else 0L)).coerceAtLeast(0L)
            val grossMs = (now - workStart).coerceAtLeast(0L)
            val dayKey = workDayKey(); val sameDay = prefs.getLong("work_day_key", 0L) == dayKey
            val realSession = grossMs < 16L * 3600000L
            val dayNetMs = (if (sameDay) prefs.getLong("work_day_net_ms", 0L) else 0L) + (if (realSession) netMs else 0L)
            val dayGrossMs = (if (sameDay) prefs.getLong("work_day_gross_ms", 0L) else 0L) + (if (realSession) grossMs else 0L)
            val dayStartFare = if (sameDay) prefs.getInt("work_day_start_fare", prefs.getInt("work_start_fare", 0)) else prefs.getInt("work_start_fare", 0)
            prefs.edit().putLong("work_day_key", dayKey).putLong("work_day_net_ms", dayNetMs).putLong("work_day_gross_ms", dayGrossMs).putInt("work_day_start_fare", dayStartFare).apply()
            val sFare = todayFare.coerceAtLeast(0)   // [시간당매출 정정] 오늘 총매출 기준(라이브 카드와 일치)
            val pH = if (dayNetMs > 3000000L) (sFare / (dayNetMs / 3600000.0)).toInt() else 0
            val dKm = if (realSession) prefs.getFloat("work_distance_m", 0f) / 1000f else 0f   // [km폭주②] 비현실(>16h) 세션 거리 신뢰불가→0
            workStart = 0L; pausedTotal = 0L; pauseStart = 0L
            // [km폭주①③] 퇴근 시 거리 0 초기화 + 미터 소유 해제.
            prefs.edit().putLong("work_start", 0L).putLong("work_paused_total", 0L).putLong("work_pause_start", 0L).putFloat("work_distance_m", 0f).putBoolean("meter_local", false).apply(); workDist = 0f
            pushWorkSession(0L, 0L, 0L, 0); stopMeter()
            com.callradar.app.Telemetry.log(context, "shift_end", "simple_home", meta = sFare.toString())
            // 서버 근무세션 요약 저장 (classic과 동일)
            if (userId.isNotEmpty()) scope.launch { try { withContext(Dispatchers.IO) {
                val j = JSONObject().apply { put("user_id", userId); put("started_at", startedAtMs); put("ended_at", now); put("gross_min", dayGrossMs / 60000L); put("net_min", dayNetMs / 60000L); put("dist_km", dKm.toDouble()); put("fare", sFare); put("per_hour", pH) }
                val conn = (URL("$SERVER_URL/api/work-session/close").openConnection().apply { com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 8000; readTimeout = 15000 }
                conn.outputStream.use { it.write(j.toString().toByteArray(Charsets.UTF_8)) }; conn.responseCode
            } } catch (e: Exception) {} }
        } catch (e: Exception) {
            try { workStart = 0L; pausedTotal = 0L; pauseStart = 0L; prefs.edit().putLong("work_start", 0L).putLong("work_paused_total", 0L).putLong("work_pause_start", 0L).putFloat("work_distance_m", 0f).putBoolean("meter_local", false).apply(); workDist = 0f; stopMeter() } catch (e2: Exception) {}
        }
    }

    // 계산값
    val sameDayLive = prefs.getLong("work_day_key", 0L) == workDayKey()
    val dayNetPrev = if (sameDayLive) prefs.getLong("work_day_net_ms", 0L).coerceAtMost(16L * 3600000L) else 0L
    val curNet = if (!active) 0L else ((nowTick - workStart) - pausedTotal - (if (paused) nowTick - pauseStart else 0L)).coerceAtLeast(0L)
    val workedMin = (dayNetPrev + curNet) / 60000L
    val hh = workedMin / 60; val mm = workedMin % 60
    val workedHours = (dayNetPrev + curNet).toDouble() / 3600000.0
    val perHour = if (workedHours > 0.05) (todayFare / workedHours).toInt() else 0   // [시간당매출 정정] 오늘 총매출 ÷ 근무시간
    val distKm = workDist / 1000f

    if (showEndConfirm) {
        AlertDialog(
            onDismissRequest = { showEndConfirm = false },
            title = { Text("퇴근할까요?", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Text("지금까지 근무 ${hh}시간 ${mm}분. 퇴근하면 세션이 끝나요.", fontSize = 13.sp, color = muted) },
            confirmButton = { Button(onClick = { showEndConfirm = false; doEnd(); onOpenCard("settlement") }, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("퇴근", color = Color.White, fontWeight = FontWeight.Bold) } },   // [기록·정산 통합] 퇴근 즉시 일일정산 자동 표시
            dismissButton = { OutlinedButton(onClick = { showEndConfirm = false }) { Text("계속 근무") } },
            containerColor = AppTheme.card
        )
    }

    val defCards = "record_settings,radar,airport,records,stats"   // [통합] settlement 제거
    val selCards = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll((prefs.getString("simple_home_cards", defCards) ?: defCards).split(",").map { it.trim() }.filter { it.isNotBlank() }) } }
    fun saveCards() { prefs.edit().putString("simple_home_cards", selCards.joinToString(",")).apply() }
    var editMode by remember { mutableStateOf(false) }
    var showAutoSetup by remember { mutableStateOf(false) }

    // [자동설정 콜카드] 3종 토글(운행버튼·자동기록·금액입력) 상태 — 콜카드 탭 시 다이얼로그로 표시.
    var floatingOn by remember { mutableStateOf(prefs.getBoolean("floating_on", false)) }
    val acctAdmin = prefs.getBoolean("acct_admin", prefs.getBoolean("is_admin", false))
    val acctEntitled = prefs.getBoolean("acct_entitled", false)
    val showAuto = com.callradar.app.BuildConfig.FLAVOR == "onestore" && (acctAdmin || acctEntitled)
    var autoRec by remember { mutableStateOf(prefs.getBoolean("auto_record_on", false)) }
    val showNotif = Config.NOTIF_CAPTURE_ENABLED && prefs.getBoolean("card_notif", true)
    var capOn by remember { mutableStateOf(prefs.getBoolean("notif_capture_on", false) && isNotifAccessGranted()) }
    var showRecordSettings by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.bg).verticalScroll(rememberScrollState()).padding(14.dp)) {
        // 헤더: 공지 · 메뉴
        Row(modifier = Modifier.fillMaxWidth().padding(top = 34.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("콜레이더", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = accent)
            Spacer(Modifier.weight(1f))
            // [모드 전환] 상단 원터치 — 기본홈으로 (기본홈에도 동일 버튼, 언제든 왕복)
            TextButton(onClick = {
                prefs.edit().putString("home_mode", "classic").apply()
                (context as? android.app.Activity)?.recreate()
            }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("⇄ 기본홈", fontSize = 12.sp, color = muted) }
            TextButton(onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://open.kakao.com/o/gsyuVMCi")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {} }) { Text("💬 톡방", fontSize = 13.sp, color = muted) }
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

        // [자동설정] 3종 토글은 이제 '자동설정' 콜카드로 내림 → 아래 콜카드 그리드 + showRecordSettings 다이얼로그.

        Spacer(Modifier.height(14.dp))

        // [콜카드] 홈에 보일 콜카드(색·설명) + 인라인 편집. (편집 시 카드 탭 = 켜고 끄기, 평소 = 바로가기)
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("콜카드", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
            if (editMode) { Spacer(Modifier.width(8.dp)); Text("탭해서 홈에 켜고 끄기", fontSize = 11.sp, color = muted) }
            Spacer(Modifier.weight(1f))
            if (editMode) {
                // 편집 중일 때만 채운 버튼(활성 상태 표시)
                Button(onClick = { saveCards(); editMode = false },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    shape = RoundedCornerShape(999.dp)) {
                    Text("완료", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            } else {
                // 평소엔 조작버튼임이 드러나게 투명 텍스트 버튼(카드처럼 안 보이게)
                TextButton(onClick = { editMode = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                    Text("✏️", fontSize = 13.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("편집", fontSize = 13.sp, color = muted)
                }
            }
        }

        // [박스 타일 2열 그리드] 목업처럼 세로 줄이 아니라 박스 카드로. 편집 모드면 전체(켜짐/꺼짐), 평소엔 켜진 것만.
        val shown = if (editMode) SIMPLE_CARD_REGISTRY else SIMPLE_CARD_REGISTRY.filter { selCards.contains(it.id) }.sortedBy { selCards.indexOf(it.id) }
        shown.chunked(2).forEach { rowCards ->
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowCards.forEach { c ->
                    val on = selCards.contains(c.id)
                    Card(modifier = Modifier.weight(1f).height(124.dp).clickable {
                            if (editMode) { if (on) selCards.remove(c.id) else selCards.add(c.id) }
                            else if (c.id == "record_settings") showRecordSettings = true
                            else onOpenCard(c.id)
                        },
                        colors = CardDefaults.cardColors(containerColor = if (editMode && !on) AppTheme.card.copy(alpha = 0.45f) else AppTheme.card),
                        shape = RoundedCornerShape(18.dp)) {
                        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(40.dp).background(Color(c.color).copy(alpha = 0.18f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                    Text(c.icon, fontSize = 21.sp)
                                }
                                Spacer(Modifier.weight(1f))
                                if (editMode) Text(if (on) "✓" else "＋", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (on) Color(c.color) else muted)
                            }
                            Column {
                                Text(c.label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (editMode && !on) muted else AppTheme.text)
                                if (c.desc.isNotEmpty()) Text(c.desc, fontSize = 10.5.sp, color = muted, maxLines = 2)
                            }
                        }
                    }
                }
                if (rowCards.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        if (!editMode) {
            // [역제안] 전체 콜카드 = 채운 카드가 아니라 외곽선(고스트) 버튼 → '더보기 조작'임이 드러남
            OutlinedButton(onClick = onOpenMenu, modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 2.dp), shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, muted.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent)) {
                Text("⋯  전체 콜카드", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = muted)
            }
        }

        Text("심플 모드(베타) · 메뉴 › 홈 모드에서 기본으로 되돌릴 수 있어요", fontSize = 10.sp, color = muted, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp, bottom = 20.dp))
    }

    // [자동설정 다이얼로그] '자동설정' 콜카드 탭 → 3종 토글(운행버튼·자동기록·금액입력).
    if (showRecordSettings) {
        AlertDialog(
            onDismissRequest = { showRecordSettings = false },
            title = { Text("🤖 자동설정", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (floatingOn) "🟢 운행 기록 버튼 켜짐" else "🚕 운행 기록 버튼", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                            Text("시작·완료 두 번이면 기록 끝", fontSize = 11.sp, color = muted)
                        }
                        Switch(checked = floatingOn, onCheckedChange = { on -> onToggleFloating(on); floatingOn = if (on) isOverlayGranted() else false; com.callradar.app.Telemetry.log(context, if (on) "floating_on" else "floating_off", "simple_home") })
                    }
                    if (showAuto) {
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppTheme.surface2))
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f).clickable { showAutoSetup = true }) {
                                Text(if (autoRec) "🤖 자동 기록 켜짐 (관리자)" else "🤖 자동 기록 (관리자)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                                Text("택시앱 운행·요금 자동 기록 (탭: 설정 점검)", fontSize = 11.sp, color = muted)
                            }
                            Switch(checked = autoRec, onCheckedChange = { on ->
                                autoRec = on
                                prefs.edit().putBoolean("auto_record_on", on).putBoolean("auto_record_touched", true).apply()
                                if (on) showAutoSetup = true else try { context.stopService(Intent(context, com.callradar.app.LocationTrackingService::class.java)) } catch (e: Exception) {}
                                com.callradar.app.Telemetry.log(context, if (on) "auto_record_on" else "auto_record_off", "simple_home")
                            })
                        }
                    }
                    if (showNotif) {
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppTheme.surface2))
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(if (capOn) "💰 금액 자동 입력 켜짐" else "💰 금액 자동 입력 (베타)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                                Text("카드결제 알림 금액 자동 입력", fontSize = 11.sp, color = muted)
                            }
                            Switch(checked = capOn, onCheckedChange = { on -> prefs.edit().putBoolean("notif_capture_on", on).apply(); onToggleNotifCapture(on); capOn = if (on) isNotifAccessGranted() else false; com.callradar.app.Telemetry.log(context, if (on) "notif_capture_on" else "notif_capture_off", "simple_home") })
                        }
                    }
                    // [구글 정책] play 버전은 접근성 완전자동 미제공 → 자동화 원하면 원스토어로 안내
                    if (com.callradar.app.BuildConfig.FLAVOR != "onestore") {
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppTheme.surface2))
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Text("🤖 완전 자동 기록을 원하세요?", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                            Text("구글플레이 버전은 정책상 수동·반자동만 제공해요. 택시앱 운행·요금을 손대지 않고 자동 기록하려면 원스토어 버전(콜레이더:택시의 신)을 설치하세요.", fontSize = 11.sp, color = muted, lineHeight = 16.sp)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    com.callradar.app.Telemetry.log(context, "onestore_guide_tap", "simple_home")
                                    val web = "https://m.onestore.co.kr/v2/ko-kr/app/0001007971"
                                    try { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("onestore://common/product/0001007971")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                    catch (e: Exception) { try { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) {} }
                                },
                                border = BorderStroke(1.dp, accent),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent)
                            ) { Text("원스토어에서 자동 버전 받기", fontSize = 13.sp, color = accent, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { showRecordSettings = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("닫기", color = Color.Black, fontWeight = FontWeight.Bold) } },
            containerColor = AppTheme.card
        )
    }

    if (showAutoSetup) com.callradar.app.screen.AutoRecordSetupDialog(context) {
        showAutoSetup = false
        val acc = (android.provider.Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services") ?: "").contains("com.callradar.app/com.callradar.app.NaviIntentReceiver")
        if (acc) try { ContextCompat.startForegroundService(context, Intent(context, com.callradar.app.LocationTrackingService::class.java)) } catch (e: Exception) {}
    }
}
