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
    SimpleCard("events", "🎪", "행사", 0xFFF472B6, "야구 종료·대형 행사 수요 예보"),
    SimpleCard("insights", "📊", "인사이트", 0xFF818CF8, "내 성향·시간대별 유리한 콜"),
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
    var supplyInfo by remember { mutableStateOf<Pair<String, String>?>(null) }   // [귀로내비] (라벨, 부제)
    var showEndConfirm by remember { mutableStateOf(false) }
    // [v93 휴식 확인] 퇴근 시 '운행 없던 긴 구간'을 기사에게 확인받는다. 자동으로 빼지 않는다.
    var showRestCheck by remember { mutableStateOf(false) }
    var restGaps by remember { mutableStateOf<List<com.callradar.app.RestGaps.Gap>>(emptyList()) }
    var restChecked = remember { androidx.compose.runtime.mutableStateListOf<Boolean>() }
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
    // [귀로내비] 수급 지수 1회 조회 (서버 5분 캐시) — 실패 시 조용히 미표시
    LaunchedEffect(Unit) {
        if (userId.isEmpty()) return@LaunchedEffect
        try {
            val o = withContext(Dispatchers.IO) {
                JSONObject((URL("$SERVER_URL/api/supply-index/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 20000 }.inputStream.bufferedReader().readText())
            }
            supplyInfo = if (o.optBoolean("locked")) {
                "잠김" to "최근 7일 운행 ${o.optInt("need", 5)}건 기록되면 열려요 (지금 ${o.optInt("have", 0)}건)"
            } else {
                val label = o.optString("label", "보통"); val pct = o.optInt("pct", 0)
                label to when {
                    pct >= 20 -> "평소보다 ${pct}% 빨리 잡혀요 — 적극 운행 추천"
                    pct <= -20 -> "평소보다 ${-pct}% 느리게 잡혀요 — 핫존 위주로"
                    else -> "평소와 비슷한 흐름이에요"
                }
            }
        } catch (e: Exception) {}
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
    // [v93 자동출근 미반영 수정] prefs 감시 — 백그라운드가 바꾼 근무상태를 화면이 바로 따라간다.
    //
    // 자동출근(NaviIntentReceiver.ensureWorkStarted)과 자동재개(WorkResume)는 접근성 서비스·
    // 플로팅 서비스에서 prefs를 직접 쓴다. 그런데 Compose의 workStart는 최초 조립 때 한 번 읽은
    // 메모리 값이라, prefs가 바뀌어도 화면은 계속 '미출근'으로 남았다.
    // 유일한 갱신 경로가 20초 서버 pull이었는데 그건 서버 왕복이 성공해야만 돌고,
    // 실패하면 화면은 영영 0이었다. 그래서 "콜 잡으면 자동출근" 이 안 되는 것처럼 보였다.
    //   · 아래 라이브 타이머는 `while (active && !paused)` 라 미출근이면 아예 안 돈다 → 여기서 따로 본다.
    //   · prefs가 진실이므로 화면을 prefs에 맞춘다. lastLocalChange를 찍어 20초 pull이
    //     서버의 낡은 0으로 방금 켜진 출근을 되돌리지 못하게 막는다.
    LaunchedEffect(Unit) {
        while (true) {
            val pws = prefs.getLong("work_start", 0L)
            val ppt = prefs.getLong("work_paused_total", 0L)
            val pps = prefs.getLong("work_pause_start", 0L)
            if (pws != workStart || ppt != pausedTotal || pps != pauseStart) {
                workStart = pws; pausedTotal = ppt; pauseStart = pps
                nowTick = System.currentTimeMillis()
                lastLocalChange = System.currentTimeMillis()
                workDist = prefs.getFloat("work_distance_m", 0f)
            }
            delay(3000)
        }
    }
    // 투폰 근무세션 pull (classic과 동일 30초 가드)
    LaunchedEffect(Unit) {
        if (userId.isEmpty()) return@LaunchedEffect
        while (true) {
            try {
                val o = withContext(Dispatchers.IO) { JSONObject((URL("$SERVER_URL/api/work-session/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 15000 }.inputStream.bufferedReader().readText()) }
                val rws = o.optLong("work_start", workStart); val rpt = o.optLong("paused_total", pausedTotal); val rps = o.optLong("pause_start", pauseStart)
                // [v91] 이미 퇴근시킨 세션이 서버에서 되살아나는 것을 막는다.
                //  전엔 lastLocalChange로 30초만 막았는데, 앱을 다시 켜면 그 값이 0으로 초기화돼
                //  방어가 사라졌다. 그래서 퇴근을 눌러도 앱을 재실행하면 근무가 다시 켜졌다.
                //  (휴무일에 근무가 계속 살아나던 원인)
                //  내가 퇴근한 시각보다 먼저 시작된 출근은 이미 끝낸 세션이므로 무시한다.
                val endedAt = prefs.getLong("last_work_end", 0L)
                val resurrect = rws > 0L && endedAt > 0L && rws < endedAt
                if (resurrect) {
                    // 서버가 옛 값을 들고 있다 → 내 퇴근을 다시 밀어올려 바로잡는다
                    pushWorkSession(0L, 0L, 0L, 0)
                } else if ((rws != workStart || rpt != pausedTotal || rps != pauseStart) && System.currentTimeMillis() - lastLocalChange > 30000L) {
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
        // [근무 구간] 새 영업일이면 어제 구간 비우고, 출근 시각으로 첫 구간을 연다
        if (newDay) com.callradar.app.WorkSegments.clear(context)
        com.callradar.app.WorkResume.clear(context)   // [v93] 새 출근 → 지난 자동 재개 안내는 끝난 얘기
        com.callradar.app.WorkSegments.open(context, t)
        pushWorkSession(t, 0L, 0L, todayFare); com.callradar.app.Telemetry.log(context, "shift_start", "simple_home"); if (distEnabled) startMeter()
    }
    val doPauseResume = {
        val t = System.currentTimeMillis()
        com.callradar.app.WorkResume.clear(context)   // [v93] 기사가 직접 눌렀으면 자동 재개 안내는 의미가 없다
        if (paused) { pausedTotal += (t - pauseStart); pauseStart = 0L; nowTick = t; prefs.edit().putLong("work_paused_total", pausedTotal).putLong("work_pause_start", 0L).apply()
            com.callradar.app.WorkSegments.open(context, t)   // [근무 구간] 재개 → 새 구간
            pushWorkSession(workStart, pausedTotal, 0L, prefs.getInt("work_start_fare", 0)); if (distEnabled) startMeter() }
        else { pauseStart = t; prefs.edit().putLong("work_pause_start", t).apply()
            com.callradar.app.WorkSegments.close(context, t)  // [근무 구간] 일시정지 → 구간 닫기
            pushWorkSession(workStart, pausedTotal, t, prefs.getInt("work_start_fare", 0)); stopMeter() }
    }
    val doEnd = {
        try {
            val now = System.currentTimeMillis()
            val startedAtMs = workStart   // 리셋 전 시작시각 보존(서버 요약용)
            com.callradar.app.WorkResume.clear(context)          // [v93] 퇴근 → 자동 재개 안내 정리
            // [v93] 퇴근 = 위치 수집 종료. 신고서에 "근무 상태인 동안에 한함"으로 신고했으므로 실제로도 그래야 한다.
            try { context.stopService(Intent(context, com.callradar.app.LocationTrackingService::class.java)) } catch (e: Exception) {}
            com.callradar.app.WorkSegments.close(context, now)   // [근무 구간] 퇴근 → 마지막 구간 닫기
            // [유저요청] 퇴근하면 플로팅 버튼도 내림(설정은 유지 → 앱 재실행 시 자동 복귀)
            try { (context as? com.callradar.app.MainActivity)?.hideFloatingForShiftEnd() } catch (e: Exception) {}
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
            lastLocalChange = now
            // [km폭주①③] 퇴근 시 거리 0 초기화 + 미터 소유 해제.
            // [v93] last_work_end 기록 — classic 홈엔 있는데 간편모드엔 빠져 있었다.
            //  이 값이 없으면 위 pull의 v91 '퇴근 세션 부활 방지'(resurrect) 가드가 통째로 죽는다.
            //  간편모드 쓰는 기사는 퇴근해도 앱 재실행 시 근무가 되살아날 수 있었다.
            prefs.edit().putLong("work_start", 0L).putLong("work_paused_total", 0L).putLong("work_pause_start", 0L).putFloat("work_distance_m", 0f).putBoolean("meter_local", false).putLong("last_work_end", now).apply(); workDist = 0f
            pushWorkSession(0L, 0L, 0L, 0); stopMeter()
            com.callradar.app.Telemetry.log(context, "shift_end", "simple_home", meta = sFare.toString())
            // 서버 근무세션 요약 저장 (classic과 동일)
            if (userId.isNotEmpty()) scope.launch { try { withContext(Dispatchers.IO) {
                /* [v94 매출 0원 수정] 세션 매출은 '오늘 매출'이 아니라 '세션 기간 매출'로 구한다.
                 *
                 * 2026-08-26 기사 제보: 영업일 시작 09시 설정에 09:03 퇴근 → 305,400원이 0원으로 저장됐다.
                 * todayFare 는 /api/today 값이라 그 순간 이미 새 영업일을 보고 있었다(3분치 = 0원).
                 * 근무시간·거리는 세션 값이라 멀쩡했고 매출만 날아갔다.
                 *
                 * 여기는 이미 백그라운드라 한 번 더 물어봐도 퇴근이 느려지지 않는다.
                 * 실패하면 기존 값을 그대로 쓴다 — 못 고치더라도 더 나빠지진 않게.
                 */
                var fixedFare = sFare
                var fixedPerHour = pH
                try {
                    if (startedAtMs > 0 && now > startedAtMs) {
                        val fc = (URL("$SERVER_URL/api/fare-range/$userId?from=$startedAtMs&to=$now").openConnection().apply {
                            com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") }
                        } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 15000 }
                        val fo = JSONObject(fc.inputStream.bufferedReader().readText())
                        val rangeFare = fo.optInt("fare", -1)
                        // 세션 구간에 운행이 있었다면 그 값이 진실이다. -1(조회 실패)이면 건드리지 않는다.
                        if (rangeFare >= 0 && rangeFare != sFare) {
                            fixedFare = rangeFare
                            fixedPerHour = if (dayNetMs > 3000000L) (rangeFare / (dayNetMs / 3600000.0)).toInt() else 0
                        }
                    }
                } catch (e: Exception) {}
                val j = JSONObject().apply { put("user_id", userId); put("started_at", startedAtMs); put("ended_at", now); put("gross_min", dayGrossMs / 60000L); put("net_min", dayNetMs / 60000L); put("dist_km", dKm.toDouble()); put("fare", fixedFare); put("per_hour", fixedPerHour) }
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
            // [v93] 퇴근 후 일일정산 화면 자동 열기 제거.
            //  영수증 OCR이 계속 틀리게 읽혀 매번 고쳐야 했고, 퇴근하고 폰 내려놓는 순간에
            //  창이 하나 더 뜨는 게 방해만 됐다. 정산이 필요하면 '기록·정산' 카드로 직접 들어간다.
            // [v93 휴식 확인] 퇴근을 누르면, 운행이 없던 긴 구간이 있었는지 먼저 본다.
            //  있으면 doEnd 전에 물어본다 — doEnd가 근무시간을 확정해 서버로 올리기 때문에 그 전이어야 한다.
            confirmButton = { Button(onClick = {
                showEndConfirm = false
                val g = try { com.callradar.app.RestGaps.find(context, workStart, System.currentTimeMillis()) } catch (e: Exception) { emptyList() }
                if (g.isEmpty()) doEnd() else { restGaps = g; restChecked = g.map { false }.toMutableStateList(); showRestCheck = true }
            }, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("퇴근", color = Color.White, fontWeight = FontWeight.Bold) } },
            dismissButton = { OutlinedButton(onClick = { showEndConfirm = false }) { Text("계속 근무") } },
            containerColor = AppTheme.card
        )
    }

    /* [v93 휴식 확인]
     *  운행이 없던 긴 구간을 보여주고, 쉰 시간이면 근무에서 빼준다.
     *  기본값은 '전부 체크 안 됨' — 아무것도 안 고르고 넘어가면 예전과 똑같이 동작한다.
     *  앱이 마음대로 빼는 게 아니라 기사가 고르는 것이다. 공항 대기 3시간은 근무고, 집에 다녀온 2시간은 아닌데
     *  그 차이는 기사만 안다. */
    if (showRestCheck) {
        val pickedMin = restGaps.indices.sumOf { if (restChecked.getOrElse(it) { false }) restGaps[it].minutes else 0L }
        AlertDialog(
            onDismissRequest = { },   // 밖을 눌러 실수로 닫히면 물어본 의미가 없다
            title = { Text("쉬신 시간이 있나요?", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("운행이 없던 구간이에요. 쉬신 시간이면 체크해 주세요. 근무시간에서 빼드릴게요.",
                        fontSize = 13.sp, color = muted)
                    Text("콜 기다린 시간이면 체크하지 마세요 — 그건 근무예요.",
                        fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(10.dp))
                    restGaps.forEachIndexed { i, g ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (i < restChecked.size) restChecked[i] = !restChecked[i]
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = restChecked.getOrElse(i) { false },
                                onCheckedChange = { if (i < restChecked.size) restChecked[i] = it }
                            )
                            Text(com.callradar.app.RestGaps.label(g), fontSize = 14.sp, color = AppTheme.text)
                        }
                    }
                    if (pickedMin > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text("총 ${pickedMin / 60}시간 ${pickedMin % 60}분을 근무시간에서 뺍니다",
                            fontSize = 13.sp, color = accent, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    // 고른 구간을 휴식으로 처리 — 일시정지 누른 것과 같은 효과.
                    //  pausedTotal에 더하고, 타임라인(WorkSegments)에서도 그 구간을 들어낸다.
                    var add = 0L
                    restGaps.forEachIndexed { i, g ->
                        if (restChecked.getOrElse(i) { false }) {
                            add += (g.end - g.start)
                            try { com.callradar.app.WorkSegments.cutOut(context, g.start, g.end) } catch (e: Exception) {}
                        }
                    }
                    if (add > 0) {
                        pausedTotal += add
                        prefs.edit().putLong("work_paused_total", pausedTotal).apply()
                        try { com.callradar.app.Telemetry.log(context, "rest_confirmed", "simple_home", meta = (add / 60000L).toString()) } catch (e: Exception) {}
                    }
                    showRestCheck = false
                    doEnd()
                }, colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                    Text(if (pickedMin > 0) "빼고 퇴근" else "그대로 퇴근", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRestCheck = false }) { Text("취소", color = muted) }
            },
            containerColor = AppTheme.card
        )
    }

    // [유저지시] 신규 설치 기본 콜카드: 기록·정산 / 공항 / 자동설정 / 궤적
    //  (레이더는 기본에서 빼고, 필요하면 '편집'에서 켜도록 — 처음 켠 기사에게 꼭 필요한 것만)
    // [v95][유저제보] 분석·인사이트를 기본에 넣는다.
    //  "간편모드에 통계가 안 보인다" — 편집에서 켜야만 나오니 대부분 존재를 몰랐다.
    //  실제로 가입 466명 중 운행을 1건이라도 기록한 사람이 124명뿐인데, 한 번 써본 사람의
    //  7일 유지율은 51%다. 제품이 아니라 '첫 경험까지 가는 길'이 막혀 있다는 뜻이라 기본 노출로 바꾼다.
    val defCards = "records,stats,insights,airport,record_settings,track"
    val selCards = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll((prefs.getString("simple_home_cards", defCards) ?: defCards).split(",").map { it.trim() }.filter { it.isNotBlank() }) } }
    fun saveCards() { prefs.edit().putString("simple_home_cards", selCards.joinToString(",")).apply() }
    // [v95] 기존 설치 1회 보정 — 기본값만 바꾸면 신규 설치에만 적용된다.
    //  이미 쓰고 있는 기사들은 저장된 카드 목록이 있어서 그대로면 여전히 분석을 못 본다.
    //  한 번만 끼워 넣고 플래그를 세운다(유저가 다시 빼면 그 선택을 존중 — 재추가하지 않는다).
    LaunchedEffect(Unit) {
        if (!prefs.getBoolean("simple_cards_v95_stats", false)) {
            var added = false
            listOf("stats", "insights").forEach { k -> if (!selCards.contains(k)) { selCards.add(k); added = true } }
            prefs.edit().putBoolean("simple_cards_v95_stats", true).apply()
            if (added) saveCards()
        }
    }
    var editMode by remember { mutableStateOf(false) }
    var showAutoSetup by remember { mutableStateOf(false) }

    // [자동설정 콜카드] 3종 토글(운행버튼·자동기록·금액입력) 상태 — 콜카드 탭 시 다이얼로그로 표시.
    // [v93] 권한이 걸린 토글은 '돌아왔을 때' 다시 읽는다.
    //  설정 화면에서 권한을 켜고 돌아와도 스위치가 꺼진 채였다 → 기사는 안 켜졌다고 판단한다.
    //  permCheckTick은 MainActivity.onResume이 올려주는 공용 신호다(설명은 그쪽 주석 참고).
    val permTickHome = com.callradar.app.MainActivity.permCheckTick.value
    var floatingOn by remember(permTickHome) { mutableStateOf(prefs.getBoolean("floating_on", false)) }
    val acctAdmin = prefs.getBoolean("acct_admin", prefs.getBoolean("is_admin", false))
    val acctEntitled = prefs.getBoolean("acct_entitled", false)
    val showAuto = com.callradar.app.BuildConfig.FLAVOR == "onestore" && (acctAdmin || acctEntitled)
    var autoRec by remember(permTickHome) { mutableStateOf(prefs.getBoolean("auto_record_on", false)) }
    val showNotif = Config.NOTIF_CAPTURE_ENABLED && prefs.getBoolean("card_notif", true)
    var capOn by remember(permTickHome) { mutableStateOf(prefs.getBoolean("notif_capture_on", false) && isNotifAccessGranted()) }
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
            // [유저요청⑧] 명함 — 홈모드처럼 간편모드 상단에서도 바로 (손님 앞에서 빨리 꺼내야 하는 기능)
            TextButton(onClick = { try { com.callradar.app.NameCardActivity.start(context) } catch (e: Exception) {} }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("📇 명함", fontSize = 13.sp, color = muted) }
            // [v91] 캡처 — 여긴 콜레이더 화면(매출·영수증)을 찍는 용도다.
            //  플랫폼 콜 화면은 앱 밖이라 플로팅 캡처 버튼으로 찍는다.
            IconButton(onClick = { try { com.callradar.app.ScreenCaptureService.shareShot(context) } catch (e: Exception) {} }, modifier = Modifier.size(38.dp)) {
                Text("📸", fontSize = 19.sp)
            }
            TextButton(onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://open.kakao.com/o/gsyuVMCi")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {} }) { Text("💬 톡방", fontSize = 13.sp, color = muted) }
            TextButton(onClick = onOpenMenu) { Text("☰ 메뉴", fontSize = 14.sp, color = AppTheme.text, fontWeight = FontWeight.Bold) }
        }

        // [v92] 설정 안내 카드 — 서버가 판단한 문제만 보여준다(가맹 미선택 등).
        //  high(사납금 등)는 MainActivity가 팝업으로 처리하므로 여기선 normal만.
        //  판단을 서버 한 곳에 둔 이유: 값이 채워지면 다음 호출에서 그냥 빠진다.
        //  (앱이 상태를 기억하면 '켰는데도 안 사라지는' 문제가 또 생긴다 — v91에서 겪었다)
        run {
            var tips by remember { mutableStateOf<List<com.callradar.app.SettingsSync.Issue>>(emptyList()) }
            val uid = remember { prefs.getString("user_id", "") ?: "" }
            // 설정하고 돌아왔을 때 사라져야 한다 → 화면이 다시 보일 때 다시 묻는다.
            //  (nowTick에 걸면 안 된다. 그건 근무 중에만 도는 타이머라 출근 전엔 멈춰 있다)
            var checkTick by remember { mutableStateOf(0) }
            val lo = androidx.compose.ui.platform.LocalLifecycleOwner.current
            DisposableEffect(lo) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                    if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) checkTick++
                }
                lo.lifecycle.addObserver(obs); onDispose { lo.lifecycle.removeObserver(obs) }
            }
            LaunchedEffect(uid, checkTick) {
                com.callradar.app.SettingsSync.health(context, uid) { list ->
                    tips = list.filter { it.severity != "high" && !com.callradar.app.SettingsSync.snoozed(context, it.code) }
                }
            }
            tips.firstOrNull()?.let { t ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        .clickable { com.callradar.app.MainActivity.settingsJump.value = true },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF14293D)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🚖", fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF60A5FA))
                            Text(t.body, fontSize = 11.sp, color = Color(0xFFB6C8DA), lineHeight = 16.sp)
                        }
                        TextButton(onClick = { com.callradar.app.SettingsSync.snooze(context, t.code); tips = emptyList() }) {
                            Text("나중에", fontSize = 11.sp, color = muted)
                        }
                        Text("›", fontSize = 22.sp, color = Color(0xFF60A5FA))
                    }
                }
            }
        }

        // [v91] 설치 점검 카드 — 톡방에 "왜 자동기록이 안 돼요" 문의가 계속 온다.
        //  대부분 권한 하나가 꺼져 있는 건데, 정작 본인은 그걸 모른다.
        //  그래서 묻기 전에 홈에서 먼저 알려준다. 다 켜져 있으면 이 카드는 안 뜬다(잔소리 금지).
        run {
            // [v92] 권한 재확인 시점 — 화면이 다시 보일 때(ON_RESUME)마다.
            //
            //  예전엔 remember(nowTick)에 물려 있었는데, nowTick은 '근무 중'일 때만 도는
            //  타이머 값이다(while (active && !paused)). 출근 전에는 영영 안 바뀐다.
            //  그래서 설정에서 권한을 켜고 돌아와도 카드가 "4가지가 꺼져 있어요" 그대로였다.
            //  시키는 대로 다 켰는데 앱이 여전히 아니라고 하니, 줄이려던 문의를 오히려 늘렸다.
            //
            //  권한은 설정 화면(=앱 밖)에서 바뀌므로 '돌아왔을 때' 다시 읽는 게 유일하게 맞는 시점이다.
            var permTick by remember { mutableStateOf(0) }
            val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                    if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permTick++
                }
                lifecycleOwner.lifecycle.addObserver(obs)
                onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
            }
            val accOk = remember(permTick) {
                (android.provider.Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services") ?: "")
                    .contains(context.packageName)
            }
            val overlayOk = remember(permTick) { try { android.provider.Settings.canDrawOverlays(context) } catch (e: Exception) { true } }
            val notifOk = remember(permTick) {
                (android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: "")
                    .contains(context.packageName)
            }
            val battOk = remember(permTick) {
                try { (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(context.packageName) }
                catch (e: Exception) { true }
            }
            val isOnestore = com.callradar.app.BuildConfig.FLAVOR == "onestore"
            val missing = buildList {
                if (isOnestore && !accOk) add("자동기록(접근성)")
                if (!overlayOk) add("운행 버튼 띄우기")
                if (!notifOk) add("금액 자동입력")
                if (!battOk) add("배터리 최적화 해제")
            }
            if (missing.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        .clickable { com.callradar.app.MainActivity.wizardReopen.value = true },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2A12)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("⚠️", fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("아직 ${missing.size}가지가 꺼져 있어요", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent)
                            Text(missing.joinToString(" · "), fontSize = 11.sp, color = Color(0xFFD6C4A8))
                            Text("눌러서 켜기 — 1분이면 끝나요", fontSize = 11.sp, color = muted)
                        }
                        Text("›", fontSize = 22.sp, color = accent)
                    }
                }
            }
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
                    // [근무 구간] 일시정지로 나뉜 구간을 그대로 보여준다 — "06:00~11:00 · 15:00~23:00"
                    //  (예전엔 한 덩어리로만 보여서, 중간에 몇 시간 쉬었는지 알 수 없었다)
                    val segTxt = remember(nowTick, paused) {
                        com.callradar.app.WorkSegments.ensureOpened(context)   // 자동출근으로 시작된 세션도 구간이 생기게
                        com.callradar.app.WorkSegments.format(context)
                    }
                    if (segTxt.isNotBlank() && segTxt.contains("·")) {
                        val restM = remember(nowTick, paused) { com.callradar.app.WorkSegments.restMin(context) }
                        Spacer(Modifier.height(4.dp))
                        Text(segTxt, fontSize = 11.sp, color = accent)
                        if (restM > 0) Text("휴식 ${restM / 60}시간 ${restM % 60}분 제외", fontSize = 10.sp, color = muted)
                    }
                    // [v93 자동 재개 알림] 앱이 일시정지를 대신 풀었으면 밝히고 되돌릴 길을 준다(classic 홈과 동일).
                    val autoResumeAt = remember(nowTick, paused) { com.callradar.app.WorkResume.pendingAt(context) }
                    if (autoResumeAt > 0L) {
                        val hhmm = remember(autoResumeAt) {
                            java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
                                .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul") }
                                .format(java.util.Date(autoResumeAt))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("$hhmm 자동 재개됨", fontSize = 11.sp, color = accent, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                if (com.callradar.app.WorkResume.undo(context)) {
                                    pausedTotal = prefs.getLong("work_paused_total", 0L)
                                    pauseStart = prefs.getLong("work_pause_start", 0L)
                                    nowTick = System.currentTimeMillis()
                                    stopMeter()
                                }
                            }) { Text("되돌리기", fontSize = 11.sp, color = accent, fontWeight = FontWeight.Bold) }
                            TextButton(onClick = {
                                com.callradar.app.WorkResume.clear(context); nowTick = System.currentTimeMillis()
                            }) { Text("확인", fontSize = 11.sp, color = muted) }
                        }
                    }
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

        // [귀로내비] 수급 지수 배지 — 오늘 콜 시장 온도(풍년/보통/가뭄). 기여 게이트(7일 5건) 미달이면 잠금 안내.
        supplyInfo?.let { s ->
            Spacer(Modifier.height(12.dp))
            val (emoji, col) = when (s.first) {
                "풍년" -> "🔥" to Color(0xFF10B981)
                "가뭄" -> "🥶" to Color(0xFFEF4444)
                "잠김" -> "🔒" to Color(0xFF6B7280)
                else -> "🌤" to Color(0xFFF59E0B)
            }
            Card(colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(emoji, fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(if (s.first == "잠김") "수급 지수" else "오늘 콜 시장: ${s.first}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (s.first == "잠김") muted else col)
                        Text(s.second, fontSize = 11.sp, color = muted)
                    }
                }
            }
        }

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
                                // [v94 접근성 공개·동의] 켤 때는 반드시 명시적 공개 화면을 먼저 거친다.
                                //  구글 정책: 접근성 도구가 아닌 앱은 '일반 사용 과정에서' 공개·동의를 보여야 한다.
                                //  동의 전에는 auto_record_on 을 켜지 않는다 — 동의 화면이 직접 켠다.
                                if (on && com.callradar.app.AutoRecordConsentActivity.needed(context)) {
                                    autoRec = false
                                    com.callradar.app.AutoRecordConsentActivity.start(context)
                                    return@Switch
                                }
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
