// ===== HomeScreen v5 (2026-07-13) =====
// v5: 월누적 카드를 접기/펼치기 월급명세서로 개조 - 헤더에 예상 실수령 크게, 펼치면 계산내역
// v4: 만근일 버그수정(오늘날짜→설정값 work_days), 홈에 만근일 표시, 사납금=일사납×만근일
// v3: 자동 새로고침 - 화면복귀(ON_RESUME)시 즉시갱신 + 15초 백업. "운행끝나고 앱열면 바로 반영"
// v2: 월누적 카드 기사유형 분기 (법인=사납금 별도줄, 개인=사업지출만)
package com.callradar.app.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.callradar.app.WorkSessionService
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

private const val SERVER_URL = Config.SERVER_URL

data class Badge(val emoji: String, val name: String)
data class LevelInfo(val level: Int, val title: String, val next: Int)
data class HomeProfile(
    val nickname: String, val points: Int, val totalTrips: Int,
    val levelInfo: LevelInfo, val badges: List<Badge>,
    val guildName: String, val myRank: Int, val monthFare: Int,
    val carNumber: String, val employeeId: String, val workType: String,
    val driverType: String, val companyName: String,
    val monthCash: Int = 0, val monthCard: Int = 0, val monthTip: Int = 0
)
data class RecentTrip(val id: Int, val origin: String, val destination: String, val fare: Int, val platform: String, val time: String)
data class PlatformStat(val platform: String, val count: Int, val totalFare: Int)

// [v20] 홈 Tier0 이벤트 카드 — 서버 공식데이터(축제 등) 지역·카테고리 필터 요약 + AI 비서 게이트
@Composable
private fun EventHomeCard(prefs: android.content.SharedPreferences, refreshKey: Int, card: Color, accent: Color, muted: Color) {
    var events by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var regionCsv by remember { mutableStateOf(prefs.getString("event_regions", "") ?: "") }
    LaunchedEffect(refreshKey, regionCsv) {
        loading = true
        val regionList = ArrayList<JSONObject>(); val allList = ArrayList<JSONObject>()
        try {
            val json = withContext(Dispatchers.IO) {
                val conn = (URL("$SERVER_URL/api/events?days=45").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                conn.inputStream.bufferedReader().use { it.readText() }
            }
            val arr = JSONArray(json)
            val sel = regionCsv.split(",").filter { it.isNotBlank() }
            val off = (prefs.getString("event_off_cats", "") ?: "").split(",").filter { it.isNotBlank() }
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val cat = e.optString("category"); val area = e.optString("area")
                if (off.contains(cat)) continue
                allList.add(e)
                if (sel.isEmpty() || sel.any { area.contains(it) || it.contains(area) }) regionList.add(e)
            }
        } catch (e: Exception) { }
        events = if (regionList.isNotEmpty()) regionList else allList  // 지역에 없으면 전국 폴백
        loading = false
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("📅 내 지역 수요 정보", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(bottom = 6.dp))
            // [v21] 내 권역 선택 (서울 기사가 전남/울산 안 보게) — 저장·즉시 반영. 아무것도 안 켜면 전국.
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                val selSet = regionCsv.split(",").filter { it.isNotBlank() }.toSet()
                listOf("서울", "경기", "인천", "부산", "대구", "대전").forEach { r ->
                    FilterChip(selected = selSet.contains(r), onClick = {
                        val ns = if (selSet.contains(r)) selSet - r else selSet + r
                        regionCsv = ns.joinToString(","); prefs.edit().putString("event_regions", regionCsv).apply()
                    }, label = { Text(r, fontSize = 10.sp) }, modifier = Modifier.height(30.dp))
                }
            }
            if (loading) {
                Text("불러오는 중…", fontSize = 12.sp, color = muted)
            } else if (events.isEmpty()) {
                Text("표시할 이벤트가 없어요 (더보기 → 이벤트에서 설정)", fontSize = 12.sp, color = muted)
            } else {
                events.take(3).forEach { e ->
                    val title = e.optString("title"); val area = e.optString("area"); val start = e.optString("start_at").take(10)
                    val areaTxt = if (area.isNotBlank() && area != "null") area else ""
                    // [v21] 유형별 파장/입항 예상 시각 (크루즈=입항시각, 스포츠 3h, 공연 2.5h) → 그 시간 그 지역 콜↑
                    val cat = e.optString("category")
                    val durMin = when (cat) { "크루즈" -> 0; "야구", "스포츠" -> 180; else -> 150 }
                    val paLabel = if (cat == "크루즈") "입항" else "파장"
                    // [v22] start_at에 실제 공연 시각이 없고 날짜만(자정) 있으면, 유형별 통상 시작시각으로 대체 → 파장 현실화(구라 방지: ≈근사치)
                    val rawStart = e.optString("start_at")
                    val noRealTime = !rawStart.contains("T") || rawStart.substringAfter("T").startsWith("00:00")
                    val paTime = try {
                        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.KOREA); sdf.timeZone = TimeZone.getTimeZone("UTC")
                        val d = sdf.parse(rawStart.take(19))!!
                        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")); cal.time = d
                        if (noRealTime && cat != "크루즈") {
                            // 통상 공연 시작(KST): 야구/스포츠 18:30, 그 외(콘서트/뮤지컬/페스티벌/공연) 19:00
                            val (sh, sm) = if (cat in listOf("야구", "스포츠")) 18 to 30 else 19 to 0
                            cal.set(Calendar.HOUR_OF_DAY, sh); cal.set(Calendar.MINUTE, sm)
                        }
                        cal.add(Calendar.MINUTE, durMin)
                        SimpleDateFormat("HH:mm", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(cal.time)
                    } catch (ex: Exception) { "" }
                    val big = cat in listOf("야구", "콘서트", "스포츠", "크루즈")  // 규모 큰 게 확실한 것만(축제는 천차만별→표기 안 함, 구라 방지)
                    Text("• $title", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text, maxLines = 2)
                    Text("   $areaTxt · $start" + (if (paTime.isNotEmpty()) " · ≈$paLabel $paTime" else "") + (if (big) " · 대형(수천명↑) 콜↑" else ""), fontSize = 13.sp, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("🤖 AI 비서 준비 중 · 데이터가 쌓이면 맞춤 추천이 켜집니다", fontSize = 11.sp, color = muted)
        }
    }
}

// [v21] 홈 상단 오늘 브리핑 한 줄 (온/오프: card_brief). 매일 보이는 재방문 훅.
@Composable
private fun HomeBriefCard(refreshKey: Int, card: Color, accent: Color, muted: Color, onBrief: (String) -> Unit = {}) {
    var brief by remember { mutableStateOf("") }
    val briefPrefs = LocalContext.current.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    LaunchedEffect(refreshKey) {
        val cal = java.util.Calendar.getInstance()
        val today = listOf("일", "월", "화", "수", "목", "금", "토")[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        val hr = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val sb = StringBuilder("오늘은 ${today}요일")
        val (place, ev) = withContext(Dispatchers.IO) {
            var p = ""; var e = ""
            try {
                val d = JSONObject((URL("$SERVER_URL/api/demand?hour=$hr").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 7000; readTimeout = 7000 }.inputStream.bufferedReader().use { it.readText() })
                val rows = d.optJSONArray("rows"); if (rows != null && rows.length() > 0) p = rows.getJSONObject(0).optString("origin")
            } catch (_: Exception) {}
            try {
                val arr = JSONArray((URL("$SERVER_URL/api/events?days=2").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 7000; readTimeout = 7000 }.inputStream.bufferedReader().use { it.readText() })
                val sel = (briefPrefs.getString("event_regions", "") ?: "").split(",").filter { it.isNotBlank() }
                var i = 0
                while (i < arr.length()) { val o = arr.getJSONObject(i); val a = o.optString("area"); if (sel.isEmpty() || sel.any { a.contains(it) || it.contains(a) }) { val t = o.optString("title"); if (t.isNotBlank()) { e = (if (a.isNotBlank() && a != "null") "$a " else "") + t; break } }; i++ }
            } catch (_: Exception) {}
            Pair(p, e)
        }
        if (place.isNotBlank()) sb.append(" · 지금 ${place} 콜↑")
        if (ev.isNotBlank()) sb.append(" · ${ev} 수요예상")
        brief = sb.toString()
        onBrief(brief)
    }
    if (brief.isNotBlank()) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🔊", fontSize = 16.sp); Spacer(Modifier.width(8.dp))
                Text(brief, fontSize = 12.sp, color = AppTheme.text, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
fun HomeScreen(nickname: String, userId: String, refreshKey: Int, onLogout: () -> Unit, onOpenSettings: () -> Unit = {}, onNavTab: (Int) -> Unit = {}, onNavMore: (String) -> Unit = {}, onToggleFloating: (Boolean) -> Unit = {}, isOverlayGranted: () -> Boolean = { false }, onToggleNotifCapture: (Boolean) -> Unit = {}, isNotifAccessGranted: () -> Boolean = { false }) {
    val bg = AppTheme.bg; val card = AppTheme.card; val accent = Color(0xFFF59E0B)
    val green = Color(0xFF10B981); val red = Color(0xFFEF4444); val muted = Color(0xFF6B7280)
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    // [v21] 출근 시 브리핑 음성 낭독 (voice_on일 때만)
    var homeBrief by remember { mutableStateOf("") }
    var homeTts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { st -> if (st == TextToSpeech.SUCCESS) engine?.language = Locale.KOREAN }
        homeTts = engine
        onDispose { engine?.stop(); engine?.shutdown() }
    }
    var goalFare by remember { mutableStateOf(prefs.getInt("goal_fare", 300000)) }
    // [v21] 익명 사용성 텔레메트리 (자기진화 루프) — 옵트아웃 시 미전송
    LaunchedEffect(Unit) { com.callradar.app.Telemetry.log(context, "open_app", "home") }
    var salaryExpanded by remember { mutableStateOf(false) }  // [v5] 월급명세서 접기/펼치기
    var dailySanap by remember { mutableStateOf(prefs.getInt("daily_sanap", 0)) }
    val driverType = prefs.getString("driver_type", "personal") ?: "personal"
    val workDaysSetting = prefs.getInt("work_days", 26)
    val lpgDailyCost = prefs.getInt("lpg_daily_cost", 0)   // [v16] 설정에서 계산된 일 가스 실부담(원). 예전 lpg_daily(L) 오용 버그 수정.
    // [v17] daily_expense(일 고정지출) 제거 — 잡지출/영수증으로 일원화. 순수익 계산에서 뺌.
    val feePercent = prefs.getInt("fee_percent", 0)
    var profile by remember { mutableStateOf<HomeProfile?>(null) }
    var todayTrips by remember { mutableStateOf(0) }
    var todayFare by remember { mutableStateOf(0) }
    var noFareCount by remember { mutableStateOf(0) }   // [v23] 오늘 금액 미입력 운행 수 (조용한 안전망 배너)
    var recentTrips by remember { mutableStateOf<List<RecentTrip>>(emptyList()) }
    var platformStats by remember { mutableStateOf<List<PlatformStat>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var homeLoaded by remember { mutableStateOf(false) }   // [v22] 최초 로딩 완료 전엔 매출 카드에 스피너(0원/-사납금 깜빡임 방지)
    var showGoalDialog by remember { mutableStateOf(false) }
    var goalInput by remember { mutableStateOf("") }
    var sanapInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var businessExpense by remember { mutableStateOf(0) }
    var personalExpense by remember { mutableStateOf(0) }
    var miscExpense by remember { mutableStateOf(0) }
    var monthWorkedDays by remember { mutableStateOf(0) }   // [v19] 이번 달 실제 매출 있는 날 수 (사납금/가스 차감 기준)
    val scope = rememberCoroutineScope()

    fun calcNetIncome(fare: Int, days: Int, sanapPerDay: Int = dailySanap): Int {
        val sanap = sanapPerDay * days; val lpg = lpgDailyCost * days
        val fee = fare * feePercent / 100; return fare - sanap - lpg - fee
    }

    if (showGoalDialog) {
        AlertDialog(onDismissRequest = { showGoalDialog = false },
            title = { Text("목표 설정", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column {
                Text("하루 목표 매출", fontSize = 13.sp, color = muted, modifier = Modifier.padding(bottom = 4.dp))
                OutlinedTextField(value = goalInput, onValueChange = { goalInput = it.filter { c -> c.isDigit() } }, label = { Text("목표 금액 (원)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(200000, 300000, 400000, 500000).forEach { amount -> OutlinedButton(onClick = { goalInput = amount.toString() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text("${amount/10000}만", fontSize = 12.sp) } } }
                Spacer(Modifier.height(10.dp))
                Text("💡 사납금·가스·수수료는 상단 ⚙️기사 설정에서 설정해요", fontSize = 11.sp, color = muted)
            } },
            confirmButton = { Button(onClick = {
                val g = goalInput.toIntOrNull(); if (g != null && g > 0) goalFare = g
                prefs.edit().putInt("goal_fare", goalFare).apply()
                showGoalDialog = false
            }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showGoalDialog = false }) { Text("취소") } },
            containerColor = AppTheme.card)
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    fun loadHomeData() {
        if (userId.isEmpty()) { isLoading = false; return }
        scope.launch {
            try {
                // [v23] 계정 dayStart 동기화 — 서브폰도 같은 영업일 기준으로 오늘매출 계산되게(today 조회 전에 갱신)
                try { withContext(Dispatchers.IO) { val s = (URL("$SERVER_URL/api/user-settings/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 6000 }.inputStream.bufferedReader().readText(); val ds = JSONObject(s).optInt("day_start", prefs.getInt("day_start_hour", 0)); prefs.edit().putInt("day_start_hour", ds).apply() } } catch (e: Exception) {}
                val todayResponse = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/today/$userId?dayStart=${prefs.getInt("day_start_hour", 0)}").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000 }; conn.inputStream.bufferedReader().readText() }
                val todayJson = JSONObject(todayResponse)
                todayTrips = todayJson.optInt("tripCount", 0)
                todayFare = todayJson.optInt("todayFare", 0)
                noFareCount = todayJson.optInt("noFareCount", 0)

                val recentArr = todayJson.optJSONArray("recentTrips") ?: JSONArray()
                val rList = mutableListOf<RecentTrip>()
                for (i in 0 until recentArr.length()) {
                    val obj = recentArr.getJSONObject(i)
                    val rawTime = obj.optString("started_at", "")
                    val formattedTime = try { val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); sdf.timeZone = TimeZone.getTimeZone("UTC"); val date = sdf.parse(rawTime); val out = SimpleDateFormat("HH:mm", Locale.KOREA); out.timeZone = TimeZone.getTimeZone("Asia/Seoul"); out.format(date!!) } catch (e: Exception) { "" }
                    rList.add(RecentTrip(obj.getInt("id"), obj.optString("origin", ""), obj.optString("destination", ""), obj.optInt("fare", 0), obj.optString("platform", ""), formattedTime))
                }
                recentTrips = rList

                try {
                    val profResponse = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/profile/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }
                    val pJson = JSONObject(profResponse); val lJson = pJson.optJSONObject("level") ?: JSONObject()
                    val badgeArr = pJson.optJSONArray("badges") ?: JSONArray()
                    val bList = mutableListOf<Badge>(); for (i in 0 until badgeArr.length()) { val b = badgeArr.getJSONObject(i); bList.add(Badge(b.optString("emoji", "🏅"), b.optString("name", ""))) }
                    profile = HomeProfile(
                        pJson.optString("nickname", nickname), pJson.optInt("points", 0), pJson.optInt("totalTrips", 0),
                        LevelInfo(lJson.optInt("level", 1), lJson.optString("title", "신입 기사"), lJson.optInt("nextLevelTrips", 10)),
                        bList, pJson.optString("guildName", ""), pJson.optInt("myRank", 0), pJson.optInt("monthFare", 0),
                        pJson.optString("carNumber", ""), pJson.optString("employeeId", ""), pJson.optString("workType", ""),
                        pJson.optString("driverType", ""), pJson.optString("companyName", ""),
                        pJson.optInt("monthCash", 0), pJson.optInt("monthCard", 0), pJson.optInt("monthTip", 0)
                    )
                } catch (e: Exception) { }

                try {
                    val platResponse = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/stats/platform/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }
                    val platJson = JSONObject(platResponse)
                    val todayArr = platJson.getJSONArray("today")
                    val platList = mutableListOf<PlatformStat>()
                    for (i in 0 until todayArr.length()) {
                        val obj = todayArr.getJSONObject(i)
                        platList.add(PlatformStat(obj.getString("platform"), obj.getInt("count"), obj.getInt("total_fare")))
                    }
                    platformStats = platList
                } catch (e: Exception) { }

                try {
                    val ym = SimpleDateFormat("yyyy-MM", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date())
                    val expResponse = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/expenses/summary/$userId?month=$ym").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }
                    val expJson = JSONObject(expResponse)
                    businessExpense = expJson.optInt("business", 0)
                    personalExpense = expJson.optInt("personal", 0)
                    miscExpense = expJson.optInt("misc", 0)
                } catch (e: Exception) { }

                // [v19] 이번 달 실제 근무일 수(매출 있는 날) — 사납금/가스를 그만큼만 차감해 월초 과다 마이너스 방지
                try {
                    val ym2 = SimpleDateFormat("yyyy-MM", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date())
                    val dsh = prefs.getInt("day_start_hour", 0)
                    val dailyResp = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/stats/daily/$userId?month=$ym2&dayStart=$dsh").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }
                    val arr = JSONArray(dailyResp)
                    var cnt = 0
                    for (i in 0 until arr.length()) { if (arr.getJSONObject(i).optInt("total_fare", 0) > 0) cnt++ }
                    monthWorkedDays = cnt
                } catch (e: Exception) { }

                errorMessage = null
                isLoading = false; homeLoaded = true
            } catch (e: Exception) { errorMessage = "서버 연결 실패"; isLoading = false; homeLoaded = true }
        }
    }

    // 최초 로드 + refreshKey 변경 시
    LaunchedEffect(userId, refreshKey) { loadHomeData() }

    // 화면 복귀(ON_RESUME) 시 자동 갱신 - 탭 전환/네비 갔다 돌아올 때 "운행 끝나고 앱 열면 바로 반영"
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { loadHomeData() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 백업: 앱 켜둔 채 있을 때 15초마다 조용히 갱신
    LaunchedEffect(userId) {
        if (userId.isEmpty()) return@LaunchedEffect
        while (true) { kotlinx.coroutines.delay(15000L); loadHomeData() }
    }

    Column(modifier = Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState())) {
        // 헤더
        Row(modifier = Modifier.fillMaxWidth().background(card).padding(top = 48.dp, start = 20.dp, end = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text("콜레이더", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent); Text("β", fontSize = 11.sp, color = muted) }
                profile?.let { p ->
                    Text(buildString { append(p.nickname); if (p.carNumber.isNotEmpty()) append(" · ${p.carNumber}") }, fontSize = 12.sp, color = muted)
                } ?: Text("${nickname}님", fontSize = 12.sp, color = muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // [v19] 명함(단골 확보) + 기사 설정
                Card(modifier = Modifier.height(36.dp).clickable { com.callradar.app.NameCardActivity.start(context) }, colors = CardDefaults.cardColors(containerColor = Color(0xFF7C3AED)), shape = RoundedCornerShape(20.dp)) {
                    Box(modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) { Text("📇 명함", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                }
                Card(modifier = Modifier.height(36.dp).clickable { onOpenSettings() }, colors = CardDefaults.cardColors(containerColor = Color(0xFF374151)), shape = RoundedCornerShape(20.dp)) {
                    Box(modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) { Text("⚙️ 기사 설정", fontSize = 12.sp, color = AppTheme.text, fontWeight = FontWeight.Bold) }
                }
            }
        }

        // 에러 메시지
        if (errorMessage != null) {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)), shape = RoundedCornerShape(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(errorMessage!!, fontSize = 12.sp, color = AppTheme.text)
                    TextButton(onClick = { errorMessage = null; isLoading = true }) { Text("재시도", color = accent, fontSize = 12.sp) }
                }
            }
        }

        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 월 누적 + 지출 + 순수익
            val cal = Calendar.getInstance(); val month = cal.get(Calendar.MONTH) + 1
            val monthFare = profile?.monthFare ?: 0
            val monthCash = profile?.monthCash ?: 0
            val monthTip = profile?.monthTip ?: 0
            val monthCard = (profile?.monthCard ?: 0).let { if (it > 0) it else monthFare - monthCash }  // 구버전 서버 대비 폴백
            val cashToCompany = prefs.getBoolean("cash_to_company", false)
            val annualLeave = prefs.getInt("annual_leave", 0)
            val workDays = workDaysSetting  // 설정한 만근일 (25/26 등), 오늘날짜 아님
            val isCorporate = driverType == "corporate"
            // 사납금 = 일사납금 × (만근일 − 연차)
            val sanapDays = (workDays - annualLeave).coerceAtLeast(0)
            // [v19] 실제 매출 있는 날 수만큼만 사납금·가스 차감 (월초/데이터 적을 때 과다 마이너스 방지). 만근일 상한.
            val billDays = if (monthWorkedDays > 0) monthWorkedDays.coerceAtMost(sanapDays) else 0
            val sanapTotal = dailySanap * billDays
            val lpgMonthly = lpgDailyCost * billDays
            // 회사 정산 기준 매출: 현금 납부하면 전체, 아니면 카드/플랫폼만
            val companyRevenue = if (cashToCompany) monthFare else monthCard
            val netIncome = (if (isCorporate) companyRevenue - sanapTotal else monthFare) - lpgMonthly - businessExpense
            // [v19] 급여명세서 기반 월 고정 공제 (기본급 +, 4대보험/조합비/기타공제 −) — 법인만
            // payZeroNet: 실급여 0(기본급 명목상·도급/전차금제) → 명세서는 홈 실수령에 0 기여, 도급 초과수익만 반영
            val payZeroNet = prefs.getBoolean("pay_zero_net", false)
            val payActive = isCorporate && !payZeroNet
            val payBase = if (payActive) prefs.getInt("pay_base", 0) else 0
            val payIns = if (payActive) prefs.getInt("pay_insurance", 0) else 0
            val payUnion = if (payActive) prefs.getInt("pay_union", 0) else 0
            val payOther = if (payActive) prefs.getInt("pay_other_deduct", 0) else 0
            val payNominalBase = if (isCorporate && payZeroNet) prefs.getInt("pay_base", 0) else 0  // 명목 기본급(표시용)
            // 기사 실수령: 현금 미납부면 발생액 + 현금(내 몫) + 기본급 − 4대보험 − 조합비 − 기타공제 (실급여0이면 명세서 0 기여)
            val takeHome = (if (isCorporate && !cashToCompany) netIncome + monthCash else netIncome) + monthTip - personalExpense - miscExpense + payBase - payIns - payUnion - payOther
            // [v21 재설계] 홈에서 계산된 실수령·월매출을 캐시 → 기록 '월급' 탭이 동일값 표시 (계산 로직 무손상)
            LaunchedEffect(takeHome, netIncome, monthFare, month) {
                prefs.edit()
                    .putInt("cached_takehome", takeHome)
                    .putInt("cached_net_income", netIncome)
                    .putInt("cached_month_fare", monthFare)
                    .putInt("cached_takehome_month", month)
                    .putBoolean("cached_is_corporate", isCorporate)
                    .apply()
            }
            // [v21 재설계] 오늘 매출 — 홈 최상단(오늘 중심)
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
                if (!homeLoaded) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = accent, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("오늘 매출 불러오는 중…", fontSize = 12.sp, color = muted)
                        }
                    }
                } else
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("오늘 매출", fontSize = 12.sp, color = muted)
                            Text(if (todayFare > 0) "${String.format("%,d", todayFare)}원" else "0원", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = if (todayFare > 0) green else muted)
                            if (dailySanap > 0 || lpgDailyCost > 0) {
                                val sanapDaysQuota = (workDaysSetting - prefs.getInt("annual_leave", 0)).coerceAtLeast(0)
                                val owedSanap = dailySanap * sanapDaysQuota
                                val sanapMet = driverType == "corporate" && dailySanap > 0 && sanapDaysQuota > 0 && owedSanap > 0 &&
                                    (monthWorkedDays > sanapDaysQuota || (profile?.monthFare ?: 0) >= owedSanap)
                                val todayNet = calcNetIncome(todayFare, 1, if (sanapMet) 0 else dailySanap)
                                Text((if (sanapMet) "순수익 " else "순수익 ") + "${String.format("%,d", todayNet)}원" + (if (sanapMet) " (사납금 완납)" else ""), fontSize = 11.sp, color = if (todayNet > 0) accent else red)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            val progress = (todayFare.toFloat() / goalFare.toFloat()).coerceIn(0f, 1f)
                            Text("${(progress * 100).toInt()}%", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (progress >= 1f) green else accent)
                            TextButton(onClick = { goalInput = goalFare.toString(); sanapInput = dailySanap.toString(); showGoalDialog = true }, contentPadding = PaddingValues(0.dp)) { Text("목표 ${String.format("%,d", goalFare)}원 ✏️", fontSize = 11.sp, color = muted) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val progress = (todayFare.toFloat() / goalFare.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp), color = if (progress >= 1f) green else accent, trackColor = AppTheme.surface2)
                    Spacer(Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$todayTrips", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppTheme.text); Text("오늘 콜", fontSize = 11.sp, color = muted) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { val avg = if (todayTrips > 0) todayFare / todayTrips else 0; Text("${String.format("%,d", avg)}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppTheme.text); Text("콜 평균", fontSize = 11.sp, color = muted) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(if (todayFare >= goalFare) "달성!" else "${String.format("%,d", goalFare - todayFare)}", fontSize = if (todayFare >= goalFare) 18.sp else 20.sp, fontWeight = FontWeight.Bold, color = if (todayFare >= goalFare) green else AppTheme.text); Text(if (todayFare >= goalFare) "목표완료" else "남은금액", fontSize = 11.sp, color = muted) }
                    }
                }
            }

            // 💰 예상 월급/순수익
            if (prefs.getBoolean("card_salary", true))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    // 헤더: 항상 보이는 예상 실수령 (접기 토글)
                    Row(modifier = Modifier.fillMaxWidth().clickable { salaryExpanded = !salaryExpanded }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("\uD83D\uDCB0 ${month}월 ${if (isCorporate) "예상 월급 수령액" else "예상 순수익"}", fontSize = 12.sp, color = muted)
                            Text("${String.format("%,d", takeHome)}원", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (takeHome > 0) green else red)
                        }
                        Text(if (salaryExpanded) "\u25B2" else "\u25BC", fontSize = 14.sp, color = muted)
                    }
                    // 펼침: 계산 내역
                    androidx.compose.animation.AnimatedVisibility(visible = salaryExpanded) {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            HorizontalDivider(color = Color(0xFF374151))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (isCorporate) "카드/플랫폼 매출" else "매출", fontSize = 12.sp, color = muted)
                                Text("${String.format("%,d", if (isCorporate && !cashToCompany) monthCard else monthFare)}원", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = green)
                            }
                            if (isCorporate && sanapTotal > 0) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("사납금 (${String.format("%,d", dailySanap)} × ${billDays}일)", fontSize = 12.sp, color = muted)
                                    Text("-${String.format("%,d", sanapTotal)}원", fontSize = 14.sp, color = red)
                                }
                            }
                            if (lpgMonthly > 0) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("가스비 (설정 · ${billDays}일)", fontSize = 12.sp, color = muted)
                                    Text("-${String.format("%,d", lpgMonthly)}원", fontSize = 14.sp, color = red)
                                }
                            }
                            if (businessExpense > 0) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("사업지출(기타)", fontSize = 12.sp, color = muted)
                                    Text("-${String.format("%,d", businessExpense)}원", fontSize = 14.sp, color = red)
                                }
                            }
                            HorizontalDivider(color = Color(0xFF374151))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (isCorporate) "회사 지급액" else "순수익", fontSize = 12.sp, color = muted)
                                Text("${String.format("%,d", netIncome)}원", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (netIncome > 0) accent else red)
                            }
                            if (isCorporate && !cashToCompany && monthCash > 0) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("+ 현금(내 몫)", fontSize = 12.sp, color = muted)
                                    Text("+${String.format("%,d", monthCash)}원", fontSize = 14.sp, color = Color(0xFFFBBF24))
                                }
                            }
                            if (monthTip > 0) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("+ 팁", fontSize = 12.sp, color = muted)
                                    Text("+${String.format("%,d", monthTip)}원", fontSize = 14.sp, color = Color(0xFFFBBF24))
                                }
                            }
                            if (miscExpense > 0) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("잡지출", fontSize = 11.sp, color = muted)
                                    Text("-${String.format("%,d", miscExpense)}원", fontSize = 12.sp, color = Color(0xFF8B5CF6))
                                }
                            }
                            if (personalExpense > 0) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("개인지출", fontSize = 11.sp, color = muted)
                                    Text("-${String.format("%,d", personalExpense)}원", fontSize = 12.sp, color = Color(0xFFF97316))
                                }
                            }
                            // [v19] 실급여 0: 명세서 기본급은 명목상 → 홈 실수령에 반영 안 함(안내만)
                            if (payNominalBase > 0) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("회사 월급(명목)", fontSize = 11.sp, color = muted)
                                    Text("실지급 0", fontSize = 12.sp, color = muted)
                                }
                            }
                            // [v19] 급여명세서 공제 (법인·명세서 입력 시)
                            if (payBase > 0) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("+ 기본급", fontSize = 11.sp, color = muted)
                                    Text("+${String.format("%,d", payBase)}원", fontSize = 12.sp, color = green)
                                }
                            }
                            if (payIns > 0) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("4대보험", fontSize = 11.sp, color = muted)
                                    Text("-${String.format("%,d", payIns)}원", fontSize = 12.sp, color = red)
                                }
                            }
                            if (payUnion > 0) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("조합비", fontSize = 11.sp, color = muted)
                                    Text("-${String.format("%,d", payUnion)}원", fontSize = 12.sp, color = red)
                                }
                            }
                            if (payOther > 0) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("기타공제", fontSize = 11.sp, color = muted)
                                    Text("-${String.format("%,d", payOther)}원", fontSize = 12.sp, color = red)
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("실수령", fontSize = 12.sp, color = muted, fontWeight = FontWeight.Bold)
                                Text("${String.format("%,d", takeHome)}원", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (takeHome > 0) green else red)
                            }
                            Text("\uD83D\uDCA1 설정에서 사납금·가스단가·연차를 조정하면 더 정확해져요", fontSize = 10.sp, color = muted, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            // [v23] 홈 상단 — 운행 자동 기록(플로팅) 토글. "버튼만 누르면 자동" 약속 실현 + 첫 운행 넛지(활성화)
            run {
                var floatingOn by remember(refreshKey) { mutableStateOf(prefs.getBoolean("floating_on", false)) }
                Card(colors = CardDefaults.cardColors(containerColor = if (floatingOn) green.copy(alpha = 0.14f) else AppTheme.card), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (floatingOn) "🟢 운행 기록 버튼 켜짐" else "🚕 운행 기록 버튼", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                            Text(if (floatingOn) "시작 한 번 · 내릴 때 금액 한 번 — 두 번이면 기록 끝" else "시작 버튼 한 번, 내릴 때 금액 한 번이면 기록 끝. 첫 운행부터 켜보세요.", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
                        }
                        Switch(checked = floatingOn, onCheckedChange = { on ->
                            onToggleFloating(on)
                            floatingOn = if (on) isOverlayGranted() else false  // 권한 없으면 설정창 열리고 아직 꺼짐 유지
                            com.callradar.app.Telemetry.log(context, if (on) "floating_on" else "floating_off", "home")
                        })
                    }
                }
            }

            // [v23] 금액 자동 입력(알림 캡처) — "손 안 가는 앱"의 핵심. 자동화 차수(phase-2)에서만 노출(첫 심사 리스크 회피).
            if (Config.NOTIF_CAPTURE_ENABLED) run {
                var capOn by remember(refreshKey) { mutableStateOf(prefs.getBoolean("notif_capture_on", false) && isNotifAccessGranted()) }
                Card(colors = CardDefaults.cardColors(containerColor = if (capOn) green.copy(alpha = 0.14f) else AppTheme.card), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (capOn) "💰 금액 자동 입력 켜짐" else "💰 금액 자동 입력 (베타)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                            Text(if (capOn) "카카오T·우버 완료 알림에서 금액이 자동 입력돼요" else "카카오T·우버 알림을 읽어 금액을 자동 입력 — 손 안 가게. (알림 접근 권한 필요)", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
                        }
                        Switch(checked = capOn, onCheckedChange = { on ->
                            prefs.edit().putBoolean("notif_capture_on", on).apply()
                            onToggleNotifCapture(on)
                            capOn = if (on) isNotifAccessGranted() else false
                            com.callradar.app.Telemetry.log(context, if (on) "notif_capture_on" else "notif_capture_off", "home")
                        })
                    }
                }
            }

            // [v23] 조용한 안전망 — 오늘 금액 미입력 운행이 있으면 살짝만 알림(조르지 않음, 한 번 탭으로 채우러 이동)
            if (noFareCount > 0) {
                Card(colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.12f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { onNavTab(2) }) {
                    Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🧾", fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Text("금액 안 넣은 운행 ${noFareCount}건 있어요", fontSize = 13.sp, color = AppTheme.text, modifier = Modifier.weight(1f))
                        Text("채우기 ›", fontSize = 13.sp, color = accent, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // [v18] 근무 세션 (핸들링 스타일: 시간 카운팅 + 일시정지/재개 + 시간당 매출) — 순수 prefs, 앱 죽어도 이어짐
            run {
                val sessionEnabled = prefs.getBoolean("work_session_enabled", true)
                var workStart by remember { mutableStateOf(prefs.getLong("work_start", 0L)) }
                var pausedTotal by remember { mutableStateOf(prefs.getLong("work_paused_total", 0L)) }
                var pauseStart by remember { mutableStateOf(prefs.getLong("work_pause_start", 0L)) }
                var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
                var workDist by remember { mutableStateOf(prefs.getFloat("work_distance_m", 0f)) }
                val distEnabled = prefs.getBoolean("work_dist_enabled", true)
                var maxHours by remember { mutableStateOf(prefs.getInt("work_max_hours", 0)) }  // [v23] 근무 최대시간 자동마감(0=끔)
                val active = workStart > 0L
                val paused = pauseStart > 0L
                // [v2] 투폰 근무세션 동기화 — 로컬 변경을 서버로 push (출근/일시정지/재개/퇴근 때 호출)
                fun pushWorkSession(ws: Long, pt: Long, ps: Long, sf: Int) {
                    if (userId.isEmpty()) return
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val json = JSONObject().apply { put("user_id", userId); put("work_start", ws); put("paused_total", pt); put("pause_start", ps); put("start_fare", sf) }
                                val conn = (URL("$SERVER_URL/api/work-session").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 8000 }
                                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }; conn.responseCode
                            }
                        } catch (e: Exception) {}
                    }
                }
                // [v17][#5] 퇴근 요약 카드
                var showEndSummary by remember { mutableStateOf(false) }
                var showEndConfirm by remember { mutableStateOf(false) }   // [v23] 실수 퇴근 방지 확인
                var sumGrossMin by remember { mutableStateOf(0L) }
                var sumNetMin by remember { mutableStateOf(0L) }
                var sumDistKm by remember { mutableStateOf(0f) }
                var sumFare by remember { mutableStateOf(0) }
                var sumPerHour by remember { mutableStateOf(0) }
                var sumFixedCost by remember { mutableStateOf(0) }   // [v24 진화②] 일 유류비+사납금
                var sumNetProfit by remember { mutableStateOf(0) }   // [v24 진화②] 교대 예상 순수익
                fun startMeter() { try { ContextCompat.startForegroundService(context, Intent(context, WorkSessionService::class.java)) } catch (e: Exception) {} }
                fun stopMeter() { try { context.stopService(Intent(context, WorkSessionService::class.java)) } catch (e: Exception) {} }
                // [v23] 퇴근 실행(확인 후 호출). 이어가기용으로 직전 세션 스냅샷도 저장.
                val endShiftNow = {
                    val now = System.currentTimeMillis()
                    val netMs = ((now - workStart) - pausedTotal - (if (paused) now - pauseStart else 0L)).coerceAtLeast(0L)
                    val grossMs = (now - workStart).coerceAtLeast(0L)
                    val startFare = prefs.getInt("work_start_fare", 0)
                    val sFare = (todayFare - startFare).coerceAtLeast(0)
                    val hrs = netMs / 3600000.0
                    sumGrossMin = grossMs / 60000L
                    sumNetMin = netMs / 60000L
                    sumDistKm = prefs.getFloat("work_distance_m", 0f) / 1000f
                    sumFare = sFare
                    sumPerHour = if (hrs > 0.05) (sFare / hrs).toInt() else 0
                    // [v24 진화②] 교대별 손익 — 일 유류비+사납금 빼고 예상 순수익
                    sumFixedCost = prefs.getInt("lpg_daily_cost", 0) + prefs.getInt("daily_sanap", 0)
                    sumNetProfit = (sFare - sumFixedCost).coerceAtLeast(0)
                    com.callradar.app.TimingLog.send(context, "shift_end", amount = sFare)
                    try {
                        val log = try { JSONArray(prefs.getString("work_session_log", "[]")) } catch (e: Exception) { JSONArray() }
                        log.put(JSONObject().apply { put("end", now); put("grossMin", sumGrossMin); put("netMin", sumNetMin); put("distKm", sumDistKm.toDouble()); put("fare", sFare); put("perHour", sumPerHour) })
                        val trimmed = if (log.length() > 90) JSONArray().also { for (i in log.length() - 90 until log.length()) it.put(log.get(i)) } else log
                        prefs.edit().putString("work_session_log", trimmed.toString()).apply()
                    } catch (e: Exception) {}
                    // 이어가기용 스냅샷 저장(잘못 퇴근 시 복구)
                    prefs.edit().putLong("last_work_start", workStart).putLong("last_work_paused_total", pausedTotal).putLong("last_work_end", now).apply()
                    workStart = 0L; pausedTotal = 0L; pauseStart = 0L
                    prefs.edit().putLong("work_start", 0L).putLong("work_paused_total", 0L).putLong("work_pause_start", 0L).apply()
                    pushWorkSession(0L, 0L, 0L, 0)
                    stopMeter()
                    com.callradar.app.WorkAutoEnd.cancel(context)
                    com.callradar.app.Telemetry.log(context, "shift_end", "home", meta = sumFare.toString())
                    showEndSummary = true
                }
                LaunchedEffect(Unit) { if (workStart > 0L) com.callradar.app.WorkAutoEnd.schedule(context, workStart, prefs.getInt("work_max_hours", 0)) }  // [v23] 앱 재시작 시 예약 복원
                LaunchedEffect(active, paused) {
                    while (active && !paused) { nowTick = System.currentTimeMillis(); workDist = prefs.getFloat("work_distance_m", 0f); kotlinx.coroutines.delay(1000) }
                }
                // [v2] 투폰 근무세션 pull — 20초마다 서버 세션을 확인해 다른 폰의 출근/일시정지/퇴근을 반영
                LaunchedEffect(Unit) {
                    if (userId.isEmpty()) return@LaunchedEffect
                    while (true) {
                        try {
                            val o = withContext(Dispatchers.IO) { JSONObject((URL("$SERVER_URL/api/work-session/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 15000 }.inputStream.bufferedReader().readText()) }
                            val rws = o.optLong("work_start", workStart); val rpt = o.optLong("paused_total", pausedTotal); val rps = o.optLong("pause_start", pauseStart)
                            if (rws != workStart || rpt != pausedTotal || rps != pauseStart) {
                                workStart = rws; pausedTotal = rpt; pauseStart = rps; nowTick = System.currentTimeMillis()
                                prefs.edit().putLong("work_start", rws).putLong("work_paused_total", rpt).putLong("work_pause_start", rps).apply()
                                if (rws > 0L && rps == 0L && distEnabled) startMeter() else if (rws == 0L) stopMeter()
                                if (rws > 0L) com.callradar.app.WorkAutoEnd.schedule(context, rws, maxHours) else com.callradar.app.WorkAutoEnd.cancel(context)
                            }
                        } catch (e: Exception) {}
                        kotlinx.coroutines.delay(20000)
                    }
                }
                // [v23] 퇴근 확인 — 실수로 눌러 세션이 초기화되는 것 방지
                if (showEndConfirm) {
                    val nowC = System.currentTimeMillis()
                    val netMsC = ((nowC - workStart) - pausedTotal - (if (paused) nowC - pauseStart else 0L)).coerceAtLeast(0L)
                    val hC = netMsC / 3600000L; val mC = (netMsC / 60000L) % 60
                    AlertDialog(
                        onDismissRequest = { showEndConfirm = false },
                        title = { Text("퇴근할까요?", color = AppTheme.text, fontWeight = FontWeight.Bold) },
                        text = { Text("지금까지 근무 ${hC}시간 ${mC}분. 퇴근하면 이 세션이 끝나고 시간·평균이 초기화돼요. 실수로 누른 거면 '계속 근무'를 누르세요.", fontSize = 13.sp, color = muted) },
                        confirmButton = { Button(onClick = { showEndConfirm = false; endShiftNow() }, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("🔴 퇴근", color = Color.White, fontWeight = FontWeight.Bold) } },
                        dismissButton = { OutlinedButton(onClick = { showEndConfirm = false }) { Text("계속 근무") } },
                        containerColor = AppTheme.card
                    )
                }
                // [v17][#5] 퇴근 요약 — [v2] 영수증 스타일 카드 + 공유
                if (showEndSummary) {
                    val gh = sumGrossMin / 60; val gm = sumGrossMin % 60
                    val nh = sumNetMin / 60; val nm = sumNetMin % 60
                    val mono = androidx.compose.ui.text.font.FontFamily.Monospace
                    val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd (E)", java.util.Locale.KOREA).format(java.util.Date())
                    val dash = "─".repeat(22)
                    val receiptText = buildString {
                        append("📻 콜레이더 · 근무 영수증\n"); append("$dash\n")
                        append("날짜   $dateStr\n")
                        append("근무   ${gh}시간 ${gm}분 (순 ${nh}:${String.format("%02d", nm)})\n")
                        append("거리   ${String.format("%.1f", sumDistKm)} km\n")
                        append("매출   ${String.format("%,d", sumFare)}원\n")
                        append("시간당 ${String.format("%,d", sumPerHour)}원\n")
                        if (sumFixedCost > 0) { append("고정비(유류+사납) -${String.format("%,d", sumFixedCost)}원\n"); append("예상 순수익 ${String.format("%,d", sumNetProfit)}원\n") }
                        append("$dash\n수고하셨습니다!")
                    }
                    AlertDialog(
                        onDismissRequest = { showEndSummary = false },
                        title = { Text("🧾 근무 영수증", color = AppTheme.text, fontWeight = FontWeight.Bold) },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth().background(AppTheme.surface2, RoundedCornerShape(10.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("📻 콜레이더 · 근무 영수증", fontSize = 13.sp, fontFamily = mono, fontWeight = FontWeight.Bold, color = AppTheme.text)
                                Text(dateStr, fontSize = 11.sp, fontFamily = mono, color = muted)
                                Text(dash, fontSize = 11.sp, fontFamily = mono, color = muted)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("총 근무", fontSize = 13.sp, fontFamily = mono, color = muted); Text("${gh}시간 ${gm}분", fontSize = 13.sp, fontFamily = mono, fontWeight = FontWeight.Bold, color = AppTheme.text) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("순 근무", fontSize = 13.sp, fontFamily = mono, color = muted); Text("${nh}시간 ${nm}분", fontSize = 13.sp, fontFamily = mono, fontWeight = FontWeight.Bold, color = AppTheme.text) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("이동 거리", fontSize = 13.sp, fontFamily = mono, color = muted); Text(String.format("%.1f km", sumDistKm), fontSize = 13.sp, fontFamily = mono, fontWeight = FontWeight.Bold, color = AppTheme.text) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("운행 매출", fontSize = 13.sp, fontFamily = mono, color = muted); Text("${String.format("%,d", sumFare)}원", fontSize = 13.sp, fontFamily = mono, fontWeight = FontWeight.Bold, color = green) }
                                Text(dash, fontSize = 11.sp, fontFamily = mono, color = muted)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("시간당", fontSize = 13.sp, fontFamily = mono, color = muted); Text("${String.format("%,d", sumPerHour)}원", fontSize = 17.sp, fontFamily = mono, fontWeight = FontWeight.Bold, color = accent) }
                                if (sumFixedCost > 0) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("고정비(유류+사납)", fontSize = 12.sp, fontFamily = mono, color = muted); Text("-${String.format("%,d", sumFixedCost)}원", fontSize = 12.sp, fontFamily = mono, color = red) }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("예상 순수익", fontSize = 13.sp, fontFamily = mono, color = muted); Text("${String.format("%,d", sumNetProfit)}원", fontSize = 16.sp, fontFamily = mono, fontWeight = FontWeight.Bold, color = green) }
                                }
                                Text("수고하셨습니다!", fontSize = 11.sp, fontFamily = mono, color = muted, modifier = Modifier.padding(top = 2.dp))
                            }
                        },
                        confirmButton = { Button(onClick = { showEndSummary = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("확인", color = Color.Black) } },
                        dismissButton = { OutlinedButton(onClick = {
                            try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, receiptText) }, "영수증 공유")) } catch (e: Exception) {}
                        }) { Text("📤 공유") } },
                        containerColor = card
                    )
                }
                if (sessionEnabled) {
                    val workedMs = if (!active) 0L else ((nowTick - workStart) - pausedTotal - (if (paused) nowTick - pauseStart else 0L)).coerceAtLeast(0L)
                    val workedMin = workedMs / 60000L
                    val hh = workedMin / 60; val mm = workedMin % 60
                    val workedHours = workedMs.toDouble() / 3600000.0
                    val perHour = if (workedHours > 0.05) (todayFare / workedHours).toInt() else 0
                    val distKm = workDist / 1000f
                    val perKm = if (distKm > 0.3f) (todayFare / distKm).toInt() else 0
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(if (!active) "근무 세션" else if (paused) "근무 일시정지 ⏸" else "근무 중 🟢", fontSize = 12.sp, color = muted)
                                    Text(if (!active) "출근을 눌러 시작" else String.format("%d시간 %02d분", hh, mm), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (!active) muted else AppTheme.text)
                                }
                                if (active) Column(horizontalAlignment = Alignment.End) {
                                    Text("시간당 매출", fontSize = 11.sp, color = muted)
                                    Text("${String.format("%,d", perHour)}원", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent)
                                }
                            }
                            if (active && distEnabled) {
                                Spacer(Modifier.height(10.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(modifier = Modifier.weight(1f).background(AppTheme.surface2, RoundedCornerShape(10.dp)).padding(vertical = 8.dp, horizontal = 10.dp)) {
                                        Column {
                                            Text("이동 거리", fontSize = 10.sp, color = muted)
                                            Text(String.format("%.1f km", distKm), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                                        }
                                    }
                                    Box(modifier = Modifier.weight(1f).background(AppTheme.surface2, RoundedCornerShape(10.dp)).padding(vertical = 8.dp, horizontal = 10.dp)) {
                                        Column {
                                            Text("km당 매출", fontSize = 10.sp, color = muted)
                                            Text(if (perKm > 0) "${String.format("%,d", perKm)}원" else "—", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = green)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            // [v23] 근무시간 자동마감 설정 — 탭하면 프리셋 순환(꺼짐/10/12/15/18/24시간)
                            run {
                                val presets = listOf(0, 10, 12, 15, 18, 24)
                                Row(modifier = Modifier.fillMaxWidth().background(AppTheme.surface2, RoundedCornerShape(10.dp)).clickable {
                                    val idx = presets.indexOf(maxHours).let { if (it < 0) 0 else it }
                                    val nv = presets[(idx + 1) % presets.size]
                                    maxHours = nv; prefs.edit().putInt("work_max_hours", nv).apply()
                                    if (active) { if (nv > 0) com.callradar.app.WorkAutoEnd.schedule(context, workStart, nv) else com.callradar.app.WorkAutoEnd.cancel(context) }
                                }.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("🛌 근무시간 자동마감(깜빡 방지)", fontSize = 12.sp, color = muted)
                                    Text(if (maxHours > 0) "${maxHours}시간 후" else "꺼짐 · 탭해서 설정", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (maxHours > 0) accent else muted)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            if (!active) {
                                Button(onClick = { val t = System.currentTimeMillis(); workStart = t; pausedTotal = 0L; pauseStart = 0L; nowTick = t; workDist = 0f; prefs.edit().putLong("work_start", t).putLong("work_paused_total", 0L).putLong("work_pause_start", 0L).putFloat("work_distance_m", 0f).putInt("work_start_fare", todayFare).apply(); pushWorkSession(t, 0L, 0L, todayFare); com.callradar.app.Telemetry.log(context, "shift_start", "home"); com.callradar.app.WorkAutoEnd.schedule(context, t, maxHours); com.callradar.app.TimingLog.send(context, "shift_start"); if (distEnabled) startMeter(); if (prefs.getBoolean("voice_on", false) && homeBrief.isNotBlank()) homeTts?.speak(homeBrief, TextToSpeech.QUEUE_FLUSH, null, "brief") }, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = green), shape = RoundedCornerShape(12.dp)) { Text("🟢 출근 (근무 시작)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(onClick = {
                                        val t = System.currentTimeMillis()
                                        if (paused) { pausedTotal += (t - pauseStart); pauseStart = 0L; nowTick = t; prefs.edit().putLong("work_paused_total", pausedTotal).putLong("work_pause_start", 0L).apply(); pushWorkSession(workStart, pausedTotal, 0L, prefs.getInt("work_start_fare", 0)); if (distEnabled) startMeter() }
                                        else { pauseStart = t; prefs.edit().putLong("work_pause_start", t).apply(); pushWorkSession(workStart, pausedTotal, t, prefs.getInt("work_start_fare", 0)); stopMeter() }
                                    }, modifier = Modifier.weight(1f).height(46.dp), shape = RoundedCornerShape(10.dp)) { Text(if (paused) "▶ 재개" else "⏸ 일시정지", color = accent, fontWeight = FontWeight.Bold) }
                                    Button(onClick = { showEndConfirm = true }, modifier = Modifier.weight(1f).height(46.dp), colors = ButtonDefaults.buttonColors(containerColor = red), shape = RoundedCornerShape(10.dp)) { Text("🔴 퇴근", color = Color.White, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                    }
                }
            }

            // [v21] 오늘 브리핑 한 줄 (온/오프: card_brief)
            if (prefs.getBoolean("card_brief", true)) {
                HomeBriefCard(refreshKey = refreshKey, card = card, accent = accent, muted = muted, onBrief = { homeBrief = it })
            }

            // [v20] 내 지역 수요 정보 (Tier0 공식데이터 + AI 비서 게이트)
            EventHomeCard(prefs = prefs, refreshKey = refreshKey, card = card, accent = accent, muted = muted)

            // 플랫폼별 매출
            if (prefs.getBoolean("card_platform", false) && platformStats.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("플랫폼별 매출", fontSize = 12.sp, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                        val feeKakao = feeRateFloat(prefs, "fee_kakao")   // [v17] 소수점 수수료
                        val feeUber = feeRateFloat(prefs, "fee_uber")
                        val feeTmoney = feeRateFloat(prefs, "fee_tmoney")
                        platformStats.forEach { stat ->
                            val feeRate = when { stat.platform.contains("카카오") -> feeKakao; stat.platform.contains("우버") -> feeUber; stat.platform.contains("티머니") -> feeTmoney; else -> 0f }
                            val netFare = (stat.totalFare * (100.0 - feeRate) / 100.0).toInt()
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stat.platform, fontSize = 13.sp, color = AppTheme.text)
                                    Text("${stat.count}건", fontSize = 12.sp, color = muted)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${String.format("%,d", stat.totalFare)}원", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = green)
                                    if (feeRate > 0f) { Text("실수령 ${String.format("%,d", netFare)}원 (${fmtFee(feeRate)}%)", fontSize = 10.sp, color = accent) }
                                }
                            }
                        }
                    }
                }
            }

            // [v21] 튜닝 홈 2단계 — 커스텀 바로가기 블록 (추가/제거/순서변경). 온오프: home_shortcuts
            if (prefs.getBoolean("home_shortcuts", true)) {
                val registry = listOf(
                    Triple("import", "📥", "가져오기"), Triple("records", "📋", "기록"),
                    Triple("airport", "✈️", "공항"), Triple("namecard", "📇", "명함"),
                    Triple("ai", "🤖", "AI비서"), Triple("events", "📅", "이벤트"),
                    Triple("bookings", "🚕", "예약"), Triple("stats", "📊", "분석"),
                    Triple("ranking", "🏆", "랭킹"), Triple("links", "🌐", "링크"),
                    Triple("settings", "⚙️", "기사설정"), Triple("more", "⋯", "더보기")
                )
                var blockCsv by remember { mutableStateOf(prefs.getString("home_blocks", "import,records,airport,ai,more") ?: "import,records,airport,ai,more") }
                var blockEdit by remember { mutableStateOf(false) }
                val order = blockCsv.split(",").map { it.trim() }.filter { id -> registry.any { it.first == id } }
                val save: (List<String>) -> Unit = { l -> blockCsv = l.joinToString(","); prefs.edit().putString("home_blocks", blockCsv).apply() }
                val runBlock: (String) -> Unit = { id ->
                    when (id) {
                        "import" -> com.callradar.app.ImageImportActivity.start(context)
                        "records" -> onNavTab(2)
                        "airport" -> onNavTab(3)
                        "more" -> onNavTab(4)
                        "radar" -> onNavTab(1)
                        "namecard" -> context.startActivity(Intent(context, com.callradar.app.NameCardActivity::class.java))
                        "settings" -> onOpenSettings()
                        "ai" -> onNavMore("ai_assistant")
                        "events" -> onNavMore("events")
                        "bookings" -> onNavMore("bookings")
                        "stats" -> onNavMore("stats")
                        "ranking" -> onNavMore("ranking")
                        "links" -> onNavMore("links")
                        else -> {}
                    }
                }
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("⚡ 바로가기 (내 맘대로 꾸미기)", fontSize = 12.sp, color = muted)
                            TextButton(onClick = { blockEdit = !blockEdit }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(if (blockEdit) "완료" else "✏️ 편집", fontSize = 11.sp, color = accent, fontWeight = FontWeight.Bold) }
                        }
                        if (!blockEdit) {
                            if (order.isEmpty()) {
                                Text("편집에서 바로가기를 추가하세요", fontSize = 11.sp, color = muted)
                            } else {
                                // [v23] 여러 개 켜도 안 짤리게 5개씩 줄바꿈(기존 단일 Row는 많이 켜면 화면 밖으로 넘쳐 안 보였음)
                                order.chunked(5).forEach { rowIds ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        rowIds.forEach { id ->
                                            val r = registry.first { it.first == id }
                                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { runBlock(id) }.padding(horizontal = 4.dp, vertical = 4.dp)) {
                                                Box(Modifier.size(46.dp).background(accent.copy(alpha = 0.14f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Text(r.second, fontSize = 20.sp) }
                                                Spacer(Modifier.height(4.dp)); Text(r.third, fontSize = 10.sp, color = AppTheme.text)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            registry.forEach { (id, icon, label) ->
                                val on = order.contains(id); val idx = order.indexOf(id)
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("$icon $label", fontSize = 13.sp, color = AppTheme.text)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (on && idx > 0) TextButton(onClick = { val m = order.toMutableList(); val t = m.removeAt(idx); m.add(idx - 1, t); save(m) }, contentPadding = PaddingValues(2.dp)) { Text("▲", color = accent) }
                                        if (on && idx >= 0 && idx < order.size - 1) TextButton(onClick = { val m = order.toMutableList(); val t = m.removeAt(idx); m.add(idx + 1, t); save(m) }, contentPadding = PaddingValues(2.dp)) { Text("▼", color = accent) }
                                        Switch(checked = on, onCheckedChange = { c -> val m = order.toMutableList(); if (c) { if (!m.contains(id)) m.add(id) } else m.remove(id); save(m) })
                                    }
                                }
                            }
                            Text("켜기/끄기 · ▲▼로 순서 · 홈에 바로 반영", fontSize = 10.sp, color = muted, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            // [v24 진화③] A단계 학습 플라이휠 — 앱이 스스로 금액 인식 개선 중임을 보여줌
            run {
                var accPct by remember { mutableStateOf(-1) }
                var accScored by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2500)   // [v24] 홈 핵심(매출 등) 먼저 로드되게 플라이휠은 뒤로 미룸
                    try {
                        val resp = withContext(Dispatchers.IO) { (URL("$SERVER_URL/api/feedback/accuracy").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 15000 }.inputStream.bufferedReader().readText() }
                        val rows = org.json.JSONObject(resp).optJSONArray("rows")
                        var sc = 0; var co = 0
                        for (i in 0 until (rows?.length() ?: 0)) { val o = rows!!.getJSONObject(i); if (o.optString("feature") == "amount") { sc += o.optInt("scored"); co += o.optInt("correct") } }
                        accScored = sc; if (sc >= 5) accPct = ((co * 100.0) / sc).toInt()
                    } catch (e: Exception) {}
                }
                if (accPct >= 0) {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🤖", fontSize = 24.sp, modifier = Modifier.padding(end = 12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("앱이 스스로 학습 중", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                                Text("금액 인식 정확도 ${accPct}% · 교정 ${accScored}건 반영", fontSize = 12.sp, color = muted)
                            }
                            Text("${accPct}%", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = green)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            // [v19] 콜레이더 기사 톡방 배너 (커뮤니티 방 gsyuVMCi로 통일)
            if (prefs.getBoolean("card_notice", true))
            Card(modifier = Modifier.fillMaxWidth().clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.kakao.com/o/gsyuVMCi"))) }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)), shape = RoundedCornerShape(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text("💬", fontSize = 28.sp, modifier = Modifier.padding(end = 12.dp))
                    Column {
                        Text("콜레이더 기사 톡방", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                        Text("정보 공유·질문·건의 환영해요!", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                    }
                }
            }

            // [v18] 홈 하단 버전 표시 — 업데이트 때마다 눈에 보이게(설정 맨 아래 대신)
            val appVersion = remember { try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { "" } }
            Text(
                "콜레이더 v$appVersion",
                fontSize = 11.sp,
                color = muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)
            )
        }
    }
}

private fun isNaviEnabled(context: Context): Boolean {
    return try { Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains("com.callradar.app") == true } catch (e: Exception) { false }
}
