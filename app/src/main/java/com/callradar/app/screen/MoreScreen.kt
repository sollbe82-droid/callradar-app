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
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
// 무료 버전 플래그: true면 자동화(접근성/알림 등) 권한 카드 숨김. 유료판 낼 때 false로.
private const val IS_FREE_VERSION = true
// 오픈톡방 링크 (버그·제안 제보방)
private const val OPEN_CHAT_URL = "https://open.kakao.com/o/pqocJcDi"

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
fun MoreScreen(userId: String, onLogout: () -> Unit, onOpenDailySettlement: () -> Unit = {}, openSettleTick: Int = 0) {
    val context = LocalContext.current
    var route by remember { mutableStateOf(R_HOME) }
    val card = AppTheme.card; val accent = Color(0xFFF59E0B); val muted = Color(0xFF6B7280)

    // [v19] 하위화면에서 폰 뒤로가기 → 앱 종료 대신 더보기 홈으로
    BackHandler(enabled = route != R_HOME) { route = R_HOME }
    // [v19] 홈 '기사 설정' 버튼에서 열면 정산 설정으로 바로 진입
    LaunchedEffect(openSettleTick) { if (openSettleTick > 0) route = R_SETTLEMENT }

    when (route) {
        R_HOME -> MoreHome(
            userId = userId, onLogout = onLogout, onOpenDailySettlement = onOpenDailySettlement,
            onNavigate = { route = it }
        )
        R_STATS -> MoreSubScreen("분석", onBack = { route = R_HOME }) { StatsScreen(userId = userId) }
        R_RANKING -> MoreSubScreen("랭킹", onBack = { route = R_HOME }) { RankingScreen(userId = userId) }
        R_LINKS -> MoreSubScreen("유용한 링크", onBack = { route = R_HOME }) {
            LinksView(context = context, card = card, accent = accent, muted = muted)
        }
        R_REGISTRY -> MoreSubScreen("기능 등록소", onBack = { route = R_HOME }) {
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
                    if (arrowLine != null) {
                        val parts = arrowLine.split("→", "->", "=>", " - ").map { it.trim() }.filter { it.isNotEmpty() }
                        if (origin.isBlank() && parts.isNotEmpty()) origin = parts[0].take(30)
                        if (dest.isBlank() && parts.size >= 2) dest = parts[1].take(30)
                    } else {
                        if (origin.isBlank() && lines.isNotEmpty()) origin = lines[0].take(30)
                        if (dest.isBlank() && lines.size >= 2) dest = lines[1].take(30)
                    }
                    rawOcr = text.take(1000)   // 교정 전 원본(학습 말뭉치)
                    memo = text.take(300)   // 원문 보존(서버 AI가 나중에 더 잘 파싱)
                    ocrStatus = "읽었어요 — 출발지·목적지를 확인·수정 후 저장하세요."
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
                val txt = URL("$SETTINGS_SERVER/api/report-hotspots").readText()
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
                val o = org.json.JSONObject(URL("$SETTINGS_SERVER/api/rhythm/$userId").readText())
                val days = listOf("일", "월", "화", "수", "목", "금", "토")
                val dw = o.optJSONArray("by_dow"); val hr = o.optJSONArray("by_hour")
                val dt = if (dw != null && dw.length() > 0) { val d = dw.getJSONObject(0); days.getOrElse(d.optInt("dow")) { "?" } + "요일 (평균 " + String.format("%,d", d.optInt("avg_fare")) + "원)" } else ""
                val ht = if (hr != null && hr.length() > 0) { val h = hr.getJSONObject(0); h.optInt("hour").toString() + "시경 (평균 " + String.format("%,d", h.optInt("avg_fare")) + "원)" } else ""
                Triple(o.optInt("total_trips"), dt, ht)
            } catch (e: Exception) { Triple(0, "", "") }
        }
        rhythmTotal = r.first; rhythmDay = r.second; rhythmHour = r.third
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 정직한 준비-중 안내 (구라 없이: 지금은 개인 기록으로 남고, 데이터 쌓이면 분석이 켜짐)
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("🤖 AI 운행 비서", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                Text("준비 중 · 데이터를 모으는 중입니다", fontSize = 12.sp, color = accent, fontWeight = FontWeight.Bold)
                Text("시외(영업 외 지역)에 나갔을 때 언제·어디서 귀로콜이 잡혔는지 기록해 두세요. 기록이 쌓이면 '이 시간, 이 지역에서 서울행 콜이 잦다' 같은 분석을 비서가 대신 해드립니다. 지금 남기는 건 우선 내 개인 기록으로 그대로 남고, 데이터가 충분해지면 분석이 켜집니다.", fontSize = 12.sp, color = muted, lineHeight = 18.sp)
                Text("※ 아직 없는 분석을 있는 척하지 않습니다. 데이터가 먼저입니다.", fontSize = 11.sp, color = muted)
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
                                    val conn = (URL("$SETTINGS_SERVER/api/trips/manual").openConnection() as HttpURLConnection).apply {
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
private fun MoreHome(userId: String, onLogout: () -> Unit, onOpenDailySettlement: () -> Unit, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    val card = AppTheme.card; val accent = Color(0xFFF59E0B)
    val green = Color(0xFF10B981); val red = Color(0xFFEF4444); val muted = Color(0xFF6B7280)
    val scope = rememberCoroutineScope()

    var viewMode by remember { mutableStateOf(prefs.getString("more_view_mode", "icon") ?: "icon") }

    // ----- 랜딩에서 여는 다이얼로그 상태 -----
    var floatingOn by remember { mutableStateOf(prefs.getBoolean("floating_on", false)) }
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
    var showCaptureConsent by remember { mutableStateOf(false) }   // [v18] 화면캡처 인앱 고지
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
        MoreGroup("오늘 할 일", listOf(
            MoreEntry("📋", "일일 마감", "전표·연료비로 매출 정리·회사 제출", chevron = true) { onOpenDailySettlement() },
            MoreEntry("🚕", "운행 버튼", "화면 위 플로팅 버튼으로 GPS 기록",
                right = if (floatingOn) "켜짐" else "꺼짐", rightKind = if (floatingOn) 1 else 2,
                badge = if (floatingOn) "켜짐" else "꺼짐", badgeKind = if (floatingOn) 1 else 2) {
                val act = context as? MainActivity
                if (floatingOn) { act?.stopFloatingButton(); floatingOn = false }
                else { act?.startFloatingButton(); floatingOn = prefs.getBoolean("floating_on", false) }
            },
            MoreEntry("📸", "화면 스샷 공유", "지금 화면을 캡처해 워터마크 붙여 공유", right = "공유") { showCaptureConsent = true },
            MoreEntry("🚕", "요금 미터기", "GPS 추정 요금(재미로) · 배터리 소모 큼", right = "추정") {
                try { com.callradar.app.MeterActivity.start(context) } catch (e: Exception) {}
            }
        )),
        MoreGroup("화면 · 기능", listOf(
            MoreEntry("🧩", "기능 등록소", "홈에 보일 카드 켜고/끄기", chevron = true) { onNavigate(R_REGISTRY) },
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
            }
        )),
        MoreGroup("정산 · 설정", listOf(
            // [v19] '기사 유형'은 정산 설정 안에 있으므로 중복 항목 제거 → 하나로 통합. 현재 유형은 오른쪽에 표시.
            MoreEntry("⚙️", "기사 설정", "유형·사납금·가스·수수료·연차", right = driverTypeLabel, rightKind = 3) { onNavigate(R_SETTLEMENT) },
            MoreEntry("🙋", "내 이름", "홈·랭킹에 보이는 이름", right = nickname.ifEmpty { "기사님" }, rightKind = 3) {
                nameInput = nickname; showNameDialog = true
            }
        )),
        MoreGroup("계정 · 연결 (2·3폰)", listOf(
            MoreEntry("📱", "다른 폰 연결", "주폰에서 코드 생성 → 서브폰 입력", right = "코드 생성") {
                if (!pairGenLoading) {
                    pairGenLoading = true; pairCodeGen = ""; showPairCode = true
                    scope.launch {
                        try {
                            val resp = withContext(Dispatchers.IO) {
                                val json = JSONObject().apply { put("user_id", userId) }
                                val conn = (URL("$SETTINGS_SERVER/api/pair/create").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000 }
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
        MoreGroup("정보", listOf(
            MoreEntry("🤖", "AI 운행 비서", "시외·귀로콜 기록 → 데이터 쌓이면 수요 분석", right = "준비 중", rightKind = 0, chevron = true) { onNavigate(R_AI) },
            MoreEntry("🚕", "예약 요청 (단골)", "명함 QR로 받은 예약 확인·수락", chevron = true) { onNavigate(R_BOOKINGS) },
            MoreEntry("📅", "이벤트·수요 정보", "내 지역 축제·공연·수요 (온·오프)", chevron = true) { onNavigate(R_EVENTS) },
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

        if (viewMode == "icon") MoreGrid(groups, accent, muted, card) else MoreList(groups, accent, green, red, muted, card)
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
                    scope.launch { try { withContext(Dispatchers.IO) { val json = JSONObject().apply { put("nickname", nn) }; val conn = (URL("$SETTINGS_SERVER/api/users/$userId").openConnection() as HttpURLConnection).apply { requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json"); doOutput = true }; conn.outputStream.write(json.toString().toByteArray(Charsets.UTF_8)); conn.responseCode } } catch (e: Exception) { } }
                }
                showNameDialog = false
            }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showNameDialog = false }) { Text("취소") } }, containerColor = AppTheme.card)
    }

    if (showDayStartDlg) {
        AlertDialog(onDismissRequest = { showDayStartDlg = false },
            title = { Text("영업일 시작 시각", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column {
                Text("하루의 시작을 이 시각으로 잡아요. 개인·일차·야간 누구나 설정할 수 있어요.\n· 일차 기사님: 오전 9시로 맞추면 '오전 9시 ~ 다음날 오전 9시'가 하루로 묶여요.\n· 야간 기사님: 오전 6시 등으로 맞추면 심야 운행이 한 '오늘'이 돼요.\n기본은 자정(0시)이에요.", fontSize = 12.sp, color = muted)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { dayStartHour = if (dayStartHour <= 0) 23 else dayStartHour - 1 }) { Text("−", color = accent, fontSize = 18.sp) }
                    Spacer(Modifier.width(16.dp))
                    Text(if (dayStartHour == 0) "자정 (0시)" else "${dayStartHour}시", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                    Spacer(Modifier.width(16.dp))
                    OutlinedButton(onClick = { dayStartHour = if (dayStartHour >= 23) 0 else dayStartHour + 1 }) { Text("+", color = accent, fontSize = 18.sp) }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "자정", 5 to "새벽5시", 6 to "오전6시", 9 to "오전9시").forEach { (h, lbl) ->
                        FilterChip(selected = dayStartHour == h, onClick = { dayStartHour = h }, label = { Text(lbl, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
                    }
                }
            } },
            confirmButton = { Button(onClick = { prefs.edit().putInt("day_start_hour", dayStartHour).apply(); showDayStartDlg = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showDayStartDlg = false }) { Text("취소") } }, containerColor = AppTheme.card)
    }

    if (showShareCfg) {
        var roomInput by remember { mutableStateOf(shareRoom) }
        var promoInput by remember { mutableStateOf(sharePromo) }
        AlertDialog(onDismissRequest = { showShareCfg = false },
            title = { Text("공유 설정", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("공유를 누르면 문구가 자동복사되고 이 오픈방이 바로 열려요 (방에서 꾹→붙여넣기)", fontSize = 12.sp, color = muted)
                OutlinedTextField(value = roomInput, onValueChange = { roomInput = it }, label = { Text("오픈방 주소 (open.kakao.com/...)", color = muted) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                OutlinedTextField(value = promoInput, onValueChange = { promoInput = it.take(60) }, label = { Text("홍보 문구/링크 (선택)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                Text("비워두면 공유 시 앱 선택창(카톡/밴드 등)이 떠요", fontSize = 11.sp, color = muted)
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
                            val conn = (URL("$SETTINGS_SERVER/api/pair/merge").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000 }
                            conn.outputStream.write(json.toString().toByteArray())
                            val rc = conn.responseCode
                            val body = (if (rc in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                            Pair(rc, body)
                        }
                        val j = try { JSONObject(resp.second) } catch (e: Exception) { JSONObject() }
                        if (resp.first in 200..299 && j.optString("primary_user_id", "").isNotEmpty()) {
                            prefs.edit().putString("user_id", j.optString("primary_user_id", "")).putString("nickname", j.optString("nickname", "기사님")).apply()
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
                            val conn = (URL("$SETTINGS_SERVER/api/devices/register").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000 }
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

    if (showCaptureConsent) {
        // [v18] 화면캡처 사전 고지(Play 정책: prominent disclosure). 캡처 전에 용도·범위를 명확히 안내.
        AlertDialog(onDismissRequest = { showCaptureConsent = false },
            title = { Text("📸 화면 스샷 공유", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column {
                Text("버튼을 누르면 지금 이 순간의 화면 1장을 캡처해 '📻 콜레이더' 워터마크를 붙여 공유해요.", fontSize = 13.sp, color = AppTheme.text)
                Spacer(Modifier.height(10.dp))
                Text("• 버튼을 누른 그 순간의 화면 한 장만 사용해요.", fontSize = 12.sp, color = muted, modifier = Modifier.padding(vertical = 1.dp))
                Text("• 백그라운드에서 화면을 관찰하거나 몰래 저장하지 않아요.", fontSize = 12.sp, color = muted, modifier = Modifier.padding(vertical = 1.dp))
                Text("• 캡처한 이미지는 공유가 끝나면 앱에 남기지 않아요.", fontSize = 12.sp, color = muted, modifier = Modifier.padding(vertical = 1.dp))
                Spacer(Modifier.height(8.dp))
                Text("계속하면 안드로이드 화면 공유 동의창이 한 번 떠요.", fontSize = 11.sp, color = accent)
            } },
            confirmButton = { Button(onClick = {
                showCaptureConsent = false
                try { com.callradar.app.ScreenCapturePermissionActivity.start(context) } catch (e: Exception) {}
            }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("캡처해서 공유", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showCaptureConsent = false }) { Text("취소") } },
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
private fun MoreGrid(groups: List<MoreGroup>, accent: Color, muted: Color, card: Color) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
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
private fun MoreList(groups: List<MoreGroup>, accent: Color, green: Color, red: Color, muted: Color, card: Color) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp)) {
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
        Triple("card_salary", "💰", "월급 명세서"),
        Triple("card_platform", "🏷️", "플랫폼별 매출"),
        Triple("work_session_enabled", "⏱", "근무 세션"),
        Triple("work_dist_enabled", "📏", "거리(km) 미터"),
        Triple("quick_entry_enabled", "💬", "완료 후 팝업"),
        Triple("card_notice", "📢", "제보 배너")
    )
    val state = remember { mutableStateMapOf<String, Boolean>().apply { items.forEach { put(it.first, prefs.getBoolean(it.first, true)) } } }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text("🧩 기능 등록소", fontSize = 15.sp, color = AppTheme.text, fontWeight = FontWeight.Bold)
            Text("안 쓰는 건 끄고, 필요한 것만 홈에 켜두세요 · 탭하면 바로 반영", fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
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
    var gasReduction by remember { mutableStateOf(prefs.getInt("gas_reduction", 9)) }
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
                    val conn = (URL("$SETTINGS_SERVER/api/driver-settings").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true }
                    conn.outputStream.write(json.toString().toByteArray()); conn.responseCode
                }
            } catch (e: Exception) { }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val resp = withContext(Dispatchers.IO) { val conn = (URL("$SETTINGS_SERVER/api/driver-settings/$userId").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }
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
            text = { Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        var reductionInput by remember { mutableStateOf(gasReduction.toString()) }
        var subsidyInput by remember { mutableStateOf(prefs.getInt("lpg_subsidy", 221).toString()) }   // [v5] 개인 유가보조금(원/L) 편집
        var gasMethod by remember { mutableStateOf(prefs.getString("gas_method", "rate") ?: "rate") }   // [v5] 법인: rate(경감률) | fixed(고정단가)
        var fixedInput by remember { mutableStateOf(prefs.getInt("gas_fixed", 0).toString()) }          // [v5] 법인 고정 차감단가(원/L)
        AlertDialog(onDismissRequest = { showLpgDialog = false },
            title = { Text("LPG 정산 설정", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = priceInput, onValueChange = { priceInput = it.filter { c -> c.isDigit() } }, label = { Text("LPG 단가 (원/L)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                OutlinedTextField(value = dailyLInput, onValueChange = { dailyLInput = it.filter { c -> c.isDigit() } }, label = { Text("일 평균 사용량 (L)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
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
                        OutlinedTextField(value = reductionInput, onValueChange = { v -> val f = v.filter { it.isDigit() }.take(3); if (f.isEmpty() || (f.toIntOrNull() ?: 0) <= 100) reductionInput = f }, label = { Text("가스 경감률 (%) — 회사 부가세 경감", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                        val redRate = reductionInput.toIntOrNull() ?: 0
                        val gross = price * liters
                        val net = (gross * (100 - redRate) / 100.0).toInt()
                        if (gross > 0) {
                            Card(colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(8.dp)) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("일 가스총액: ${String.format("%,d", gross)}원 (${price}×${liters}L)", fontSize = 12.sp, color = AppTheme.text)
                                    Text("경감 ${redRate}% 적용", fontSize = 12.sp, color = green)
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
                gasReduction = reductionInput.toIntOrNull() ?: 9
                val sub = subsidyInput.toIntOrNull() ?: 221
                val fixedV = fixedInput.toIntOrNull() ?: 0
                // [v16] 일 가스 실부담(원) 계산 → 홈 순수익이 읽는 단일 소스
                val dailyCost = if (driverType == "corporate") {
                    if (gasMethod == "fixed") fixedV * lpgDaily
                    else (lpgPrice.toLong() * lpgDaily * (100 - gasReduction) / 100).toInt()
                } else {
                    ((lpgPrice - sub) * lpgDaily).coerceAtLeast(0)
                }
                prefs.edit().putInt("lpg_price", lpgPrice).putInt("lpg_daily", lpgDaily).putInt("gas_reduction", gasReduction).putInt("lpg_subsidy", sub).putString("gas_method", gasMethod).putInt("gas_fixed", fixedV).putInt("lpg_daily_cost", dailyCost).apply()
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
            confirmButton = { Button(onClick = { prefs.edit().putString("driver_type", driverType).putString("affiliation", affiliation).putInt("work_days", workDays).apply(); saveSettingsToServer(); showTypeDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
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
                Column { Text("LPG 정산", fontSize = 14.sp, color = AppTheme.text); Text("단가·사용량·유가보조금", fontSize = 11.sp, color = muted) }
                Text(if (lpgPrice > 0) "${lpgPrice}원/L · ${lpgDaily}L" else "미설정", fontSize = 11.sp, color = accent)
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
                    val conn = (URL("$SETTINGS_SERVER/api/bookings/$id/status").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 8000 }
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
                val conn = (URL("$SETTINGS_SERVER/api/bookings/$userId").openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
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
                    val conn = (URL("$SETTINGS_SERVER/api/events?days=90").openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 8000; readTimeout = 8000 }
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
