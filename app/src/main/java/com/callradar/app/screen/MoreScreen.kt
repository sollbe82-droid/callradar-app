// ===== MoreScreen v5 (2026-07-24) — 재구성: 아이콘형/목록형 토글 랜딩 =====
// v5: 상단 4탭(분석/랭킹/링크/설정) → 성격별 그룹 타일 랜딩 + ⊞아이콘/☰목록 토글(prefs "more_view_mode").
//     흩어진 정산 항목은 "정산 설정" 하위화면(SettlementSettings)으로 묶음. 기존 화면/다이얼로그 로직 보존.
//     분석/랭킹/링크/등록소/정산은 하위화면(뒤로가기 헤더)으로 재사용 — 컨테이너만 바뀜.
// v4: 권한상태 자동갱신 / v3: 자동화 권한 카드 / v2: 기사유형 설정 + 서버연동
package com.callradar.app.screen

import com.callradar.app.MainActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.Calendar
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val SETTINGS_SERVER = Config.SERVER_URL

// [A-Z] AI/레이더 GET 헬퍼 — 타임아웃(콜드스타트 무한대기 방지)+인증헤더(ENFORCE_TOKEN 대비). 기존 URL(x).readText() 대체.
private fun moreGet(url: String): String {
    val conn = (URL(url).openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 30000 }
    return conn.inputStream.bufferedReader().use { it.readText() }
}

// [v22] 다이얼로그 키보드 가림 완전수정: AlertDialog는 별도 윈도우라 imePadding()이 IME inset을 못 받아 무시됨.
// 다이얼로그 윈도우에 decorFitsSystemWindows=false를 걸면 imePadding()이 실작동 → 입력칸/저장버튼이 키보드 위로.
@Composable
private fun DialogImeFix() {
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(view) {
        var p: android.view.ViewParent? = view.parent
        var win: android.view.Window? = null
        while (p != null) {
            if (p is androidx.compose.ui.window.DialogWindowProvider) { win = p.window; break }
            p = p.parent
        }
        win?.let {
            it.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, false)
        }
    }
}
// 무료 버전 플래그: true면 자동화(접근성/알림 등) 권한 카드 숨김. 유료판 낼 때 false로.
private const val IS_FREE_VERSION = true
// [핵심만] true면 수입기록과 무관한 곁가지(예약·이벤트·노하우·AI비서·요금미터기)를 숨김.
//   핵심 3개(운행·매출 기록 / 급여·사납 계산 / 레이더)에 집중. 나중에 되살리려면 false.
private const val CORE_ONLY = true
// 오픈톡방 링크 (커뮤니티 방 gsyuVMCi로 통일 — 홈 배너와 동일. 옛 버그방 pqocJcDi 제거)
private const val OPEN_CHAT_URL = "https://open.kakao.com/o/gsyuVMCi"

// 하위 화면 라우트
private const val R_HOME = "home"
private const val R_STATS = "stats"
private const val R_RANKING = "ranking"
private const val R_LINKS = "links"
private const val R_REGISTRY = "registry"
private const val R_SETTLEMENT = "settlement"
private const val R_EVENTS = "events"
private const val R_BOOKINGS = "bookings"
private const val R_AI = "ai_assistant"
private const val R_MAP = "driver_map"
private const val R_KNOWHOW = "knowhow"

// 한 항목(타일/행 공통 데이터). onClick으로 동작.
private data class MoreEntry(
    val icon: String,
    val label: String,
    val desc: String,
    val right: String? = null,          // 목록형 우측 값
    val rightKind: Int = 0,             // 0 accent, 1 green, 2 red, 3 muted
    val chevron: Boolean = false,       // 우측 › (하위화면 이동형)
    val badge: String? = null,          // 아이콘형 상태 뱃지
    val badgeKind: Int = 0,             // 0 info(accent), 1 on(green), 2 off(red)
    val danger: Boolean = false,        // 로그아웃 등 빨강
    val onClick: () -> Unit
)
private data class MoreGroup(val title: String, val entries: List<MoreEntry>)

@Composable
fun MoreScreen(userId: String, onLogout: () -> Unit, onOpenDailySettlement: () -> Unit = {}, openSettleTick: Int = 0, openRoute: String = "") {
    val context = LocalContext.current
    var route by remember { mutableStateOf(R_HOME) }
    val card = AppTheme.card; val accent = Color(0xFFF59E0B); val muted = Color(0xFF6B7280)

    // [v19] 하위화면에서 폰 뒤로가기 → 앱 종료 대신 더보기 홈으로
    BackHandler(enabled = route != R_HOME) { route = R_HOME }
    // [v19] 홈 '기사 설정' 버튼에서 열면 정산 설정으로 바로 진입
    LaunchedEffect(openSettleTick) { if (openSettleTick > 0) route = if (openRoute.isNotBlank()) openRoute else R_SETTLEMENT }

    // [스크롤보존] 더보기 홈 스크롤 위치를 하위화면 왕복(route 변경) 사이에 유지.
    //  MoreScreen은 하위화면 진입해도 계속 컴포즈되므로 여기서 remember → MoreHome/Grid/List로 전달.
    val homeScroll = rememberScrollState()
    when (route) {
        R_HOME -> MoreHome(
            userId = userId, onLogout = onLogout, onOpenDailySettlement = onOpenDailySettlement,
            homeScroll = homeScroll,
            onNavigate = { route = it; com.callradar.app.Telemetry.log(context, "open_feature", it) }
        )
        R_STATS -> MoreSubScreen("분석", onBack = { route = R_HOME }) { StatsScreen(userId = userId) }
        R_RANKING -> MoreSubScreen("랭킹", onBack = { route = R_HOME }) { RankingScreen(userId = userId) }
        R_LINKS -> MoreSubScreen("유용한 링크", onBack = { route = R_HOME }) {
            LinksView(context = context, card = card, accent = accent, muted = muted)
        }
        R_REGISTRY -> MoreSubScreen("홈 편집", onBack = { route = R_HOME }) {
            val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                FeatureRegistry(prefs = prefs, accent = accent, muted = muted, card = card)
                Spacer(Modifier.height(10.dp))
                Text("여기서 켠 카드만 홈에 보입니다. 탭하면 바로 반영돼요.", fontSize = 11.sp, color = muted)
            }
        }
        R_SETTLEMENT -> MoreSubScreen("정산 설정", onBack = { route = R_HOME }) {
            SettlementSettings(userId = userId, context = context, card = card, accent = accent,
                green = AppTheme.green, muted = muted)
        }
        R_EVENTS -> MoreSubScreen("이벤트·수요 정보", onBack = { route = R_HOME }) {
            EventsView(context = context, accent = accent, muted = muted, card = card)
        }
        R_BOOKINGS -> MoreSubScreen("예약 요청 (단골)", onBack = { route = R_HOME }) {
            BookingsView(userId = userId, context = context, accent = accent, muted = muted, card = card)
        }
        R_AI -> MoreSubScreen("AI 운행 비서", onBack = { route = R_HOME }) {
            AiAssistantView(userId = userId, context = context, accent = accent, muted = muted, card = card)
        }
        R_MAP -> DriverMapScreen(userId = userId, onBack = { route = R_HOME })
        R_KNOWHOW -> KnowHowScreen(userId = userId, onBack = { route = R_HOME })
    }
}

// 뒤로가기 헤더가 있는 하위 화면 래퍼
@Composable
private fun MoreSubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val card = AppTheme.card; val accent = Color(0xFFF59E0B)
    Column(Modifier.fillMaxSize().background(AppTheme.bg)) {
        Row(
            Modifier.fillMaxWidth().background(card).padding(top = 44.dp, start = 8.dp, end = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("‹", fontSize = 24.sp, color = accent, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp)); Text("더보기", fontSize = 14.sp, color = accent)
            }
            Spacer(Modifier.width(4.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

// [v21] AI 운행 비서 — 정직판(준비 중). 시외·귀로콜 기록이 곧 비서를 키우는 데이터.
@Composable
private fun AiAssistantView(userId: String, context: Context, accent: Color, muted: Color, card: Color) {
    val scope = rememberCoroutineScope()
    var origin by remember { mutableStateOf("") }
    var dest by remember { mutableStateOf("") }
    var timeStr by remember { mutableStateOf("") }
    var outArea by remember { mutableStateOf(true) }   // 시외(영업 외)가 이 기능의 핵심 → 기본 on
    var memo by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var ocrStatus by remember { mutableStateOf("") }
    var rawOcr by remember { mutableStateOf("") }   // 원본 OCR(교정 전) — 학습 말뭉치용
    // [v21] 오픈톡방 스샷 → 한국어 OCR → 제보 자동 채움 (온디바이스). 초반 인식율 낮고 쓸수록 진화.
    fun runReportOcr(uri: Uri) {
        ocrStatus = "스샷을 읽는 중…"
        try {
            val image = InputImage.fromFilePath(context, uri)
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                .process(image)
                .addOnSuccessListener { vt ->
                    val text = vt.text.trim()
                    if (text.isEmpty()) { ocrStatus = "글자를 못 읽었어요. 더 선명한 스샷이면 좋아요."; return@addOnSuccessListener }
                    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    // 시간 자동 추출 (23:40 형태)
                    val tm = Regex("([01]?\\d|2[0-3]):[0-5]\\d").find(text)?.value
                    if (timeStr.isBlank() && tm != null) timeStr = tm
                    // "출발 → 목적" 형태 우선 인식
                    val arrowLine = lines.firstOrNull { it.contains("→") || it.contains("->") || it.contains("=>") || it.contains(" - ") }
                    val placeRe = Regex("[가-힣A-Za-z0-9]+(동|구|시|읍|면|리|역|공항|터미널|나들목|IC)")
                    if (arrowLine != null) {
                        val parts = arrowLine.split("→", "->", "=>", " - ").map { it.trim() }.filter { it.isNotEmpty() }
                        if (origin.isBlank() && parts.isNotEmpty()) origin = parts[0].take(30)
                        if (dest.isBlank() && parts.size >= 2) dest = parts[1].take(30)
                    } else {
                        // 지명처럼 보이는 줄만 채움(쓰레기 자동입력 방지). 없으면 비워두고 사용자가 메모 원문 보고 채움.
                        val places = lines.filter { placeRe.containsMatchIn(it) && it.length <= 20 }
                        if (origin.isBlank() && places.isNotEmpty()) origin = places[0].take(30)
                        if (dest.isBlank() && places.size >= 2) dest = places[1].take(30)
                    }
                    rawOcr = text.take(1000)   // 교정 전 원본(학습 말뭉치)
                    memo = text.take(500)   // 읽은 원문 전체(사용자 참고·교정용)
                    ocrStatus = "원문을 메모에 담았어요. 출발지·목적지를 확인해 채워주세요. (채팅 스샷은 초반 인식이 부정확할 수 있어요)"
                }
                .addOnFailureListener { e -> ocrStatus = "읽기 실패: ${e.message}" }
        } catch (e: Exception) { ocrStatus = "오류: ${e.message}" }
    }
    val reportPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> if (uri != null) runReportOcr(uri) }
    // [v21] 핫스팟 집계 로드 (내 기록+제보에서 자주 잡히는 출발지·평균 시간대)
    var hotspots by remember { mutableStateOf(listOf<Triple<String, Int, Int>>()) }
    var hsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) {
            try {
                val txt = moreGet("$SETTINGS_SERVER/api/report-hotspots")
                val arr = org.json.JSONArray(txt)
                (0 until arr.length()).map { val o = arr.getJSONObject(it); Triple(o.optString("origin"), o.optInt("cnt"), o.optInt("avg_hour")) }
            } catch (e: Exception) { emptyList() }
        }
        hotspots = list.take(8); hsLoaded = true
    }
    // [v21] 개인 운행 리듬 (본인 데이터 — Day-1 가치)
    var rhythmDay by remember { mutableStateOf("") }
    var rhythmHour by remember { mutableStateOf("") }
    var rhythmTotal by remember { mutableStateOf(-1) }
    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) {
            try {
                val o = org.json.JSONObject(moreGet("$SETTINGS_SERVER/api/rhythm/$userId"))
                val days = listOf("일", "월", "화", "수", "목", "금", "토")
                val dw = o.optJSONArray("by_dow"); val hr = o.optJSONArray("by_hour")
                val dt = if (dw != null && dw.length() > 0) { val d = dw.getJSONObject(0); days.getOrElse(d.optInt("dow")) { "?" } + "요일 (평균 " + String.format("%,d", d.optInt("avg_fare")) + "원)" } else ""
                val ht = if (hr != null && hr.length() > 0) { val h = hr.getJSONObject(0); h.optInt("hour").toString() + "시경 (평균 " + String.format("%,d", h.optInt("avg_fare")) + "원)" } else ""
                Triple(o.optInt("total_trips"), dt, ht)
            } catch (e: Exception) { Triple(0, "", "") }
        }
        rhythmTotal = r.first; rhythmDay = r.second; rhythmHour = r.third
    }
    // [v23] 내 노하우(씨앗) — 데이터 0이어도 개인 맞춤
    var knowhow by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) {
            try {
                val arr = org.json.JSONArray(moreGet("$SETTINGS_SERVER/api/knowhow/$userId"))
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val head = listOfNotNull(o.optString("area").ifBlank { null }, o.optString("time_band").ifBlank { null }, o.optString("pattern").ifBlank { null }).joinToString(" · ")
                    val nt = o.optString("note")
                    (head + (if (nt.isNotBlank()) " — $nt" else "")).trim()
                }.filter { it.isNotBlank() }
            } catch (e: Exception) { emptyList() }
        }
        knowhow = list
    }
    // [v21] 전체 기사 통합 — 지금 시간대 콜 잘 잡히는 곳 (2단계: 더 많은 데이터)
    var demandRows by remember { mutableStateOf(listOf<Triple<String, Int, Int>>()) }
    LaunchedEffect(Unit) {
        val hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val list = withContext(Dispatchers.IO) {
            try {
                val o = org.json.JSONObject(moreGet("$SETTINGS_SERVER/api/demand?hour=$hr"))
                val arr = o.optJSONArray("rows")
                (0 until (arr?.length() ?: 0)).map { val x = arr!!.getJSONObject(it); Triple(x.optString("origin"), x.optInt("cnt"), x.optInt("drivers")) }
            } catch (e: Exception) { emptyList() }
        }
        demandRows = list.take(6)
    }
    // [v2] 배드타임·배드존 역산 — 공차(다음 운행까지 시간)가 긴 시간대·지역. AI 비서 판단 근거.
    var badTimes by remember { mutableStateOf<List<Triple<Int, Int, Int>>>(emptyList()) }  // dow, hour, avgGap(분)
    var badZones by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }       // area, avgGap(분)
    LaunchedEffect(Unit) {
        val res = withContext(Dispatchers.IO) {
            try {
                val o = org.json.JSONObject(moreGet("$SETTINGS_SERVER/api/stats/deadzones/$userId"))
                val bt = o.optJSONArray("badTimes"); val bz = o.optJSONArray("badZones")
                val times = (0 until (bt?.length() ?: 0)).map { val x = bt!!.getJSONObject(it); Triple(x.optInt("dow"), x.optInt("hour"), x.optInt("avg_gap")) }
                val zones = (0 until (bz?.length() ?: 0)).map { val x = bz!!.getJSONObject(it); Pair(x.optString("area"), x.optInt("avg_gap")) }
                Pair(times, zones)
            } catch (e: Exception) { Pair(emptyList<Triple<Int, Int, Int>>(), emptyList<Pair<String, Int>>()) }
        }
        badTimes = res.first; badZones = res.second
    }
    // [v21] 임박 이벤트 → 브리핑 수요 신호 (기존 /api/events 재사용, 심사 무관)
    var eventLine by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val line = withContext(Dispatchers.IO) {
            try {
                val arr = org.json.JSONArray(moreGet("$SETTINGS_SERVER/api/events?days=2"))
                if (arr.length() > 0) {
                    val e = arr.getJSONObject(0)
                    val title = e.optString("title"); val area = e.optString("area")
                    val d = e.optString("start_at").take(10)
                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(java.util.Date())
                    val whenTxt = if (d == today) "오늘" else "곧"
                    if (title.isNotEmpty()) "$whenTxt ${if (area.isNotEmpty()) area + "에서 " else ""}$title 있어 그 근처 수요가 늘 수 있어요(예상)." else ""
                } else ""
            } catch (e: Exception) { "" }
        }
        eventLine = line
    }
    // [v21] 공항 입국 예고 피크 → 브리핑/음성 결합 (기존 /api/airport/passengers 재사용, 심사 무관)
    var airportPeak by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val p = withContext(Dispatchers.IO) {
            try {
                val arr = org.json.JSONArray(moreGet("$SETTINGS_SERVER/api/airport/passengers"))
                var bh = -1; var bn = -1
                for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); val tot = o.optInt("t1") + o.optInt("t2"); if (tot > bn) { bn = tot; bh = o.optInt("hour") } }
                if (bh >= 0 && bn > 0) "인천공항 입국은 ${bh}시경 약 ${String.format("%,d", bn)}명으로 가장 붐벼요" else ""
            } catch (e: Exception) { "" }
        }
        airportPeak = p
    }
    // [v21] 음성 안내(TTS) On/Off — 핸즈프리 브리핑
    val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    var voiceOn by remember { mutableStateOf(prefs.getBoolean("voice_on", false)) }
    // [v21] 브리핑 요점 선택 (불필요한 안내 제한 — 켠 것만 말함)
    var bRhythm by remember { mutableStateOf(prefs.getBoolean("brief_rhythm", true)) }
    var bDemand by remember { mutableStateOf(prefs.getBoolean("brief_demand", true)) }
    var bEvent by remember { mutableStateOf(prefs.getBoolean("brief_event", true)) }
    var bAirport by remember { mutableStateOf(prefs.getBoolean("brief_airport", true)) }
    var bBad by remember { mutableStateOf(prefs.getBoolean("brief_bad", true)) }   // [v2] 피해야 할 시간·지역 경고
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { st -> if (st == TextToSpeech.SUCCESS) engine?.language = Locale.KOREAN }
        tts = engine
        onDispose { engine?.stop(); engine?.shutdown() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 오늘의 브리핑 (음성 On/Off + 듣기) — 매일 듣는 습관 = 중독화 훅
        run {
            val today = listOf("일", "월", "화", "수", "목", "금", "토")[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
            val topPlace = demandRows.firstOrNull()?.first ?: ""
            // [v2] 지금 시간대(요일×시)가 공차 긴 배드타임인지 판단
            val nowDow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1
            val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val badNow = badTimes.firstOrNull { it.first == nowDow && it.second == nowHour }
            val worstZone = badZones.firstOrNull()
            val brief = buildString {
                append("오늘은 ${today}요일입니다. ")
                if (bRhythm && rhythmDay.startsWith(today)) append("평소 매출이 잘 나오는 요일이에요. ")
                if (bDemand && topPlace.isNotEmpty()) append("지금 시간대엔 ${topPlace} 쪽에서 콜이 자주 잡혔어요. ")
                if (bBad && badNow != null) append("다만 지금 시간대는 평소 다음 콜까지 약 ${badNow.third}분 비어요 — 공차가 길 수 있으니 수요 있는 쪽으로 미리 이동하세요. ")
                if (bBad && worstZone != null) append("${worstZone.first} 쪽에 내리면 다음 콜까지 평균 ${worstZone.second}분 걸려요, 참고하세요. ")
                if (bEvent && eventLine.isNotEmpty()) append(eventLine + " ")
                if (bAirport && airportPeak.isNotEmpty()) append(airportPeak + ". ")
                append("안전 운전하세요.")
            }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("🔊 오늘의 브리핑", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("음성", fontSize = 12.sp, color = muted); Spacer(Modifier.width(4.dp))
                            Switch(checked = voiceOn, onCheckedChange = { voiceOn = it; prefs.edit().putBoolean("voice_on", it).apply(); if (it) tts?.speak(brief, TextToSpeech.QUEUE_FLUSH, null, "brief") })
                        }
                    }
                    // 발동 모드 프리셋 (귀로안내/장거리 등 — 요점을 한 번에 세팅)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val modes = listOf(
                            "기본" to listOf(true, true, true, true),
                            "귀로" to listOf(false, true, false, false),
                            "장거리" to listOf(false, false, true, true),
                            "조용히" to listOf(false, false, false, false)
                        )
                        modes.forEach { (name, v) ->
                            AssistChip(onClick = { bRhythm = v[0]; bDemand = v[1]; bEvent = v[2]; bAirport = v[3]; prefs.edit().putBoolean("brief_rhythm", v[0]).putBoolean("brief_demand", v[1]).putBoolean("brief_event", v[2]).putBoolean("brief_airport", v[3]).apply() }, label = { Text(name, fontSize = 10.sp, color = accent) })
                        }
                    }
                    Text(brief, fontSize = 13.sp, color = AppTheme.text, lineHeight = 19.sp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = bRhythm, onClick = { bRhythm = !bRhythm; prefs.edit().putBoolean("brief_rhythm", bRhythm).apply() }, label = { Text("리듬", fontSize = 10.sp) })
                        FilterChip(selected = bDemand, onClick = { bDemand = !bDemand; prefs.edit().putBoolean("brief_demand", bDemand).apply() }, label = { Text("수요", fontSize = 10.sp) })
                        FilterChip(selected = bEvent, onClick = { bEvent = !bEvent; prefs.edit().putBoolean("brief_event", bEvent).apply() }, label = { Text("이벤트", fontSize = 10.sp) })
                        FilterChip(selected = bAirport, onClick = { bAirport = !bAirport; prefs.edit().putBoolean("brief_airport", bAirport).apply() }, label = { Text("공항", fontSize = 10.sp) })
                        FilterChip(selected = bBad, onClick = { bBad = !bBad; prefs.edit().putBoolean("brief_bad", bBad).apply() }, label = { Text("위험", fontSize = 10.sp) })
                    }
                    Text("듣고 싶은 것만 켜세요 · 끈 항목은 말하지 않아요", fontSize = 9.sp, color = muted)
                    Button(onClick = { tts?.speak(brief, TextToSpeech.QUEUE_FLUSH, null, "brief") }, modifier = Modifier.fillMaxWidth().height(44.dp), colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(10.dp)) {
                        Text("▶ 브리핑 듣기", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
        // [v2] 피해야 할 시간·지역 (공차 역산) — AI 판단 근거를 눈으로도
        if (badTimes.isNotEmpty() || badZones.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("⚠️ 피해야 할 시간·지역", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                    Text("내 기록에서 다음 콜까지 오래 걸린(공차 긴) 순", fontSize = 11.sp, color = muted)
                    if (badTimes.isNotEmpty()) {
                        val days = listOf("일", "월", "화", "수", "목", "금", "토")
                        Text("시간대", fontSize = 12.sp, color = muted, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        badTimes.take(4).forEach { (dw, hr, gap) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${days.getOrElse(dw) { "?" }}요일 ${hr}시", fontSize = 13.sp, color = AppTheme.text)
                                Text("평균 ${gap}분 공차", fontSize = 13.sp, color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (badZones.isNotEmpty()) {
                        Text("지역(내린 곳)", fontSize = 12.sp, color = muted, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        badZones.take(4).forEach { (area, gap) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(area.take(16), fontSize = 13.sp, color = AppTheme.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("평균 ${gap}분", fontSize = 13.sp, color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text("비서가 브리핑에서 지금 시간대·목적지를 이 기록과 비교해 짚어줘요.", fontSize = 10.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        // 정직한 준비-중 안내 (구라 없이: 지금은 개인 기록으로 남고, 데이터 쌓이면 분석이 켜짐)
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("🤖 AI 운행 비서", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                Text("준비 중 · 데이터를 모으는 중입니다", fontSize = 12.sp, color = accent, fontWeight = FontWeight.Bold)
                Text("시외(영업 외 지역)에 나갔을 때 언제·어디서 귀로콜이 잡혔는지 기록해 두세요. 기록이 쌓이면 '이 시간, 이 지역에서 서울행 콜이 잦다' 같은 분석을 비서가 대신 해드립니다. 지금 남기는 건 우선 내 개인 기록으로 그대로 남고, 데이터가 충분해지면 분석이 켜집니다.", fontSize = 12.sp, color = muted, lineHeight = 18.sp)
                Text("※ 아직 없는 분석을 있는 척하지 않습니다. 데이터가 먼저입니다.", fontSize = 11.sp, color = muted)
                HorizontalDivider(color = AppTheme.surface2)
                Text("🎁 지금은 베타라 모든 기능이 무료입니다. 정식 출시 후엔 정밀 위치·귀로콜 자동감지 같은 일부 고급 기능만 구독으로 전환될 예정이고, 기본 기능(기록·동단위 수요·개인 리듬)은 계속 무료입니다. 베타에 함께해 주신 기사님껜 감사 혜택을 드려요.", fontSize = 11.sp, color = muted, lineHeight = 16.sp)
            }
        }
        // [v23] 내 노하우(씨앗) — 내가 적은 것을 비서가 그대로 짚어줌(데이터 0이어도)
        if (knowhow.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("📝 내 노하우", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                    knowhow.take(6).forEach { Text("• $it", fontSize = 13.sp, color = muted) }
                    Text("더보기 → 내 노하우 에서 추가·수정", fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        // 내 운행 리듬 (본인 데이터, 표본 5건 이상일 때만 — 정직)
        if (rhythmTotal >= 5 && (rhythmDay.isNotEmpty() || rhythmHour.isNotEmpty())) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("📈 내 운행 리듬 (내 기록 ${rhythmTotal}건 기준)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                    if (rhythmDay.isNotEmpty()) Text("• 매출 높은 요일: $rhythmDay", fontSize = 13.sp, color = muted)
                    if (rhythmHour.isNotEmpty()) Text("• 매출 높은 시간대: $rhythmHour", fontSize = 13.sp, color = muted)
                }
            }
        }
        // 자주 잡히는 포인트 (실제 집계 — 데이터 적으면 참고용으로 정직 표기)
        if (hsLoaded && hotspots.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("📍 자주 잡히는 포인트 (내 기록+제보 기준)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                    hotspots.forEach { (o, c, h) ->
                        Text("• $o · ${c}회" + (if (h in 0..23) " · 평균 ${h}시경" else ""), fontSize = 13.sp, color = muted)
                    }
                    Text("데이터가 적을수록 참고용입니다. 기록이 쌓일수록 정확해집니다.", fontSize = 11.sp, color = muted)
                }
            }
        }
        // 전체 기사 통합 — 지금 이 시간대 콜 잘 잡히는 곳 (비식별 집계, 기사 수 표기로 정직)
        if (demandRows.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("⏰ 지금 시간대 콜 잘 잡히는 곳 (전체 기사)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent)
                    demandRows.forEach { (o, c, d) ->
                        Text("• $o · ${c}회 · 기사 ${d}명", fontSize = 13.sp, color = muted)
                    }
                    Text("전체 기사 데이터가 쌓일수록 시간·지역이 더 촘촘해집니다.", fontSize = 11.sp, color = muted)
                }
            }
        }
        // 귀로콜 레이더 — 📍현재 위치(포그라운드 GPS→역지오코딩) 또는 지역 검색으로 그 지역 수요 조회.
        run {
            var radarArea by remember { mutableStateOf("") }
            var radarRows by remember { mutableStateOf(listOf<Triple<String, Int, Int>>()) }
            var radarBusy by remember { mutableStateOf(false) }
            var radarDone by remember { mutableStateOf(false) }
            var geoStatus by remember { mutableStateOf("") }
            val runSearch: (String) -> Unit = { area ->
                if (area.isNotBlank()) {
                    radarBusy = true; radarDone = false
                    scope.launch {
                        val list = withContext(Dispatchers.IO) {
                            try {
                                val o = org.json.JSONObject(moreGet("$SETTINGS_SERVER/api/demand?area=" + java.net.URLEncoder.encode(area, "UTF-8")))
                                val arr = o.optJSONArray("rows")
                                (0 until (arr?.length() ?: 0)).map { val x = arr!!.getJSONObject(it); Triple(x.optString("origin"), x.optInt("cnt"), x.optInt("avg_fare")) }
                            } catch (e: Exception) { emptyList() }
                        }
                        radarRows = list.take(8); radarBusy = false; radarDone = true
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🧭 귀로콜 레이더", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent)
                    Text("시외에 나갔을 때 '📍현재 위치로'를 누르거나 지역(동/구)을 넣어보세요. 그 지역에서 콜이 자주 잡힌 곳·평균 요금을 알려줍니다.", fontSize = 11.sp, color = muted, lineHeight = 16.sp)
                    OutlinedTextField(value = radarArea, onValueChange = { radarArea = it }, label = { Text("지역 (예: 강남, 송도, 수원)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                geoStatus = "현재 위치 확인 중…"
                                val fine = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                val coarse = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (!fine && !coarse) { geoStatus = "위치 권한이 필요해요 (운행 버튼 켤 때 허용됩니다)" }
                                else {
                                    try {
                                        com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context).lastLocation
                                            .addOnSuccessListener { loc ->
                                                if (loc == null) geoStatus = "위치를 못 잡았어요. 잠시 후 다시."
                                                else scope.launch {
                                                    val area = withContext(Dispatchers.IO) {
                                                        try {
                                                            val g = org.json.JSONObject(moreGet("$SETTINGS_SERVER/api/geocode/reverse?x=${loc.longitude}&y=${loc.latitude}"))
                                                            val region = g.optString("region")
                                                            region.split(" ").firstOrNull { it.endsWith("구") || it.endsWith("시") || it.endsWith("군") } ?: region.split(" ").lastOrNull() ?: ""
                                                        } catch (e: Exception) { "" }
                                                    }
                                                    if (area.isNotBlank()) { radarArea = area; geoStatus = "현재 위치: $area"; runSearch(area) } else geoStatus = "주소 변환 실패"
                                                }
                                            }
                                            .addOnFailureListener { geoStatus = "위치 오류: ${it.message}" }
                                    } catch (e: SecurityException) { geoStatus = "위치 권한이 필요해요" }
                                }
                            },
                            modifier = Modifier.weight(1f).height(44.dp), colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(10.dp)
                        ) { Text("📍 현재 위치로", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        Button(
                            onClick = { runSearch(radarArea) }, enabled = !radarBusy,
                            modifier = Modifier.weight(1f).height(44.dp), colors = ButtonDefaults.buttonColors(containerColor = card), shape = RoundedCornerShape(10.dp)
                        ) { Text(if (radarBusy) "찾는 중…" else "지역 검색", color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    }
                    if (geoStatus.isNotEmpty()) Text(geoStatus, fontSize = 11.sp, color = accent)
                    radarRows.forEach { (o, c, f) -> Text("• $o · ${c}회 · 평균 " + String.format("%,d", f) + "원", fontSize = 13.sp, color = muted) }
                    if (radarDone && radarRows.isEmpty()) Text("아직 이 지역 데이터가 적어요. 기록이 쌓이면 보여드릴게요.", fontSize = 12.sp, color = muted)
                }
            }
        }
        // 시외·귀로콜 기록 입력
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("📡 시외·귀로콜 기록", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                Text("오픈톡방 등에서 캡처한 스샷을 올리면 자동으로 읽어옵니다. 손으로 적는 건 쉴 때만 하세요.\n※ 초반엔 인식율이 낮을 수 있어요. 쓸수록 데이터가 쌓여 점점 정확해집니다.", fontSize = 11.sp, color = muted, lineHeight = 16.sp)
                Button(onClick = { reportPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth().height(46.dp), colors = ButtonDefaults.buttonColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
                    Text("📷 스샷으로 제보 (자동 인식)", color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                if (ocrStatus.isNotEmpty()) Text(ocrStatus, fontSize = 11.sp, color = accent)
                OutlinedTextField(value = origin, onValueChange = { origin = it }, label = { Text("콜 잡힌 위치 (출발지)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = dest, onValueChange = { dest = it }, label = { Text("목적지") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = timeStr, onValueChange = { timeStr = it }, label = { Text("시간 (예: 23:40, 선택)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = outArea, onCheckedChange = { outArea = it })
                    Text("시외(영업 외 지역) 콜", fontSize = 13.sp, color = AppTheme.text)
                }
                OutlinedTextField(value = memo, onValueChange = { memo = it }, label = { Text("메모 (선택)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        if (origin.isBlank()) { msg = "출발지를 입력하세요"; return@Button }
                        busy = true; msg = ""
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                try {
                                    val note = buildString {
                                        if (timeStr.isNotBlank()) append(timeStr).append(" ")
                                        if (outArea) append("[시외] ")
                                        append(memo)
                                    }.trim()
                                    val json = JSONObject().apply {
                                        put("user_id", userId); put("platform", "콜제보")
                                        put("originName", origin); put("destName", dest.ifBlank { "미정" } + (if (note.isNotEmpty()) " · $note" else ""))
                                        put("fare", 0); put("payment_type", "report"); put("source", "report"); put("is_report", true)
                                        if (rawOcr.isNotEmpty()) put("raw_ocr", rawOcr)   // (원본→교정) 학습 말뭉치
                                    }
                                    val conn = (URL("$SETTINGS_SERVER/api/trips/manual").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                                        requestMethod = "POST"; doOutput = true; connectTimeout = 12000; readTimeout = 12000
                                        setRequestProperty("Content-Type", "application/json")
                                    }
                                    conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.flush() }
                                    val code = conn.responseCode; conn.disconnect(); code in 200..299
                                } catch (e: Exception) { false }
                            }
                            busy = false
                            if (ok) { msg = "기록 완료 · 고맙습니다 🙏"; origin = ""; dest = ""; timeStr = ""; memo = ""; outArea = true; rawOcr = ""; ocrStatus = "" }
                            else msg = "저장 실패 — 네트워크를 확인하세요"
                        }
                    },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(12.dp)
                ) { Text(if (busy) "저장 중…" else "기록 남기기", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = accent)
            }
        }
    }
}

@Composable
private fun MoreHome(userId: String, onLogout: () -> Unit, onOpenDailySettlement: () -> Unit, homeScroll: ScrollState, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    val card = AppTheme.card; val accent = Color(0xFFF59E0B)
    val green = Color(0xFF10B981); val red = Color(0xFFEF4444); val muted = Color(0xFF6B7280)
    val scope = rememberCoroutineScope()

    var viewMode by remember { mutableStateOf(prefs.getString("more_view_mode", "icon") ?: "icon") }

    // ----- 랜딩에서 여는 다이얼로그 상태 -----
    var floatingOn by remember { mutableStateOf(prefs.getBoolean("floating_on", false)) }
    var floatingPulse by remember { mutableStateOf(prefs.getBoolean("floating_pulse", true)) }   // [v2] 운행중 버튼 펄스
    var telemetryOn by remember { mutableStateOf(prefs.getBoolean("telemetry_on", true)) }
    var isDark by remember { mutableStateOf(AppTheme.isDark) }
    var nickname by remember { mutableStateOf(prefs.getString("nickname", "") ?: "") }
    var showNameDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var showDayStartDlg by remember { mutableStateOf(false) }
    var dayStartHour by remember { mutableStateOf(prefs.getInt("day_start_hour", 0)) }
    var shareRoom by remember { mutableStateOf(prefs.getString("share_room_url", "") ?: "") }
    var sharePromo by remember { mutableStateOf(prefs.getString("share_promo", "") ?: "") }
    var showShareCfg by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showPairCode by remember { mutableStateOf(false) }
    var pairCodeGen by remember { mutableStateOf("") }
    var pairGenLoading by remember { mutableStateOf(false) }
    var showMergeDlg by remember { mutableStateOf(false) }
    var mergeCode by remember { mutableStateOf("") }
    var mergeMsg by remember { mutableStateOf("") }
    var mergeBusy by remember { mutableStateOf(false) }
    var showPlatDlg by remember { mutableStateOf(false) }
    val platSel = remember { mutableStateListOf<String>() }
    val driverTypeLabel = run {
        val dt = prefs.getString("driver_type", "personal") ?: "personal"
        val aff = when (prefs.getString("affiliation", "none")) { "kakao" -> "카카오"; "uber" -> "우버"; else -> "비가맹" }
        "${if (dt == "corporate") "법인" else "개인"}·$aff"
    }

    // ----- 그룹/항목 구성 -----
    val groups = listOf(
        MoreGroup("오늘 할 일", listOfNotNull(
            MoreEntry("📋", "일일 마감", "전표·연료비로 매출 정리·회사 제출", chevron = true) { onOpenDailySettlement() },
            MoreEntry("🚕", "운행 버튼", "화면 위 플로팅 버튼으로 GPS 기록",
                right = if (floatingOn) "켜짐" else "꺼짐", rightKind = if (floatingOn) 1 else 2,
                badge = if (floatingOn) "켜짐" else "꺼짐", badgeKind = if (floatingOn) 1 else 2) {
                val act = context as? MainActivity
                if (floatingOn) { act?.stopFloatingButton(); floatingOn = false }
                else { act?.startFloatingButton(); floatingOn = prefs.getBoolean("floating_on", false) }
            },
            MoreEntry("✨", "운행중 버튼 깜빡임", "운행중일 때 버튼이 은은하게 호흡",
                right = if (floatingPulse) "켜짐" else "꺼짐", rightKind = if (floatingPulse) 1 else 2,
                badge = if (floatingPulse) "켜짐" else "꺼짐", badgeKind = if (floatingPulse) 1 else 2) {
                floatingPulse = !floatingPulse; prefs.edit().putBoolean("floating_pulse", floatingPulse).apply()
            },
            if (!CORE_ONLY) MoreEntry("🚕", "요금 미터기", "GPS 추정 요금(재미로) · 배터리 소모 큼", right = "추정") {
                try { com.callradar.app.MeterActivity.start(context) } catch (e: Exception) {}
            } else null
        )),
        MoreGroup("화면 · 기능", listOf(
            MoreEntry("🏠", "홈 편집", "홈에 표시할 카드·바로가기 고르기", chevron = true) { onNavigate(R_REGISTRY) },
            // [UX] 버튼 이름 = '지금 전환되는 곳'. 간편모드일 땐 '홈모드로', 홈모드일 땐 '간편모드로' — 돌아가기 헷갈림 해소.
            (prefs.getString("home_mode", "classic") ?: "classic").let { m ->
                if (m == "simple")
                    MoreEntry("🏠", "홈모드로 돌아가기", "지금 간편모드(카카오식 무탭) 사용 중 · 탭하면 원래 홈모드로", right = "간편", rightKind = 1) {
                        prefs.edit().putString("home_mode", "classic").apply()
                        (context as? android.app.Activity)?.recreate()
                    }
                else
                    MoreEntry("✨", "간편모드로 전환", "카카오식 무탭 홈 · 탭하면 전환, 언제든 '홈모드로 돌아가기'로 복귀", right = "기본", rightKind = 0) {
                        prefs.edit().putString("home_mode", "simple").apply()
                        (context as? android.app.Activity)?.recreate()
                    }
            },
            MoreEntry("🎨", "화면 테마", "밝게/어둡게 전환 (앱 전체)",
                right = if (isDark) "🌙 다크" else "☀️ 라이트", rightKind = 0,
                badge = if (isDark) "다크" else "라이트", badgeKind = 0) {
                AppTheme.isDark = !AppTheme.isDark; isDark = AppTheme.isDark
                prefs.edit().putBoolean("dark_mode", AppTheme.isDark).apply()
            },
            MoreEntry("🕐", "영업일 시작", "하루의 시작 시각 (야간 기사용)",
                right = if (dayStartHour == 0) "자정" else "${dayStartHour}시", rightKind = if (dayStartHour == 0) 3 else 0) {
                dayStartHour = prefs.getInt("day_start_hour", 0); showDayStartDlg = true
            }
        )),
        MoreGroup("기록 · 통계", listOf(
            MoreEntry("📊", "분석", "수입 추세·요일별·시간대 통계", chevron = true) { onNavigate(R_STATS) },
            MoreEntry("🏆", "랭킹", "기사 랭킹·내 순위", chevron = true) { onNavigate(R_RANKING) },
            MoreEntry("📥", "내보내기", "이번 달 운행 엑셀로 저장", right = "엑셀") {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$SETTINGS_SERVER/api/export/$userId"))) } catch (e: Exception) {}
            },
            MoreEntry("📷", "과거기록", "다른 앱 장부를 사진으로 불러오기", right = "사진") {
                try { com.callradar.app.ImageImportActivity.start(context) } catch (e: Exception) {}
            },
            MoreEntry("🗺️", "운행 궤적", "오늘 실차·공차 경로 + 이미지 공유", right = "PNG") {
                try { com.callradar.app.TrackActivity.start(context) } catch (e: Exception) {}
            }
        )),
        MoreGroup("정산 · 설정", listOf(
            // [v19] '기사 유형'은 정산 설정 안에 있으므로 중복 항목 제거 → 하나로 통합. 현재 유형은 오른쪽에 표시.
            MoreEntry("⚙️", "기사 설정", "유형·사납금·가스·수수료·연차", right = driverTypeLabel, rightKind = 3) { onNavigate(R_SETTLEMENT) },
            MoreEntry("📄", "급여명세서 스캔", "명세서 촬영 → 전 항목 인식 + 실수령 역산", right = "역산") {
                try { com.callradar.app.PayslipScanActivity.start(context) } catch (e: Exception) {}
            },
            MoreEntry("🧾", "매출 영수증 정산", "미터기 당일상세거래내역 촬영 → 빠진 금액 자동 채움·교정", right = "자동") {
                try { com.callradar.app.ReceiptReconcileActivity.start(context) } catch (e: Exception) {}
            },
            MoreEntry("🏢", "회사 프로필", "회사×근무형태별 사납·가스·초과율 → 예상급여", right = "예상급여") {
                try { com.callradar.app.CompanyProfileActivity.start(context) } catch (e: Exception) {}
            },
            MoreEntry("🧾", "세무 리포트", "개인택시 종소세·부가세 연간 추정 (경비율 vs 장부)", right = "추정") {
                try { com.callradar.app.TaxReportActivity.start(context) } catch (e: Exception) {}
            },
            MoreEntry("🙋", "내 이름", "홈·랭킹에 보이는 이름", right = nickname.ifEmpty { "기사님" }, rightKind = 3) {
                nameInput = nickname; showNameDialog = true
            }
        )),
        MoreGroup("계정 · 연결 (2·3폰)", listOf(
            MoreEntry("🆔", "내 계정 ID", "테스트 권한 등록 시 이 번호를 대표에게 알려주세요 (탭하면 복사)", right = userId.ifBlank { "-" }) {
                try {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("user_id", userId))
                    android.widget.Toast.makeText(context, "내 계정 ID 복사됨: $userId", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {}
            },
            MoreEntry("📱", "다른 폰 연결", "주폰에서 코드 생성 → 서브폰 입력", right = "코드 생성") {
                if (!pairGenLoading) {
                    pairGenLoading = true; pairCodeGen = ""; showPairCode = true
                    scope.launch {
                        try {
                            val resp = withContext(Dispatchers.IO) {
                                val json = JSONObject().apply { put("user_id", userId) }
                                val conn = (URL("$SETTINGS_SERVER/api/pair/create").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 15000 }
                                conn.outputStream.write(json.toString().toByteArray())
                                conn.inputStream.bufferedReader().readText()
                            }
                            pairCodeGen = JSONObject(resp).optString("code", "")
                        } catch (e: Exception) { pairCodeGen = "" }
                        pairGenLoading = false
                    }
                }
            },
            MoreEntry("🔗", "계정 합치기", "쪼개진 계정을 주계정에 통합", right = "합치기") {
                mergeCode = ""; mergeMsg = ""; showMergeDlg = true
            },
            MoreEntry("📲", "이 폰 플랫폼", "이 폰에서 쓰는 콜앱 (여러 개)", right = "설정") {
                platSel.clear(); (prefs.getString("my_platforms", "") ?: "").split(",").filter { it.isNotBlank() }.forEach { platSel.add(it) }; showPlatDlg = true
            },
            MoreEntry("↗️", "공유 설정", "오픈방 1터치 등록 (브랜드 노출)",
                right = if (shareRoom.isNotBlank()) "등록됨" else "미설정", rightKind = if (shareRoom.isNotBlank()) 1 else 3) {
                showShareCfg = true
            }
        )),
        MoreGroup("정보", listOfNotNull(
            MoreEntry("📖", "설치 도움말 (자동설정 다시 보기)", "운행버튼·자동기록·금액입력 설정을 그림으로 다시 안내", chevron = true) {
                com.callradar.app.MainActivity.wizardReopen.value = true
            },
            if (!CORE_ONLY) MoreEntry("🤖", "AI 운행 비서", "시외·귀로콜 기록 → 데이터 쌓이면 수요 분석", right = "준비 중", rightKind = 0, chevron = true) { onNavigate(R_AI) } else null,
            if (!CORE_ONLY) MoreEntry("📝", "내 노하우", "🔒 폰에만 저장되는 영업수첩 · 공유는 선택", right = "비공개", chevron = true) { try { com.callradar.app.KnowhowActivity.start(context) } catch (e: Exception) {} } else null,
            MoreEntry("🗺️", "내 운행 지도", "내 출발지 밀도를 지도로 (카카오맵)", right = "지도", chevron = true) { onNavigate(R_MAP) },
            MoreEntry("📈", "사용성 개선 참여 (익명)", "익명 통계로 앱을 함께 개선 · 개인정보 없음", right = if (telemetryOn) "참여중" else "끔", rightKind = if (telemetryOn) 1 else 2) {
                telemetryOn = !telemetryOn; prefs.edit().putBoolean("telemetry_on", telemetryOn).apply()
                android.widget.Toast.makeText(context, if (telemetryOn) "익명 사용성 통계 참여 켜짐 (개인정보 없음)" else "사용성 통계 참여 꺼짐", android.widget.Toast.LENGTH_SHORT).show()
            },
            if (!CORE_ONLY) MoreEntry("🚕", "예약 요청 (단골)", "명함 QR로 받은 예약 확인·수락", chevron = true) { onNavigate(R_BOOKINGS) } else null,
            if (!CORE_ONLY) MoreEntry("📅", "이벤트·수요 정보", "내 지역 축제·공연·수요 (온·오프)", chevron = true) { onNavigate(R_EVENTS) } else null,
            MoreEntry("🌐", "유용한 링크", "공항·항공편·기상 사이트 모음", chevron = true) { onNavigate(R_LINKS) },
            MoreEntry("💬", "오픈톡방", "아이디어·개선·버그 제보 환영", chevron = true) {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OPEN_CHAT_URL))) } catch (e: Exception) {}
            },
            MoreEntry("🚪", "로그아웃", "", danger = true) { showLogoutConfirm = true }
        ))
    )

    Column(Modifier.fillMaxSize().background(AppTheme.bg)) {
        // 제목 + 토글
        Column(Modifier.fillMaxWidth().background(card).padding(top = 44.dp, start = 16.dp, end = 12.dp, bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("콜레이더", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent)
                    Text("더보기", fontSize = 13.sp, color = muted)
                }
                Row(Modifier.background(AppTheme.surface2, RoundedCornerShape(10.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("icon" to "⊞ 아이콘", "list" to "☰ 목록").forEach { (m, lbl) ->
                        val on = viewMode == m
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) accent else Color.Transparent)
                            .clickable { viewMode = m; prefs.edit().putString("more_view_mode", m).apply() }
                            .padding(horizontal = 11.dp, vertical = 6.dp)) {
                            Text(lbl, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (on) Color(0xFF111111) else muted)
                        }
                    }
                }
            }
        }

        if (viewMode == "icon") MoreGrid(groups, accent, muted, card, homeScroll) else MoreList(groups, accent, green, red, muted, card, homeScroll)
    }

    // ================= 다이얼로그들 =================
    if (showNameDialog) {
        AlertDialog(onDismissRequest = { showNameDialog = false },
            title = { Text("앱에서 쓸 이름 변경", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column {
                Text("홈·랭킹 등에 표시되는 이름이에요", fontSize = 12.sp, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(value = nameInput, onValueChange = { nameInput = it.take(12) }, label = { Text("이름 (최대 12자)", color = muted) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
            } },
            confirmButton = { Button(onClick = {
                val nn = nameInput.trim()
                if (nn.isNotEmpty()) {
                    nickname = nn; prefs.edit().putString("nickname", nn).apply()
                    scope.launch { try { withContext(Dispatchers.IO) { val json = JSONObject().apply { put("nickname", nn) }; val conn = (URL("$SETTINGS_SERVER/api/users/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json"); doOutput = true }; conn.outputStream.write(json.toString().toByteArray(Charsets.UTF_8)); conn.responseCode } } catch (e: Exception) { } }
                }
                showNameDialog = false
            }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showNameDialog = false }) { Text("취소") } }, containerColor = AppTheme.card)
    }

    // [영업일 단일화] 기본홈·간편메뉴와 동일한 공용 다이얼로그 사용 (서버 동기화 포함)
    if (showDayStartDlg) DayStartDialog(onDismiss = { showDayStartDlg = false }, onSaved = { dayStartHour = it })

    if (showShareCfg) {
        var roomInput by remember { mutableStateOf(shareRoom) }
        var promoInput by remember { mutableStateOf(sharePromo) }
        AlertDialog(onDismissRequest = { showShareCfg = false },
            title = { Text("공유 설정", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text("공유를 누르면 문구가 자동복사되고 이 오픈방이 바로 열려요 (방에서 꾹→붙여넣기)", fontSize = 12.sp, color = muted)
                OutlinedTextField(value = roomInput, onValueChange = { roomInput = it }, label = { Text("오픈방 주소 (open.kakao.com/...)", color = muted) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                OutlinedTextField(value = promoInput, onValueChange = { promoInput = it.take(60) }, label = { Text("홍보 문구/링크 (선택)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
            } },
            confirmButton = { Button(onClick = { shareRoom = roomInput.trim(); sharePromo = promoInput.trim(); prefs.edit().putString("share_room_url", shareRoom).putString("share_promo", sharePromo).apply(); showShareCfg = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showShareCfg = false }) { Text("취소") } }, containerColor = AppTheme.card)
    }

    if (showPairCode) {
        AlertDialog(onDismissRequest = { showPairCode = false },
            title = { Text("다른 폰 연결 코드", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column {
                if (pairGenLoading) Text("코드 생성 중…", color = muted)
                else if (pairCodeGen.isNotEmpty()) {
                    Text(pairCodeGen, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = accent)
                    Spacer(Modifier.height(8.dp))
                    Text("서브폰 로그인 화면에서 '다른 폰 계정 연결'을 눌러 이 코드를 입력하세요. (5분 유효)", fontSize = 12.sp, color = muted)
                } else Text("코드 생성 실패 — 네트워크를 확인하세요", color = red)
            } },
            confirmButton = { Button(onClick = { showPairCode = false }) { Text("닫기") } },
            containerColor = AppTheme.card)
    }

    if (showMergeDlg) {
        AlertDialog(onDismissRequest = { if (!mergeBusy) showMergeDlg = false },
            title = { Text("계정 합치기", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column {
                Text("주폰 '다른 폰 연결'에서 뜬 6자리 코드를 넣으면, 이 폰의 기록이 그 계정으로 합쳐져요.", fontSize = 12.sp, color = muted)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = mergeCode, onValueChange = { v -> mergeCode = v.filter { it.isDigit() }.take(6) }, singleLine = true, label = { Text("6자리 코드") })
                if (mergeMsg.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(mergeMsg, fontSize = 12.sp, color = red) }
            } },
            confirmButton = { Button(onClick = {
                if (mergeCode.length != 6) { mergeMsg = "6자리 코드를 입력하세요"; return@Button }
                mergeBusy = true; mergeMsg = "합치는 중…"
                scope.launch {
                    try {
                        val androidId = try { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "" } catch (e: Exception) { "" }
                        val deviceId = if (androidId.isNotEmpty()) "guest_$androidId" else "guest_${System.currentTimeMillis()}"
                        val resp = withContext(Dispatchers.IO) {
                            val json = JSONObject().apply { put("code", mergeCode); put("secondary_user_id", userId); put("device_id", deviceId) }
                            val conn = (URL("$SETTINGS_SERVER/api/pair/merge").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 15000 }
                            conn.outputStream.write(json.toString().toByteArray())
                            val rc = conn.responseCode
                            val body = (if (rc in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                            Pair(rc, body)
                        }
                        val j = try { JSONObject(resp.second) } catch (e: Exception) { JSONObject() }
                        if (resp.first in 200..299 && j.optString("primary_user_id", "").isNotEmpty()) {
                            prefs.edit().putString("user_id", j.optString("primary_user_id", "")).putString("nickname", j.optString("nickname", "기사님")).apply()
                            com.callradar.app.Auth.clear(prefs)  // [보안 v24] 계정 통합 → 토큰 초기화(재생성 시 자가치유로 재발급)
                            mergeBusy = false; showMergeDlg = false
                            (context as? android.app.Activity)?.recreate()
                        } else { mergeBusy = false; mergeMsg = j.optString("error", "합치기 실패 — 코드를 확인하세요") }
                    } catch (e: Exception) { mergeBusy = false; mergeMsg = "합치기 실패 — 네트워크 확인" }
                }
            }) { Text(if (mergeBusy) "합치는 중" else "합치기") } },
            dismissButton = { OutlinedButton(onClick = { if (!mergeBusy) showMergeDlg = false }) { Text("취소") } },
            containerColor = AppTheme.card)
    }

    if (showPlatDlg) {
        AlertDialog(onDismissRequest = { showPlatDlg = false },
            title = { Text("이 폰 플랫폼", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column {
                Text("이 폰에서 쓰는 플랫폼을 골라요. 1개(전용폰)든 여러 개든 OK.", fontSize = 12.sp, color = muted)
                listOf("카카오T", "우버", "티머니고", "길빵/예약").forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { if (platSel.contains(p)) platSel.remove(p) else platSel.add(p) }.padding(vertical = 4.dp)) {
                        Checkbox(checked = platSel.contains(p), onCheckedChange = { c -> if (c) { if (!platSel.contains(p)) platSel.add(p) } else platSel.remove(p) })
                        Text(p, color = AppTheme.text)
                    }
                }
            } },
            confirmButton = { Button(onClick = {
                scope.launch {
                    try {
                        val androidId = try { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "" } catch (e: Exception) { "" }
                        val deviceId = if (androidId.isNotEmpty()) "guest_$androidId" else "guest_${System.currentTimeMillis()}"
                        withContext(Dispatchers.IO) {
                            val json = JSONObject().apply { put("user_id", userId); put("device_id", deviceId); put("platforms", org.json.JSONArray(platSel.toList())); put("label", "내 폰") }
                            val conn = (URL("$SETTINGS_SERVER/api/devices/register").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 15000 }
                            conn.outputStream.write(json.toString().toByteArray()); conn.responseCode
                        }
                        prefs.edit().putString("my_platforms", platSel.joinToString(",")).apply()
                    } catch (e: Exception) {}
                    showPlatDlg = false
                }
            }) { Text("저장") } },
            dismissButton = { OutlinedButton(onClick = { showPlatDlg = false }) { Text("취소") } },
            containerColor = AppTheme.card)
    }

    if (showLogoutConfirm) {
        AlertDialog(onDismissRequest = { showLogoutConfirm = false },
            title = { Text("로그아웃", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Text("로그아웃하면 로그인 화면으로 돌아가요.\n다른 계정으로 로그인할 수 있어요.\n(설정·기록은 그대로 보관됩니다)", color = Color(0xFF9CA3AF)) },
            confirmButton = { Button(onClick = { showLogoutConfirm = false; onLogout() }, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("로그아웃", color = AppTheme.text) } },
            dismissButton = { OutlinedButton(onClick = { showLogoutConfirm = false }) { Text("취소") } }, containerColor = AppTheme.card)
    }
}

// ===== 아이콘형(격자) =====
@Composable
private fun MoreGrid(groups: List<MoreGroup>, accent: Color, muted: Color, card: Color, scroll: ScrollState) {
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 6.dp)) {
        groups.forEach { g ->
            Text(g.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 8.dp))
            g.entries.chunked(4).forEach { rowItems ->
                Row(Modifier.fillMaxWidth().padding(bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    rowItems.forEach { e ->
                        val bg = if (e.danger) Color(0xFF2A1414) else card
                        val br = if (e.danger) Color(0xFF5B2626) else Color(0xFF1E2942)
                        Box(Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(15.dp)).background(bg).border(1.dp, br, RoundedCornerShape(15.dp)).clickable { e.onClick() }.padding(horizontal = 4.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text(e.icon, fontSize = 24.sp)
                                Spacer(Modifier.height(5.dp))
                                // [v19] 두 줄 라벨 짤림 방지: maxLines=2 + 촘촘한 줄간격
                                Text(e.label, fontSize = 10.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, color = if (e.danger) Color(0xFFEF4444) else AppTheme.text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                if (e.badge != null) {
                                    Spacer(Modifier.height(4.dp))
                                    val bc = when (e.badgeKind) { 1 -> Color(0xFF10B981); 2 -> Color(0xFFEF4444); else -> accent }
                                    Text(e.badge, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = bc, modifier = Modifier.background(bc.copy(alpha = 0.16f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                                }
                            }
                        }
                    }
                    repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ===== 목록형(게시판) =====
@Composable
private fun MoreList(groups: List<MoreGroup>, accent: Color, green: Color, red: Color, muted: Color, card: Color, scroll: ScrollState) {
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 14.dp, vertical = 6.dp)) {
        groups.forEach { g ->
            Text(g.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                g.entries.forEach { e ->
                    Card(Modifier.fillMaxWidth().clickable { e.onClick() }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                            Text(e.icon, fontSize = 22.sp, modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
                            Column(Modifier.weight(1f)) {
                                Text(e.label, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = if (e.danger) red else AppTheme.text)
                                if (e.desc.isNotEmpty()) Text(e.desc, fontSize = 11.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                            }
                            when {
                                e.chevron -> Text("›", fontSize = 18.sp, color = Color(0xFF4B5568))
                                e.right != null -> {
                                    val rc = when (e.rightKind) { 1 -> green; 2 -> red; 3 -> muted; else -> accent }
                                    Text(e.right, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = rc)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// [v18] 기능 등록소 — 홈에 뭘 보여줄지 한 곳에서 켜고/끄는 조립 패널. 탭하면 즉시 반영(옵트인 기본 켬).
@Composable
private fun FeatureRegistry(prefs: android.content.SharedPreferences, accent: Color, muted: Color, card: Color) {
    val items = listOf(
        Triple("card_brief", "🔊", "오늘 브리핑"),
        Triple("card_salary", "💰", "월급 명세서"),
        Triple("card_platform", "🏷️", "플랫폼별 매출"),
        Triple("work_session_enabled", "⏱", "근무 세션"),
        Triple("work_dist_enabled", "📏", "거리(km) 미터"),
        Triple("quick_entry_enabled", "💬", "완료 후 팝업"),
        Triple("card_notif", "💰", "금액 자동입력"),
        Triple("card_notice", "📢", "제보 배너")
    )
    val state = remember { mutableStateMapOf<String, Boolean>().apply { items.forEach { put(it.first, prefs.getBoolean(it.first, it.first != "card_platform")) } } }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text("🏠 홈 편집", fontSize = 15.sp, color = AppTheme.text, fontWeight = FontWeight.Bold)
            Text("홈에 표시할 항목을 고르세요 · '오늘 매출'과 '운행 기록 버튼'은 항상 고정 · 탭하면 바로 반영", fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            // [v21] 홈 스타일 프리셋 (골라 쓰는 홈) — 기존 온/오프 묶음 적용
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val presets = listOf(
                    "심플" to mapOf("card_brief" to true, "card_salary" to false, "card_platform" to false, "card_notice" to false),
                    "기본" to mapOf("card_brief" to true, "card_salary" to true, "card_platform" to false, "card_notice" to true),
                    "정보형" to mapOf("card_brief" to true, "card_salary" to true, "card_platform" to true, "card_notice" to true)
                )
                presets.forEach { (name, m) ->
                    Box(modifier = Modifier.weight(1f).height(38.dp).background(AppTheme.surface2, RoundedCornerShape(10.dp)).border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp)).clickable { m.forEach { (k, v) -> state[k] = v; prefs.edit().putBoolean(k, v).apply() } }, contentAlignment = Alignment.Center) {
                        Text(name, fontSize = 12.sp, color = accent, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text("홈 스타일: 탭하면 카드 구성이 한 번에 바뀝니다 (아래에서 개별 조정도 가능)", fontSize = 10.sp, color = muted, modifier = Modifier.padding(bottom = 12.dp))
            items.chunked(3).forEach { rowItems ->
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { triple ->
                        val key = triple.first; val icon = triple.second; val label = triple.third
                        val on = state[key] ?: true
                        Box(modifier = Modifier.weight(1f).height(94.dp)
                            .background(if (on) accent.copy(alpha = 0.14f) else AppTheme.surface2, RoundedCornerShape(12.dp))
                            .border(1.5.dp, if (on) accent else Color(0xFF2A3B56), RoundedCornerShape(12.dp))
                            .clickable { val nv = !on; state[key] = nv; prefs.edit().putBoolean(key, nv).apply() }
                            .padding(horizontal = 4.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(icon, fontSize = 22.sp)
                                Text(label, fontSize = 10.sp, lineHeight = 11.5.sp, color = AppTheme.text, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 3.dp))
                                Text(if (on) "켬" else "끔", fontSize = 9.sp, color = if (on) accent else muted, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                    repeat(3 - rowItems.size) { Box(modifier = Modifier.weight(1f)) {} }
                }
            }
            // [v44] 홈 하단 바로가기(액션)도 여기서 on/off — 켠 것만 홈 그리드에 표시(칸 밀림 방지).
            Spacer(Modifier.height(4.dp))
            Text("🔗 바로가기 (홈에 표시할 액션)", fontSize = 13.sp, color = AppTheme.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            Text("켠 것만 홈 하단에 나와요 · 안 쓰는 건 꺼서 깔끔하게", fontSize = 10.sp, color = muted, modifier = Modifier.padding(bottom = 8.dp))
            val actions = listOf(
                "gas" to "⛽ 가스", "elec" to "🔌 전기", "expense" to "🧾 지출촬영", "track" to "🗺️ 운행궤적",
                "import" to "📥 가져오기", "records" to "📋 기록", "airport" to "✈️ 공항", "namecard" to "📇 명함",
                "ai" to "🤖 AI비서", "events" to "📅 이벤트", "bookings" to "🚕 예약", "stats" to "📊 분석",
                "ranking" to "🏆 랭킹", "links" to "🌐 링크", "settings" to "⚙️ 기사설정", "more" to "⋯ 더보기"
            )
            var blocks by remember { mutableStateOf((prefs.getString("home_blocks", "gas,elec,expense,records,import,more") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()) }
            actions.chunked(3).forEach { rowItems ->
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { (id, label) ->
                        val on = blocks.contains(id)
                        Box(modifier = Modifier.weight(1f).height(60.dp)
                            .background(if (on) accent.copy(alpha = 0.14f) else AppTheme.surface2, RoundedCornerShape(12.dp))
                            .border(1.5.dp, if (on) accent else Color(0xFF2A3B56), RoundedCornerShape(12.dp))
                            .clickable {
                                val l = blocks.toMutableList(); if (l.contains(id)) l.remove(id) else l.add(id)
                                blocks = l; prefs.edit().putString("home_blocks", l.joinToString(",")).apply()
                            }
                            .padding(4.dp), contentAlignment = Alignment.Center) {
                            Text(label + (if (on) "  ✓" else ""), fontSize = 11.sp, lineHeight = 13.sp, color = if (on) accent else AppTheme.text, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                    repeat(3 - rowItems.size) { Box(modifier = Modifier.weight(1f)) {} }
                }
            }
        }
    }
}

// ===== 정산 설정 하위화면 (기존 SettingsView의 정산 부분 추출) =====
@Composable
private fun SettlementSettings(userId: String, context: Context, card: Color, accent: Color, green: Color, muted: Color) {
    val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    var driverType by remember { mutableStateOf(prefs.getString("driver_type", "personal") ?: "personal") }
    var affiliation by remember { mutableStateOf(prefs.getString("affiliation", "none") ?: "none") }
    var profitShare by remember { mutableStateOf(prefs.getInt("profit_share", 100)) }
    var lpgRefundRate by remember { mutableStateOf(prefs.getInt("lpg_refund_rate", 0)) }
    var workDays by remember { mutableStateOf(prefs.getInt("work_days", 26)) }
    var annualLeave by remember { mutableStateOf(prefs.getInt("annual_leave", 0)) }
    var cashToCompany by remember { mutableStateOf(prefs.getBoolean("cash_to_company", false)) }
    var showTypeDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var dailySanap by remember { mutableStateOf(prefs.getInt("daily_sanap", 0)) }
    var showSanapDialog by remember { mutableStateOf(false) }
    var sanapInput by remember { mutableStateOf("") }
    // [v19] 급여 공제 (명세서 기반): 기본급·4대보험·조합비·기타공제
    var showPayDialog by remember { mutableStateOf(false) }
    var payBase by remember { mutableStateOf(prefs.getInt("pay_base", 0)) }
    var payIns by remember { mutableStateOf(prefs.getInt("pay_insurance", 0)) }
    var payUnion by remember { mutableStateOf(prefs.getInt("pay_union", 0)) }
    var payOther by remember { mutableStateOf(prefs.getInt("pay_other_deduct", 0)) }
    var payZeroNet by remember { mutableStateOf(prefs.getBoolean("pay_zero_net", false)) }   // [v19] 실급여 0(기본급 명목상·도급제): 명세서를 홈 실수령에 0 기여
    var showFeeDialog by remember { mutableStateOf(false) }
    var kakaoFee by remember { mutableStateOf(feeRateFloat(prefs, "fee_kakao")) }   // [v17] 소수점 지원(Float)
    var uberFee by remember { mutableStateOf(feeRateFloat(prefs, "fee_uber")) }
    var tmoneyFee by remember { mutableStateOf(feeRateFloat(prefs, "fee_tmoney")) }
    var lpgPrice by remember { mutableStateOf(prefs.getInt("lpg_price", 1050)) }
    var lpgDaily by remember { mutableStateOf(prefs.getInt("lpg_daily", 40)) }
    var gasReduction by remember { mutableStateOf(prefs.getFloat("gas_reduction_f", prefs.getInt("gas_reduction", 9).toFloat())) }  // [v23] 소수점 지원(Float)
    var fuelType by remember { mutableStateOf(prefs.getString("fuel_type", "lpg") ?: "lpg") }  // [v24] lpg | ev(전기차 충전)
    var showLpgDialog by remember { mutableStateOf(false) }

    fun saveSettingsToServer() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val json = JSONObject().apply {
                        put("user_id", userId); put("driver_type", driverType); put("affiliation", affiliation)
                        put("commission_rate", (feeRateFloat(prefs,"fee_kakao") + feeRateFloat(prefs,"fee_uber") + feeRateFloat(prefs,"fee_tmoney")).toDouble())
                        put("daily_payment", prefs.getInt("daily_sanap", 0)); put("work_days", workDays)
                        put("profit_share", profitShare); put("lpg_refund_rate", lpgRefundRate)
                        put("annual_leave", annualLeave); put("gas_price", prefs.getInt("lpg_price", 0))
                    }
                    val conn = (URL("$SETTINGS_SERVER/api/driver-settings").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 15000 }
                    conn.outputStream.write(json.toString().toByteArray()); conn.responseCode
                }
            } catch (e: Exception) { }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val resp = withContext(Dispatchers.IO) { val conn = (URL("$SETTINGS_SERVER/api/driver-settings/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 30000 }; conn.inputStream.bufferedReader().readText() }
            val j = JSONObject(resp)
            // [v17][#1] 로컬 우선·서버 병합: 서버에 '실제로 존재하는' 값만 반영한다.
            // (예전엔 서버에 없는 항목까지 기본값으로 덮어써서, 앱 업데이트/재로드 때 로컬 설정이 초기화되던 버그)
            val e = prefs.edit()
            if (j.has("driver_type") && !j.isNull("driver_type") && j.optString("driver_type").isNotBlank()) { driverType = j.optString("driver_type"); e.putString("driver_type", driverType) }
            if (j.has("affiliation") && !j.isNull("affiliation") && j.optString("affiliation").isNotBlank()) { affiliation = j.optString("affiliation"); e.putString("affiliation", affiliation) }
            if (j.has("profit_share") && !j.isNull("profit_share")) { profitShare = j.optInt("profit_share", profitShare); e.putInt("profit_share", profitShare) }
            if (j.has("lpg_refund_rate") && !j.isNull("lpg_refund_rate")) { lpgRefundRate = j.optInt("lpg_refund_rate", lpgRefundRate); e.putInt("lpg_refund_rate", lpgRefundRate) }
            if (j.has("work_days") && !j.isNull("work_days")) { workDays = j.optInt("work_days", workDays); e.putInt("work_days", workDays) }
            if (j.has("annual_leave") && !j.isNull("annual_leave")) { annualLeave = j.optInt("annual_leave", annualLeave); e.putInt("annual_leave", annualLeave) }
            e.apply()
            // 공유설정(share_room_url/share_promo)·개별 수수료(fee_*)는 로컬 전용 → 서버 로드가 건드리지 않음(유지)
        } catch (e: Exception) { }
    }

    // ---- 다이얼로그 ----
    if (showPayDialog) {
        var baseIn by remember { mutableStateOf(if (payBase > 0) payBase.toString() else "") }
        var insIn by remember { mutableStateOf(if (payIns > 0) payIns.toString() else "") }
        var unionIn by remember { mutableStateOf(if (payUnion > 0) payUnion.toString() else "") }
        var otherIn by remember { mutableStateOf(if (payOther > 0) payOther.toString() else "") }
        var payOcrStatus by remember { mutableStateOf("") }
        var payOcrOk by remember { mutableStateOf(false) }
        var payRawText by remember { mutableStateOf("") }
        var showPayRaw by remember { mutableStateOf(false) }
        var zeroNetIn by remember { mutableStateOf(payZeroNet) }
        // [v19] 명세서 사진 → ML Kit 한국어 OCR → 공제칸 자동채우기 (온디바이스, 무권한 갤러리)
        fun runPayOcr(uri: Uri) {
            payOcrStatus = "명세서를 읽는 중…"; payOcrOk = false
            try {
                val image = InputImage.fromFilePath(context, uri)
                TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                    .process(image)
                    .addOnSuccessListener { vt ->
                        payRawText = vt.text
                        // 라인별 (텍스트, 중심x, 중심y) — 2단 레이아웃을 좌표로 행 매칭
                        val lines = ArrayList<Triple<String, Int, Int>>()
                        for (b in vt.textBlocks) for (l in b.lines) { val r = l.boundingBox; if (r != null) lines.add(Triple(l.text, r.centerX(), r.centerY())) }
                        val p = parsePayslip(vt.text, lines)
                        if (p.base > 0) baseIn = p.base.toString()
                        if (p.insurance > 0) insIn = p.insurance.toString()
                        if (p.union > 0) unionIn = p.union.toString()
                        if (p.other > 0) otherIn = p.other.toString()
                        val got = listOfNotNull(
                            if (p.base > 0) "기본급 ${"%,d".format(p.base)}" else null,
                            if (p.insurance > 0) "4대보험 ${"%,d".format(p.insurance)}" else null,
                            if (p.union > 0) "조합비 ${"%,d".format(p.union)}" else null,
                            if (p.other > 0) "기타 ${"%,d".format(p.other)}" else null,
                            if (p.net > 0) "명세서 실수령 ${"%,d".format(p.net)}" else null,
                        )
                        payOcrOk = got.isNotEmpty()
                        payOcrStatus = if (got.isEmpty()) "자동 인식이 안 됐어요. 아래 칸에 직접 입력해 주세요."
                            else "인식됨: ${got.joinToString(" · ")} — 숫자를 확인·수정 후 저장하세요."
                    }
                    .addOnFailureListener { e -> payOcrOk = false; payOcrStatus = "읽기 실패: ${e.message}" }
            } catch (e: Exception) { payOcrOk = false; payOcrStatus = "오류: ${e.message}" }
        }
        val payPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> if (uri != null) runPayOcr(uri) }
        AlertDialog(onDismissRequest = { showPayDialog = false },
            title = { Text("급여 공제 (명세서 기준)", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column(modifier = Modifier.imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogImeFix()
                Text("급여명세서에 적힌 월 고정 항목을 넣으면 예상 월급이 정확해져요. 회사마다 달라요.", fontSize = 12.sp, color = muted)
                Button(onClick = { payPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth().height(46.dp), colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(10.dp)) {
                    Text("📷 명세서 사진으로 자동 채우기", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                if (payOcrStatus.isNotEmpty()) Text(payOcrStatus, fontSize = 11.sp, color = if (payOcrOk) AppTheme.green else Color(0xFFEF4444))
                OutlinedTextField(value = baseIn, onValueChange = { baseIn = it.filter { c -> c.isDigit() } }, label = { Text("기본급 (월, 원) +", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                OutlinedTextField(value = insIn, onValueChange = { insIn = it.filter { c -> c.isDigit() } }, label = { Text("4대보험 (월, 원) −", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                OutlinedTextField(value = unionIn, onValueChange = { unionIn = it.filter { c -> c.isDigit() } }, label = { Text("조합비/노조비 (월, 원) −", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                OutlinedTextField(value = otherIn, onValueChange = { otherIn = it.filter { c -> c.isDigit() } }, label = { Text("기타공제 (월, 원) −", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                // [v19] 실급여 0 체크: 사납금 낮고 가스 기사부담 등 기본급이 명목상(통장 미지급)인 도급 기사용
                Row(modifier = Modifier.fillMaxWidth().clickable { zeroNetIn = !zeroNetIn }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = zeroNetIn, onCheckedChange = { zeroNetIn = it }, colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = muted, checkmarkColor = Color.Black))
                    Column(Modifier.padding(start = 4.dp)) {
                        Text("실급여 0 (기본급이 명목상)", fontSize = 13.sp, color = AppTheme.text)
                        Text("체크 시 명세서는 홈 실수령에 더하지 않아요(도급·전차금제). 도급 초과수익만 반영.", fontSize = 10.sp, color = muted)
                    }
                }
                Text("💡 사진은 기기에서만 분석돼요(서버 전송 X). 양식에 따라 일부만 인식될 수 있으니 확인 후 저장하세요.", fontSize = 10.sp, color = muted)
                if (payRawText.isNotEmpty()) {
                    TextButton(onClick = { showPayRaw = !showPayRaw }) { Text(if (showPayRaw) "OCR 원문 접기" else "OCR 원문 보기", color = muted, fontSize = 11.sp) }
                    if (showPayRaw) Text(payRawText, fontSize = 10.sp, color = muted)
                }
            } },
            confirmButton = { Button(onClick = {
                payBase = baseIn.toIntOrNull() ?: 0; payIns = insIn.toIntOrNull() ?: 0; payUnion = unionIn.toIntOrNull() ?: 0; payOther = otherIn.toIntOrNull() ?: 0; payZeroNet = zeroNetIn
                prefs.edit().putInt("pay_base", payBase).putInt("pay_insurance", payIns).putInt("pay_union", payUnion).putInt("pay_other_deduct", payOther).putBoolean("pay_zero_net", payZeroNet).apply()
                showPayDialog = false
            }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showPayDialog = false }) { Text("취소") } }, containerColor = AppTheme.card)
    }
    if (showSanapDialog) {
        AlertDialog(onDismissRequest = { showSanapDialog = false },
            title = { Text("일 사납금 설정", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column {
                Text("법인택시 일 사납금 (개인택시는 0)", fontSize = 12.sp, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(value = sanapInput, onValueChange = { sanapInput = it.filter { c -> c.isDigit() } }, label = { Text("사납금 (원)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0, 100000, 120000, 150000).forEach { amount -> OutlinedButton(onClick = { sanapInput = amount.toString() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text(if (amount == 0) "없음" else "${amount/10000}만", fontSize = 12.sp) } } }
            } },
            confirmButton = { Button(onClick = { dailySanap = sanapInput.toIntOrNull() ?: 0; prefs.edit().putInt("daily_sanap", dailySanap).apply(); showSanapDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showSanapDialog = false }) { Text("취소") } }, containerColor = AppTheme.card)
    }
    if (showFeeDialog) {
        // [v17] 소수점 1자리 허용 (예: 3.3%). 점 하나·소수 1자리·최대 100으로 정리.
        fun sanitizeFee(s: String): String {
            var t = s.filter { it.isDigit() || it == '.' }
            val dot = t.indexOf('.')
            if (dot >= 0) {
                val intPart = t.substring(0, dot)
                var dec = t.substring(dot + 1).filter { it.isDigit() }
                if (dec.length > 1) dec = dec.substring(0, 1)
                t = "$intPart.$dec"
            }
            val num = t.toFloatOrNull()
            if (num != null && num > 100f) t = "100"
            return t
        }
        var kInput by remember { mutableStateOf(fmtFee(kakaoFee)) }
        var uInput by remember { mutableStateOf(fmtFee(uberFee)) }
        var tInput by remember { mutableStateOf(fmtFee(tmoneyFee)) }
        AlertDialog(onDismissRequest = { showFeeDialog = false },
            title = { Text("플랫폼별 수수료 설정", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("가맹 기사만 입력 (비가맹은 0%) · 소수점 1자리까지 (예: 3.3)", fontSize = 12.sp, color = muted)
                OutlinedTextField(value = kInput, onValueChange = { kInput = sanitizeFee(it) }, label = { Text("카카오T 수수료 (%)", color = muted) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                OutlinedTextField(value = uInput, onValueChange = { uInput = sanitizeFee(it) }, label = { Text("우버 수수료 (%)", color = muted) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                OutlinedTextField(value = tInput, onValueChange = { tInput = sanitizeFee(it) }, label = { Text("티머니고 수수료 (%)", color = muted) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
            } },
            confirmButton = { Button(onClick = { kakaoFee = kInput.toFloatOrNull() ?: 0f; uberFee = uInput.toFloatOrNull() ?: 0f; tmoneyFee = tInput.toFloatOrNull() ?: 0f; prefs.edit().putFloat("fee_kakao", kakaoFee).putFloat("fee_uber", uberFee).putFloat("fee_tmoney", tmoneyFee).apply(); saveSettingsToServer(); showFeeDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showFeeDialog = false }) { Text("취소") } }, containerColor = AppTheme.card)
    }
    if (showLpgDialog) {
        var priceInput by remember { mutableStateOf(lpgPrice.toString()) }
        var dailyLInput by remember { mutableStateOf(lpgDaily.toString()) }
        var reductionInput by remember { mutableStateOf(if (gasReduction % 1f == 0f) gasReduction.toInt().toString() else gasReduction.toString()) }
        var subsidyInput by remember { mutableStateOf(prefs.getInt("lpg_subsidy", 221).toString()) }   // [v5] 개인 유가보조금(원/L) 편집
        var gasMethod by remember { mutableStateOf(prefs.getString("gas_method", "rate") ?: "rate") }   // [v5] 법인: rate(경감률) | fixed(고정단가)
        var fixedInput by remember { mutableStateOf(prefs.getInt("gas_fixed", 0).toString()) }          // [v5] 법인 고정 차감단가(원/L)
        AlertDialog(onDismissRequest = { showLpgDialog = false },
            title = { Text(if (fuelType == "ev") "전기차 충전 정산 설정" else "LPG 정산 설정", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // [v24] 연료 유형 — LPG / 전기차 충전
                Text("연료 유형", fontSize = 12.sp, color = muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("lpg" to "LPG", "ev" to "⚡ 전기차").forEach { (v, lbl) ->
                        FilterChip(selected = fuelType == v, onClick = { fuelType = v }, label = { Text(lbl, fontSize = 12.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
                    }
                }
                OutlinedTextField(value = priceInput, onValueChange = { priceInput = it.filter { c -> c.isDigit() } }, label = { Text(if (fuelType == "ev") "충전 단가 (원/kWh)" else "LPG 단가 (원/L)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                OutlinedTextField(value = dailyLInput, onValueChange = { dailyLInput = it.filter { c -> c.isDigit() } }, label = { Text(if (fuelType == "ev") "일 충전량 (kWh)" else "일 평균 사용량 (L)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                val price = priceInput.toIntOrNull() ?: 0
                val liters = dailyLInput.toIntOrNull() ?: 0
                if (driverType == "corporate") {
                    Text("회사 가스 정산 방식", fontSize = 12.sp, color = muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("rate" to "경감률(%)", "fixed" to "고정단가(원/L)").forEach { (v, lbl) ->
                            FilterChip(selected = gasMethod == v, onClick = { gasMethod = v }, label = { Text(lbl, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
                        }
                    }
                    if (gasMethod == "rate") {
                        OutlinedTextField(value = reductionInput, onValueChange = { v -> val f = v.replace(",", ".").filter { it.isDigit() || it == '.' }; val ok = f.count { it == '.' } <= 1 && (f.toFloatOrNull() ?: 0f) <= 100f; if (f.isEmpty() || f == "." || ok) reductionInput = f }, label = { Text("가스 경감률 (%) — 소수점 가능 (예: 8.5)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                        val redRate = reductionInput.toFloatOrNull() ?: 0f
                        val gross = price * liters
                        val net = (gross * (100 - redRate) / 100.0).toInt()
                        if (gross > 0) {
                            Card(colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(8.dp)) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("일 가스총액: ${String.format("%,d", gross)}원 (${price}×${liters}L)", fontSize = 12.sp, color = AppTheme.text)
                                    Text("경감 ${if (redRate % 1f == 0f) redRate.toInt().toString() else redRate.toString()}% 적용", fontSize = 12.sp, color = green)
                                    Text("일 실부담(차감액): ${String.format("%,d", net)}원", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
                                    Text("💡 월 차감 = 실부담 × 근무일", fontSize = 10.sp, color = muted)
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(value = fixedInput, onValueChange = { fixedInput = it.filter { c -> c.isDigit() } }, label = { Text("회사 고정 차감단가 (원/L)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                        val fixed = fixedInput.toIntOrNull() ?: 0
                        val net = fixed * liters
                        if (fixed > 0 && liters > 0) {
                            Card(colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(8.dp)) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("회사 고정단가 ${String.format("%,d", fixed)}원/L × ${liters}L", fontSize = 12.sp, color = AppTheme.text)
                                    Text("일 실부담(차감액): ${String.format("%,d", net)}원", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
                                    Text("💡 회사가 정한 리터당 고정 정산단가로 계산돼요. 월 차감 = 실부담 × 근무일", fontSize = 10.sp, color = muted)
                                }
                            }
                        }
                    }
                } else if (fuelType == "ev") {
                    val cost = price * liters
                    if (cost > 0) {
                        Card(colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(8.dp)) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("일 충전비: ${String.format("%,d", cost)}원 (${price}원/kWh × ${liters}kWh)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
                                Text("💡 전기차는 유가보조금이 없어요. 월 충전비 = 일 충전비 × 근무일", fontSize = 10.sp, color = muted)
                            }
                        }
                    }
                } else {
                    OutlinedTextField(value = subsidyInput, onValueChange = { subsidyInput = it.filter { c -> c.isDigit() } }, label = { Text("유가보조금 (원/L)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0, 197, 221, 250).forEach { amount -> OutlinedButton(onClick = { subsidyInput = amount.toString() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text(if (amount == 0) "없음" else "${amount}원", fontSize = 12.sp) } } }
                    val subsidy = subsidyInput.toIntOrNull() ?: 0
                    val cost = price * liters
                    val subsidyTotal = subsidy * liters
                    val net = cost - subsidyTotal
                    if (cost > 0) {
                        Card(colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(8.dp)) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("일 연료비: ${String.format("%,d", cost)}원", fontSize = 12.sp, color = AppTheme.text)
                                Text("유가보조금: -${String.format("%,d", subsidyTotal)}원 (${subsidy}원/L)", fontSize = 12.sp, color = green)
                                Text("실 연료비: ${String.format("%,d", net)}원", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
                                Text("💡 유가보조금 단가는 지역·시기별로 달라요. 바뀌면 여기서 수정하세요.", fontSize = 10.sp, color = muted)
                            }
                        }
                    }
                }
            } },
            confirmButton = { Button(onClick = {
                lpgPrice = priceInput.toIntOrNull() ?: 1050
                lpgDaily = dailyLInput.toIntOrNull() ?: 40
                gasReduction = reductionInput.toFloatOrNull() ?: 9f
                val sub = subsidyInput.toIntOrNull() ?: 221
                val fixedV = fixedInput.toIntOrNull() ?: 0
                // [v16] 일 가스 실부담(원) 계산 → 홈 순수익이 읽는 단일 소스
                val dailyCost = if (driverType == "corporate") {
                    if (gasMethod == "fixed") fixedV * lpgDaily
                    else (lpgPrice.toDouble() * lpgDaily * (100 - gasReduction) / 100.0).toInt()
                } else if (fuelType == "ev") {
                    (lpgPrice * lpgDaily).coerceAtLeast(0)   // [v24] 전기차: 충전단가 × 충전량 (보조금 없음)
                } else {
                    ((lpgPrice - sub) * lpgDaily).coerceAtLeast(0)
                }
                prefs.edit().putString("fuel_type", fuelType).putInt("lpg_price", lpgPrice).putInt("lpg_daily", lpgDaily).putFloat("gas_reduction_f", gasReduction).putInt("gas_reduction", gasReduction.toInt()).putInt("lpg_subsidy", sub).putString("gas_method", gasMethod).putInt("gas_fixed", fixedV).putInt("lpg_daily_cost", dailyCost).apply()
                showLpgDialog = false
            }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showLpgDialog = false }) { Text("취소") } }, containerColor = AppTheme.card)
    }
    // [v17] '일 고정 지출' 다이얼로그 제거 — 잡지출/영수증으로 일원화
    if (showTypeDialog) {
        AlertDialog(onDismissRequest = { showTypeDialog = false },
            title = { Text("기사 유형 설정", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("운행 형태", fontSize = 13.sp, color = muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("개인기사" to "personal", "법인기사" to "corporate").forEach { (label, value) ->
                        FilterChip(selected = driverType == value, onClick = { driverType = value }, label = { Text(label, fontSize = 12.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
                    }
                }
                Text("가맹 형태", fontSize = 13.sp, color = muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("비가맹" to "none", "카카오가맹" to "kakao", "우버가맹" to "uber").forEach { (label, value) ->
                        FilterChip(selected = affiliation == value, onClick = { affiliation = value }, label = { Text(label, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = green, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
                    }
                }
                if (driverType == "corporate") {
                    Text("만근일 수", fontSize = 13.sp, color = muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(25, 26).forEach { d -> FilterChip(selected = workDays == d, onClick = { workDays = d }, label = { Text("${d}일", fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted)) }
                    }
                }
                Text("💡 수수료·사납금은 아래 항목에서 직접 입력하세요", fontSize = 11.sp, color = muted)
            } },
            confirmButton = { Button(onClick = {
                val ed = prefs.edit().putString("driver_type", driverType).putString("affiliation", affiliation).putInt("work_days", workDays)
                if (driverType == "personal") { ed.putInt("daily_sanap", 0); dailySanap = 0 }  // [개인/법인 분리] 개인택시 전환 시 잔존 법인 사납값 제거
                ed.apply(); saveSettingsToServer(); showTypeDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showTypeDialog = false }) { Text("취소") } }, containerColor = AppTheme.card)
    }
    if (showShareDialog) {
        var shareInput by remember { mutableStateOf(profitShare.toString()) }
        AlertDialog(onDismissRequest = { showShareDialog = false },
            title = { Text("초과수익 분배율", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("사납금 초과분 중 기사 몫 (%)", fontSize = 12.sp, color = muted)
                OutlinedTextField(value = shareInput, onValueChange = { v -> val f = v.filter { it.isDigit() }.take(3); if (f.isEmpty() || f.toInt() <= 100) shareInput = f }, label = { Text("기사 몫 (%)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(100, 90, 80, 70, 60).forEach { p -> OutlinedButton(onClick = { shareInput = p.toString() }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text("${p}%", fontSize = 11.sp) } } }
            } },
            confirmButton = { Button(onClick = { profitShare = shareInput.toIntOrNull() ?: 100; prefs.edit().putInt("profit_share", profitShare).apply(); saveSettingsToServer(); showShareDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showShareDialog = false }) { Text("취소") } }, containerColor = AppTheme.card)
    }
    if (showLeaveDialog) {
        var leaveInput by remember { mutableStateOf(annualLeave.toString()) }
        AlertDialog(onDismissRequest = { showLeaveDialog = false },
            title = { Text("이번달 연차", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("연차 1일당 사납금이 하루치 면제됩니다", fontSize = 12.sp, color = muted)
                OutlinedTextField(value = leaveInput, onValueChange = { v -> leaveInput = v.filter { it.isDigit() }.take(2) }, label = { Text("연차 일수", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
            } },
            confirmButton = { Button(onClick = { annualLeave = leaveInput.toIntOrNull() ?: 0; prefs.edit().putInt("annual_leave", annualLeave).apply(); saveSettingsToServer(); showLeaveDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showLeaveDialog = false }) { Text("취소") } }, containerColor = AppTheme.card)
    }

    // ---- 본문 카드 목록 ----
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(modifier = Modifier.fillMaxWidth().clickable { showTypeDialog = true }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("🚖 기사 유형", fontSize = 14.sp, color = AppTheme.text, fontWeight = FontWeight.Bold); Text("정산 방식의 기준이 됩니다", fontSize = 11.sp, color = muted) }
                Text("${if (driverType == "corporate") "법인" else "개인"} · ${when(affiliation){"kakao"->"카카오";"uber"->"우버";else->"비가맹"}}", fontSize = 13.sp, color = accent, fontWeight = FontWeight.Bold)
            }
        }
        if (driverType == "corporate") {
            Card(modifier = Modifier.fillMaxWidth().clickable { sanapInput = dailySanap.toString(); showSanapDialog = true }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("일 사납금", fontSize = 14.sp, color = AppTheme.text); Text("만근 ${workDays}일 기준", fontSize = 11.sp, color = muted) }
                    Text(if (dailySanap > 0) "${String.format("%,d", dailySanap)}원" else "미설정", fontSize = 13.sp, color = if (dailySanap > 0) accent else muted)
                }
            }
            Card(modifier = Modifier.fillMaxWidth().clickable { showShareDialog = true }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("초과수익 분배율", fontSize = 14.sp, color = AppTheme.text); Text("사납금 초과분 중 기사 몫", fontSize = 11.sp, color = muted) }
                    Text("${profitShare}%", fontSize = 13.sp, color = accent)
                }
            }
            Card(modifier = Modifier.fillMaxWidth().clickable { showLeaveDialog = true }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("이번달 연차", fontSize = 14.sp, color = AppTheme.text); Text("연차 1일당 사납금 면제", fontSize = 11.sp, color = muted) }
                    Text("${annualLeave}일", fontSize = 13.sp, color = accent)
                }
            }
            Card(modifier = Modifier.fillMaxWidth().clickable { cashToCompany = !cashToCompany; prefs.edit().putBoolean("cash_to_company", cashToCompany).apply() }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("현금 회사 납부", fontSize = 14.sp, color = AppTheme.text); Text(if (cashToCompany) "현금도 회사에 납부함" else "현금은 내가 가짐 (미납부)", fontSize = 11.sp, color = muted) }
                    Text(if (cashToCompany) "납부 O" else "내 몫", fontSize = 13.sp, color = accent, fontWeight = FontWeight.Bold)
                }
            }
            // [v19] 급여 공제 (명세서 기준): 기본급·4대보험·조합비·기타공제
            Card(modifier = Modifier.fillMaxWidth().clickable { showPayDialog = true }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("📄 급여 공제 (명세서)", fontSize = 14.sp, color = AppTheme.text); Text("기본급·4대보험·조합비·기타공제", fontSize = 11.sp, color = muted) }
                    Text(if (payBase + payIns + payUnion + payOther > 0) "설정됨" else "미설정", fontSize = 13.sp, color = if (payBase + payIns + payUnion + payOther > 0) accent else muted, fontWeight = FontWeight.Bold)
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth().clickable { showFeeDialog = true }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("플랫폼별 수수료", fontSize = 14.sp, color = AppTheme.text); Text("가맹 기사만 설정 (비가맹은 0%)", fontSize = 11.sp, color = muted) }
                Text("카${fmtFee(kakaoFee)}% 우${fmtFee(uberFee)}% 티${fmtFee(tmoneyFee)}%", fontSize = 11.sp, color = accent)
            }
        }
        Card(modifier = Modifier.fillMaxWidth().clickable { showLpgDialog = true }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text(if (fuelType == "ev") "⚡ 전기차 충전 정산" else "LPG 정산", fontSize = 14.sp, color = AppTheme.text); Text(if (fuelType == "ev") "충전단가·충전량" else "단가·사용량·유가보조금", fontSize = 11.sp, color = muted) }
                Text(if (lpgPrice > 0) "${lpgPrice}원/${if (fuelType == "ev") "kWh" else "L"} · ${lpgDaily}${if (fuelType == "ev") "kWh" else "L"}" else "미설정", fontSize = 11.sp, color = accent)
            }
        }
        // [v17] '일 고정 지출' 항목 제거 — 잡지출/영수증(기록 탭)과 중복이라 일원화
        Spacer(Modifier.height(12.dp))
    }
}

// [v19] 급여명세서 OCR 파싱 결과 (docs/27). net = 명세서 차인지급액(실수령) 인식값(있으면).
private data class PayParsed(val base: Int, val insurance: Int, val union: Int, val other: Int, val net: Int)

/**
 * 급여명세서 OCR → (기본급, 4대보험, 조합비, 기타공제, 실수령) 역산.
 * ML Kit은 2단(지급/공제) 표에서 라벨 열을 먼저·금액 열을 나중에 읽어 평면 텍스트 순서가 뒤섞임.
 * → 라인 바운딩박스(중심 x,y)로 '같은 행'의 라벨↔금액을 짝지어 매칭한다(좌표 우선, 텍스트 폴백).
 */
private fun parsePayslip(raw: String, lines: List<Triple<String, Int, Int>> = emptyList()): PayParsed {
    val amtRe = Regex("[0-9]{1,3}(?:,[0-9]{3})+|\\d{4,}")
    fun toInt(s: String) = s.replace(",", "").toIntOrNull() ?: 0
    fun isAmountOnly(t: String) = t.isNotBlank() && t.replace(Regex("[0-9,\\s]"), "").isEmpty() && amtRe.containsMatchIn(t)

    // ---- 좌표 기반 (권장) ----
    if (lines.isNotEmpty()) {
        data class Amt(val v: Int, val x: Int, val y: Int)
        val amts = lines.mapNotNull { (t, x, y) -> if (isAmountOnly(t)) amtRe.find(t)?.let { Amt(toInt(it.value), x, y) } else null }
            .filter { it.v in 1000..99999999 }
        // 라벨(키워드) 라인의 같은 행에서, 라벨 오른쪽의 가장 가까운 금액
        fun geo(vararg keys: String): Int {
            val lab = lines.firstOrNull { (t, _, _) -> keys.any { t.contains(it) } && !isAmountOnly(t) } ?: return 0
            val (_, lx, ly) = lab
            val row = amts.filter { kotlin.math.abs(it.y - ly) <= 30 }
            val right = row.filter { it.x > lx }.minByOrNull { it.x - lx }
            return (right ?: row.minByOrNull { kotlin.math.abs(it.x - lx) })?.v ?: 0
        }
        val base = geo("기본급여", "기본 급여", "기본급")
        val insSum = geo("국민연금") + geo("건강보험") + geo("장기요양", "요양보험") + geo("고용보험")
        val insurance = if (insSum > 0) insSum else geo("4대보험", "사대보험")
        val union = geo("노동조합비", "조합비", "노조비")
        // 소득세는 '지방' 미포함 라벨만
        val incomeTax = lines.firstOrNull { (t, _, _) -> t.contains("소득세") && !t.contains("지방") && !isAmountOnly(t) }?.let { (_, lx, ly) ->
            val row = amts.filter { kotlin.math.abs(it.y - ly) <= 30 }
            (row.filter { it.x > lx }.minByOrNull { it.x - lx } ?: row.minByOrNull { kotlin.math.abs(it.x - lx) })?.v ?: 0
        } ?: 0
        val other = incomeTax + geo("지방소득세", "지방세") + geo("기타공제", "기타 공제")
        val net = geo("차인지급액", "실지급액", "실수령")
        if (base > 0 || insurance > 0 || union > 0 || other > 0 || net > 0) return PayParsed(base, insurance, union, other, net)
    }

    // ---- 텍스트 폴백 (좌표 없거나 실패 시) ----
    fun amountFor(vararg keys: String): Int {
        for (line in raw.split(Regex("\\r?\\n"))) {
            if (keys.any { line.contains(it) }) {
                val idx = keys.map { line.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1
                val after = if (idx >= 0) line.substring(idx) else line
                amtRe.find(after)?.let { return toInt(it.value) }
            }
        }
        return 0
    }
    val base = amountFor("기본급여", "기본 급여", "기본급")
    val insSum = amountFor("국민연금") + amountFor("건강보험") + amountFor("장기요양", "요양보험") + amountFor("고용보험")
    val insurance = if (insSum > 0) insSum else amountFor("4대보험", "사대보험")
    val union = amountFor("노동조합비", "조합비", "노조비")
    val incomeTax = run {
        for (line in raw.split(Regex("\\r?\\n"))) if (line.contains("소득세") && !line.contains("지방")) { amtRe.find(line.substring(line.indexOf("소득세")))?.let { return@run toInt(it.value) } }
        0
    }
    val other = incomeTax + amountFor("지방소득세", "지방세") + amountFor("기타공제", "기타 공제")
    val net = amountFor("차인지급액", "실지급액", "실수령")
    return PayParsed(base, insurance, union, other, net)
}

// [v20] 예약 요청 — 명함 QR로 승객이 넣은 예약을 기사가 확인·수락/거절·전화
@Composable
private fun BookingsView(userId: String, context: Context, accent: Color, muted: Color, card: Color) {
    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var reloadTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    fun setStatus(id: Int, status: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val conn = (URL("$SETTINGS_SERVER/api/bookings/$id/status").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 8000 }
                    conn.outputStream.use { it.write("{\"status\":\"$status\"}".toByteArray(Charsets.UTF_8)); it.flush() }; conn.responseCode
                }
            } catch (e: Exception) { }
            reloadTick++
        }
    }
    LaunchedEffect(reloadTick) {
        loading = true
        val list = ArrayList<JSONObject>()
        try {
            val json = withContext(Dispatchers.IO) {
                val conn = (URL("$SETTINGS_SERVER/api/bookings/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                conn.inputStream.bufferedReader().use { it.readText() }
            }
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        } catch (e: Exception) { }
        items = list; loading = false
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("명함 QR로 받은 예약", fontSize = 13.sp, color = muted)
            TextButton(onClick = { reloadTick++ }) { Text("새로고침", fontSize = 13.sp, color = accent) }
        }
        Spacer(Modifier.height(6.dp))
        if (loading) {
            Text("불러오는 중…", fontSize = 14.sp, color = muted)
        } else if (items.isEmpty()) {
            Text("아직 받은 예약이 없어요.\n명함 QR을 손님이 찍고 예약을 넣으면 여기에 떠요.", fontSize = 14.sp, color = muted)
        } else {
            items.forEach { b ->
                val id = b.optInt("id", 0); val nm = b.optString("passenger_name", "").ifBlank { "이름없음" }
                val ph = b.optString("passenger_phone", ""); val st = b.optString("status", "requested")
                val d = b.optString("ride_date", ""); val t = b.optString("ride_time", "")
                val o = b.optString("origin", ""); val ds = b.optString("destination", ""); val memo = b.optString("memo", "")
                val stLabel = when (st) { "accepted" -> "✅ 수락함"; "declined" -> "❌ 거절함"; else -> "🔔 새 요청" }
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(nm, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                            Text(stLabel, fontSize = 12.sp, color = if (st == "requested") accent else muted)
                        }
                        if (d.isNotBlank() || t.isNotBlank()) Text("🗓 $d $t", fontSize = 13.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
                        if (o.isNotBlank() || ds.isNotBlank()) Text("📍 ${o.ifBlank{"?"}} → ${ds.ifBlank{"?"}}", fontSize = 13.sp, color = AppTheme.text, modifier = Modifier.padding(top = 2.dp))
                        if (memo.isNotBlank() && memo != "null") Text("메모: $memo", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            if (ph.isNotBlank()) OutlinedButton(onClick = { try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$ph")).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {} }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("📞 전화", fontSize = 13.sp, color = accent) }
                            if (st == "requested") {
                                Button(onClick = { setStatus(id, "accepted") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("수락", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                                OutlinedButton(onClick = { setStatus(id, "declined") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("거절", fontSize = 13.sp, color = muted) }
                            }
                            // [#46 캘린더] 날짜 있는 예약은 폰 캘린더에 원터치 등록 (수락 여부 무관 — 잊어버림 방지)
                            if (d.isNotBlank() && d != "null") {
                                OutlinedButton(onClick = {
                                    try {
                                        val cal = java.util.Calendar.getInstance()
                                        val dp = d.split("-").map { it.toInt() }
                                        val tp = (if (t.isNotBlank() && t != "null") t else "09:00").split(":").map { it.toIntOrNull() ?: 0 }
                                        cal.set(dp[0], dp[1] - 1, dp[2], tp.getOrElse(0) { 9 }, tp.getOrElse(1) { 0 }, 0)
                                        val begin = cal.timeInMillis
                                        val intent = android.content.Intent(android.content.Intent.ACTION_INSERT).apply {
                                            data = android.provider.CalendarContract.Events.CONTENT_URI
                                            putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                                            putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, begin + 3600_000L)
                                            putExtra(android.provider.CalendarContract.Events.TITLE, "🚕 예약: $nm${if (ph.isNotBlank()) " ($ph)" else ""}")
                                            putExtra(android.provider.CalendarContract.Events.DESCRIPTION, "콜레이더 예약\n${o.ifBlank { "?" }} → ${ds.ifBlank { "?" }}${if (memo.isNotBlank() && memo != "null") "\n메모: $memo" else ""}")
                                            putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, o)
                                            putExtra(android.provider.CalendarContract.Events.HAS_ALARM, 1)
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) { android.widget.Toast.makeText(context, "캘린더 앱을 열 수 없어요", android.widget.Toast.LENGTH_SHORT).show() }
                                }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("📅 캘린더", fontSize = 13.sp, color = accent) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// [v20] 이벤트·수요 정보 — 서버 /api/events(공식데이터 실시간) + 지역/카테고리 온오프
@Composable
private fun EventsView(context: Context, accent: Color, muted: Color, card: Color) {
    val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    val allCats = listOf("축제", "공연", "야구", "크루즈", "지역행사")
    val regions = listOf("전국", "서울", "경기", "인천", "부산", "대구", "광주", "대전", "울산", "세종", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주")
    val selRegions = remember { mutableStateListOf<String>().apply { addAll((prefs.getString("event_regions", "") ?: "").split(",").filter { it.isNotBlank() }) } }
    val offCats = remember { mutableStateListOf<String>().apply { addAll((prefs.getString("event_off_cats", "") ?: "").split(",").filter { it.isNotBlank() }) } }
    var loading by remember { mutableStateOf(true) }
    var events by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val scope = rememberCoroutineScope()
    fun load() {
        loading = true
        scope.launch {
            val list = ArrayList<JSONObject>()
            try {
                val json = withContext(Dispatchers.IO) {
                    val conn = (URL("$SETTINGS_SERVER/api/events?days=90").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 8000; readTimeout = 8000 }
                    conn.inputStream.bufferedReader().use { it.readText() }
                }
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
            } catch (e: Exception) { }
            events = list; loading = false
        }
    }
    LaunchedEffect(Unit) { load() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("지역 (여러 개 선택 가능 · 전국=전체)", fontSize = 13.sp, color = muted)
        Spacer(Modifier.height(4.dp))
        regions.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                row.forEach { r ->
                    FilterChip(selected = (r == "전국" && selRegions.isEmpty()) || selRegions.contains(r), onClick = {
                        if (r == "전국") selRegions.clear() else { if (selRegions.contains(r)) selRegions.remove(r) else selRegions.add(r) }
                        prefs.edit().putString("event_regions", selRegions.joinToString(",")).apply()
                    },
                        label = { Text(r, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("카테고리 (탭해서 켜고/끄기)", fontSize = 13.sp, color = muted)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            allCats.forEach { c ->
                val on = !offCats.contains(c)
                FilterChip(selected = on, onClick = {
                    if (on) offCats.add(c) else offCats.remove(c)
                    prefs.edit().putString("event_off_cats", offCats.joinToString(",")).apply()
                }, label = { Text(c, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
            }
        }
        Spacer(Modifier.height(14.dp))
        if (loading) {
            Text("불러오는 중…", fontSize = 14.sp, color = muted)
        } else {
            val filtered = events.filter { e ->
                val cat = e.optString("category"); val area = e.optString("area")
                !offCats.contains(cat) && (selRegions.isEmpty() || selRegions.contains(area))
            }
            Text("표시 중 ${filtered.size}건", fontSize = 12.sp, color = muted)
            Spacer(Modifier.height(6.dp))
            if (filtered.isEmpty()) Text("표시할 이벤트가 없어요. (지역·카테고리 확인)", fontSize = 14.sp, color = muted)
            filtered.take(60).forEach { e ->
                val title = e.optString("title"); val cat = e.optString("category")
                val area = e.optString("area"); val venue = e.optString("venue"); val start = e.optString("start_at").take(10)
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                    val areaTxt = if (area.isNotBlank() && area != "null") area else ""
                    Text("$cat · $areaTxt · $start", fontSize = 12.sp, color = accent)
                    if (venue.isNotBlank() && venue != "null") Text(venue, fontSize = 12.sp, color = muted)
                }
                HorizontalDivider(color = AppTheme.surface2)
            }
        }
    }
}

@Composable
private fun LinksView(context: Context, card: Color, accent: Color, muted: Color) {
    data class LinkItem(val emoji: String, val title: String, val desc: String, val url: String)
    data class LinkSection(val title: String, val color: Color, val links: List<LinkItem>)

    val sections = listOf(
        LinkSection("✈️ 공항 기사용", accent, listOf(
            LinkItem("🛫", "인천국제공항", "실시간 항공편·혼잡도 확인", "https://www.airport.kr"),
            LinkItem("🛬", "김포공항", "국내선 항공편 확인", "https://www.airport.co.kr/gimpo"),
            LinkItem("🚄", "공항철도 시간표", "AREX 운행 정보", "https://www.arex.or.kr"),
            LinkItem("🌍", "FlightRadar24", "실시간 항공기 추적", "https://www.flightradar24.com"),
            LinkItem("🛣️", "서울 도시고속도로", "공항로·올림픽대로 실시간", "https://www.ex.co.kr"),
            LinkItem("⛅", "항공기상청", "공항 기상 정보", "https://amo.kma.go.kr"),
            LinkItem("🚢", "인천항 크루즈 일정", "입항 크루즈 하선 일정", "https://www.icpa.or.kr")
        ))
    )

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        sections.forEach { section ->
            Text(section.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = section.color, modifier = Modifier.padding(bottom = 8.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
                Column {
                    section.links.forEachIndexed { index, link ->
                        Row(modifier = Modifier.fillMaxWidth().clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) }.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(link.emoji, fontSize = 24.sp)
                                Column { Text(link.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text); Text(link.desc, fontSize = 11.sp, color = muted) }
                            }
                            Text("→", fontSize = 16.sp, color = muted)
                        }
                        if (index < section.links.size - 1) HorizontalDivider(color = AppTheme.surface2, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
