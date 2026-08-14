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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

private const val SERVER_URL = Config.SERVER_URL

// [v24] 자가치유 GET — 토큰이 stale/불일치라 403/401 나면 토큰 비우고 무토큰으로 1회 재시도.
//  (특정 유저가 '서버 연결 실패' 지속되던 문제: 페어링/계정전환 후 남은 토큰↔user_id 불일치 → 403)
private fun getWithSelfHeal(prefs: android.content.SharedPreferences, urlStr: String): String {
    fun open(withToken: Boolean): java.net.HttpURLConnection {
        val c = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 40000   // [v25] 콜드스타트(서버 깨어남) 대비 넉넉히
        if (withToken) com.callradar.app.Auth.tok?.let { if (it.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $it") }
        return c
    }
    var lastErr: Exception? = null
    repeat(3) { attempt ->   // [v25] 콜드스타트/일시장애 자동 재시도
        try {
            var conn = open(true)
            var code = conn.responseCode
            if ((code == 401 || code == 403) && !com.callradar.app.Auth.tok.isNullOrBlank()) {
                try { conn.disconnect() } catch (e: Exception) {}
                com.callradar.app.Auth.clear(prefs)   // stale/불일치 토큰 제거 → 무토큰 재시도(phase-1 통과)
                conn = open(false); code = conn.responseCode
            }
            if (code in 200..299) return conn.inputStream.bufferedReader().readText()
            throw java.io.IOException("HTTP $code")
        } catch (e: Exception) {
            lastErr = e
            // SSL(폰 시계)·DNS는 재시도해도 소용없음 → 즉시 던져 정확히 안내
            if (e is javax.net.ssl.SSLException || e is java.net.UnknownHostException) throw e
            if (attempt < 2) try { Thread.sleep(1500L * (attempt + 1)) } catch (ie: InterruptedException) {}
        }
    }
    throw lastErr ?: java.io.IOException("unknown")
}

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
    // [관리자 게이트] 버전 라벨 탭 → ADMIN_KEY 입력 → 서버검증 → 이 기기 관리자 해금(자동기록 사용 가능)
    var showAdminDialog by remember { mutableStateOf(false) }
    var adminKeyInput by remember { mutableStateOf("") }
    var adminBusy by remember { mutableStateOf(false) }
    // [v43] 계정 기반 관리자/권한 — 서버(users.is_admin/auto_entitled)에서 조회. 카카오 로그인하면 어느 폰에서든 유지.
    var acctAdmin by remember { mutableStateOf(prefs.getBoolean("acct_admin", prefs.getBoolean("is_admin", false))) }
    var acctEntitled by remember { mutableStateOf(prefs.getBoolean("acct_entitled", false)) }
    var entitleTarget by remember { mutableStateOf("") }
    var entitleMsg by remember { mutableStateOf("") }
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

    // [관리자 게이트] 자동기록(접근성)은 관리자 해금 기기에서만. 버전 라벨 탭 → 이 다이얼로그 → ADMIN_KEY 서버검증.
    if (showAdminDialog) {
        AlertDialog(
            onDismissRequest = { if (!adminBusy) { showAdminDialog = false; adminKeyInput = ""; entitleMsg = "" } },
            title = { Text(if (acctAdmin) "관리자 (${nickname})" else "관리자 인증", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column {
                if (!acctAdmin) {
                    Text("ADMIN_KEY를 넣으면 이 카카오 계정이 관리자로 등록돼요. 이후엔 어느 폰에서든 이 계정으로 로그인만 하면 자동기록을 쓸 수 있어요(기기별 재인증 불필요).", fontSize = 12.sp, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                    OutlinedTextField(value = adminKeyInput, onValueChange = { adminKeyInput = it.trim() }, label = { Text("ADMIN_KEY", color = muted) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                } else {
                    Text("이 계정은 관리자예요. 테스트 기사에게 자동기록 사용권을 부여/회수할 수 있어요. 대상 기사의 계정 ID(user_id)를 넣으세요.", fontSize = 12.sp, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                    OutlinedTextField(value = entitleTarget, onValueChange = { entitleTarget = it.filter { c -> c.isDigit() } }, label = { Text("대상 계정 ID", color = muted) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                    if (entitleMsg.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(entitleMsg, fontSize = 12.sp, color = accent) }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        fun entitle(on: Boolean) {
                            val t = entitleTarget.toIntOrNull() ?: return; adminBusy = true; entitleMsg = ""
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { try {
                                    val json = JSONObject().apply { put("target_user_id", t); put("on", on) }
                                    val conn = (URL("$SERVER_URL/api/admin/entitle").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 10000 }
                                    conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                                    conn.responseCode in 200..299
                                } catch (e: Exception) { false } }
                                adminBusy = false; entitleMsg = if (ok) (if (on) "부여 완료" else "회수 완료") else "실패 · 관리자 권한/ID 확인"
                            }
                        }
                        Button(enabled = !adminBusy && entitleTarget.isNotBlank(), onClick = { entitle(true) }, colors = ButtonDefaults.buttonColors(containerColor = green)) { Text("권한 부여", color = Color.White, fontSize = 13.sp) }
                        OutlinedButton(enabled = !adminBusy && entitleTarget.isNotBlank(), onClick = { entitle(false) }) { Text("회수", color = red, fontSize = 13.sp) }
                    }
                }
            } },
            confirmButton = {
                if (!acctAdmin) Button(enabled = !adminBusy && adminKeyInput.isNotBlank(), onClick = {
                    adminBusy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            try {
                                val json = JSONObject().apply { put("user_id", userId); put("key", adminKeyInput) }
                                val conn = (URL("$SERVER_URL/api/admin/claim").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 8000 }
                                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                                conn.responseCode == 200
                            } catch (e: Exception) { false }
                        }
                        adminBusy = false
                        if (ok) { acctAdmin = true; prefs.edit().putBoolean("acct_admin", true).putBoolean("is_admin", true).apply(); adminKeyInput = ""; android.widget.Toast.makeText(context, "이 계정이 관리자로 등록됨 · 자동기록 사용 가능 (원스토어+접근성)", android.widget.Toast.LENGTH_LONG).show() }
                        else android.widget.Toast.makeText(context, "키가 올바르지 않아요", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text(if (adminBusy) "확인 중" else "관리자 등록", color = Color.Black, fontWeight = FontWeight.Bold) }
                else TextButton(onClick = { if (!adminBusy) { showAdminDialog = false; entitleMsg = "" } }) { Text("완료", color = accent) }
            },
            dismissButton = { OutlinedButton(onClick = { if (!adminBusy) { showAdminDialog = false; adminKeyInput = ""; entitleMsg = "" } }) { Text("닫기") } },
            containerColor = AppTheme.card)
    }

    // [v43] 계정 플래그(is_admin/auto_entitled) 서버 조회 → 자동기록 토글 노출 결정
    LaunchedEffect(userId, refreshKey) {
        if (userId.isEmpty()) return@LaunchedEffect
        try {
            val s = withContext(Dispatchers.IO) { (URL("$SERVER_URL/api/users/$userId/flags").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 12000 }.inputStream.bufferedReader().readText() }
            val o = JSONObject(s)
            val ia = o.optBoolean("is_admin", false); val ae = o.optBoolean("auto_entitled", false)
            val fo = o.optBoolean("free_open", false)  // [근본해결] 전원 무료 개방 스위치
            acctAdmin = ia; acctEntitled = ae || fo
            prefs.edit().putBoolean("acct_admin", ia).putBoolean("acct_entitled", ae).putBoolean("is_admin", ia).putBoolean("auto_free_open", fo).apply()
        } catch (e: Exception) {}
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    fun loadHomeData() {
        if (userId.isEmpty()) { isLoading = false; return }
        scope.launch {
            try {
                // [v23] 계정 dayStart 동기화 — 서브폰도 같은 영업일 기준으로 오늘매출 계산되게(today 조회 전에 갱신)
                try { withContext(Dispatchers.IO) { val s = (URL("$SERVER_URL/api/user-settings/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 30000 }.inputStream.bufferedReader().readText(); val ds = JSONObject(s).optInt("day_start", prefs.getInt("day_start_hour", 0)); prefs.edit().putInt("day_start_hour", ds).apply() } } catch (e: Exception) {}
                val todayResponse = withContext(Dispatchers.IO) { getWithSelfHeal(prefs, "$SERVER_URL/api/today/$userId?dayStart=${prefs.getInt("day_start_hour", 0)}") }
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

                // [perf] 프로필/플랫폼/지출/근무일 병렬 로드 (순차 4연속 → 동시 실행). 홈 로딩 체감 개선.
                val ymH = SimpleDateFormat("yyyy-MM", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date())
                val dshH = prefs.getInt("day_start_hour", 0)
                val profD = async(Dispatchers.IO) { try { (URL("$SERVER_URL/api/profile/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 20000 }.inputStream.bufferedReader().readText() } catch (e: Exception) { null } }
                val platD = async(Dispatchers.IO) { try { (URL("$SERVER_URL/api/stats/platform/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 20000 }.inputStream.bufferedReader().readText() } catch (e: Exception) { null } }
                val expD = async(Dispatchers.IO) { try { (URL("$SERVER_URL/api/expenses/summary/$userId?month=$ymH").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 20000 }.inputStream.bufferedReader().readText() } catch (e: Exception) { null } }
                val dailyD = async(Dispatchers.IO) { try { (URL("$SERVER_URL/api/stats/daily/$userId?month=$ymH&dayStart=$dshH").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 20000 }.inputStream.bufferedReader().readText() } catch (e: Exception) { null } }

                try {
                    val profResponse = profD.await() ?: throw Exception("prof")
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
                    val platResponse = platD.await() ?: throw Exception("plat")
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
                    val expResponse = expD.await() ?: throw Exception("exp")
                    val expJson = JSONObject(expResponse)
                    businessExpense = expJson.optInt("business", 0)
                    personalExpense = expJson.optInt("personal", 0)
                    miscExpense = expJson.optInt("misc", 0)
                } catch (e: Exception) { }

                // [v19] 이번 달 실제 근무일 수(매출 있는 날) — 사납금/가스를 그만큼만 차감해 월초 과다 마이너스 방지
                try {
                    val ym2 = SimpleDateFormat("yyyy-MM", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date())
                    val dsh = prefs.getInt("day_start_hour", 0)
                    val dailyResp = dailyD.await() ?: throw Exception("daily")
                    val arr = JSONArray(dailyResp)
                    var cnt = 0
                    for (i in 0 until arr.length()) { if (arr.getJSONObject(i).optInt("total_fare", 0) > 0) cnt++ }
                    monthWorkedDays = cnt
                    prefs.edit().putInt("mwd_$ym2", cnt).apply()   // [SEV3] 성공 값 캐시 → 다음 실패 때 0으로 떨어져 월급 과대표시 되는 것 방지
                } catch (e: Exception) {
                    // 로드 실패 시 이번 달 마지막 성공값 유지(없으면 기존값). 0으로 리셋해 사납금 미차감→월급 과대 방지.
                    val ym2 = SimpleDateFormat("yyyy-MM", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date())
                    val cached = prefs.getInt("mwd_$ym2", -1); if (cached >= 0) monthWorkedDays = cached
                }

                errorMessage = null
                isLoading = false; homeLoaded = true
            } catch (e: Exception) {
                errorMessage = when {
                    e is javax.net.ssl.SSLException || (e.message?.contains("SSL", true) == true) || (e.message?.contains("cert", true) == true) || (e.message?.contains("trust", true) == true) ->
                        "📅 폰 날짜·시간이 틀린 것 같아요. 설정 > 날짜·시간 > '자동'을 켜주세요. (인증서 오류)"
                    e is java.net.UnknownHostException -> "📶 인터넷 연결을 확인해 주세요."
                    else -> "서버 연결 실패 · ${e.javaClass.simpleName} — 잠시 후 다시 시도돼요"
                }
                isLoading = false; homeLoaded = true
            }
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
        while (true) { kotlinx.coroutines.delay(60000L); loadHomeData() }   // [SEV9] 15s→60s: 데이터·배터리 절약(포그라운드 복귀는 ON_RESUME이 갱신)
    }

    Column(modifier = Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState())) {
        // 헤더
        Row(modifier = Modifier.fillMaxWidth().background(card).padding(top = 48.dp, start = 20.dp, end = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("콜레이더", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent)
                    Text("β", fontSize = 11.sp, color = muted)
                    val verLabel = remember { try { val pi = context.packageManager.getPackageInfo(context.packageName, 0); val vc = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode; "v${pi.versionName}·$vc" } catch (e: Exception) { "" } }
                    if (verLabel.isNotBlank()) Text(verLabel, fontSize = 10.sp, color = muted, modifier = Modifier.clickable { showAdminDialog = true })
                }
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
                    Box(modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text("⚙️ 기사설정", fontSize = 12.sp, color = AppTheme.text, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
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
                            // [4-A] 개인택시(personal)는 사납금이 없음 → 사납 미적용. 순수익=매출−가스. 법인만 사납 반영.
                            val effDailySanap = if (isCorporate) dailySanap else 0
                            if (effDailySanap > 0 || lpgDailyCost > 0) {
                                val sanapDaysQuota = (workDaysSetting - prefs.getInt("annual_leave", 0)).coerceAtLeast(0)
                                val owedSanap = effDailySanap * sanapDaysQuota
                                val sanapMet = isCorporate && effDailySanap > 0 && sanapDaysQuota > 0 && owedSanap > 0 &&
                                    (monthWorkedDays > sanapDaysQuota || (profile?.monthFare ?: 0) >= owedSanap)
                                val todayNet = calcNetIncome(todayFare, 1, if (sanapMet) 0 else effDailySanap)
                                Text("순수익 ${String.format("%,d", todayNet)}원" + (if (sanapMet) " (사납금 완납)" else ""), fontSize = 11.sp, color = if (todayNet > 0) accent else red)
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

            // [v18] 근무 세션 (핸들링 스타일: 시간 카운팅 + 일시정지/재개 + 시간당 매출) — 순수 prefs, 앱 죽어도 이어짐
            run {
                val sessionEnabled = prefs.getBoolean("work_session_enabled", true)
                var workStart by remember { mutableStateOf(prefs.getLong("work_start", 0L)) }
                var pausedTotal by remember { mutableStateOf(prefs.getLong("work_paused_total", 0L)) }
                var pauseStart by remember { mutableStateOf(prefs.getLong("work_pause_start", 0L)) }
                var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
                var workDist by remember { mutableStateOf(prefs.getFloat("work_distance_m", 0f)) }
                // [v59] 로컬 출근/일시정지/재개/퇴근 직후 시각 — 20초 투폰 pull이 아직 서버에 반영 안 된 옛 상태로 로컬 일시정지를 덮어써 재개시키던 버그 방지.
                var lastLocalWorkChange by remember { mutableStateOf(0L) }
                val distEnabled = prefs.getBoolean("work_dist_enabled", true)
                var maxHours by remember { mutableStateOf(prefs.getInt("work_max_hours", 0)) }  // [v23] 근무 최대시간 자동마감(0=끔)
                val active = workStart > 0L
                val paused = pauseStart > 0L
                // [v41] 영업일(day_start_hour 기준) 키 — 하루 안 여러 출퇴근을 하나로 누적하기 위한 기준.
                fun workDayKey(): Long {
                    val h = prefs.getInt("day_start_hour", 0)
                    val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"))
                    if (c.get(java.util.Calendar.HOUR_OF_DAY) < h) c.add(java.util.Calendar.DAY_OF_YEAR, -1)
                    c.set(java.util.Calendar.HOUR_OF_DAY, h); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
                    return c.timeInMillis
                }
                // [v2] 투폰 근무세션 동기화 — 로컬 변경을 서버로 push (출근/일시정지/재개/퇴근 때 호출)
                fun pushWorkSession(ws: Long, pt: Long, ps: Long, sf: Int) {
                    lastLocalWorkChange = System.currentTimeMillis()   // [v59] 로컬 변경 표시 → 30초간 pull이 서버로 덮지 않음
                    if (userId.isEmpty()) return
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val json = JSONObject().apply { put("user_id", userId); put("work_start", ws); put("paused_total", pt); put("pause_start", ps); put("start_fare", sf) }
                                val conn = (URL("$SERVER_URL/api/work-session").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 8000; readTimeout = 15000 }
                                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }; conn.responseCode
                            }
                        } catch (e: Exception) {}
                    }
                }
                // [v17][#5] 퇴근 요약 카드
                var showEndSummary by remember { mutableStateOf(false) }
                var showDistReset by remember { mutableStateOf(false) }   // [거리초기화] 오염된 세션거리(548km 등) 수동 리셋
                var showPastSessions by remember { mutableStateOf(false) }   // [v41] 지난 근무 기록 보기/공유
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
                // [원스토어 반려수정] 어떤 예외가 나도 '퇴근'으로 앱이 죽지 않도록 전체를 안전망으로 감쌈.
                val endShiftNow = {
                  try {
                    val now = System.currentTimeMillis()
                    val netMs = ((now - workStart) - pausedTotal - (if (paused) now - pauseStart else 0L)).coerceAtLeast(0L)
                    val grossMs = (now - workStart).coerceAtLeast(0L)
                    // [v41] 하루(영업일) 누적 — 오전·오후 여러 출퇴근을 하나로 합쳐 영수증을 '하루 총합'으로.
                    //  이번 세션분을 당일 누적(work_day_net_ms/gross_ms)에 더하고, 매출·거리는 당일 첫 출근 기준으로 계산.
                    val dayKey = workDayKey()
                    val sameDay = prefs.getLong("work_day_key", 0L) == dayKey
                    // [버그수정] 16시간 초과 세션 = 퇴근 깜빡한 유령 → 하루 누적에 더하지 않음(25시간 오염 방지)
                    val realSession = grossMs < 16L * 3600000L
                    val dayNetMs = (if (sameDay) prefs.getLong("work_day_net_ms", 0L) else 0L) + (if (realSession) netMs else 0L)
                    val dayGrossMs = (if (sameDay) prefs.getLong("work_day_gross_ms", 0L) else 0L) + (if (realSession) grossMs else 0L)
                    val dayStartFare = if (sameDay) prefs.getInt("work_day_start_fare", prefs.getInt("work_start_fare", 0)) else prefs.getInt("work_start_fare", 0)
                    prefs.edit().putLong("work_day_key", dayKey).putLong("work_day_net_ms", dayNetMs).putLong("work_day_gross_ms", dayGrossMs).putInt("work_day_start_fare", dayStartFare).apply()
                    val sFare = (todayFare - dayStartFare).coerceAtLeast(0)
                    val hrs = dayNetMs / 3600000.0
                    sumGrossMin = dayGrossMs / 60000L
                    sumNetMin = dayNetMs / 60000L
                    // [km폭주 수정②] 비현실(>16h 유령) 세션은 거리 신뢰불가 → 0. (workStart 스톨로 미터가 오래 돌아 수백 km 누적되던 것 차단)
                    sumDistKm = if (realSession) prefs.getFloat("work_distance_m", 0f) / 1000f else 0f
                    sumFare = sFare
                    sumPerHour = if (hrs > 0.05) (sFare / hrs).toInt() else 0
                    // [v24 진화②] 교대별 손익 — 일 유류비+사납금 빼고 예상 순수익 (하루 1회만 차감)
                    // [개인/법인 분리] 개인택시는 사납금이 없음 → 고정비=유류만. 법인만 사납 포함(잔존 사납값 누출 방지).
                    val effShiftSanap = if (driverType == "corporate") prefs.getInt("daily_sanap", 0) else 0
                    sumFixedCost = prefs.getInt("lpg_daily_cost", 0) + effShiftSanap
                    sumNetProfit = (sFare - sumFixedCost).coerceAtLeast(0)
                    com.callradar.app.TimingLog.send(context, "shift_end", amount = sFare)
                    try {
                        val log = try { JSONArray(prefs.getString("work_session_log", "[]")) } catch (e: Exception) { JSONArray() }
                        log.put(JSONObject().apply { put("end", now); put("grossMin", sumGrossMin); put("netMin", sumNetMin); put("distKm", sumDistKm.toDouble()); put("fare", sFare); put("perHour", sumPerHour) })
                        val trimmed = if (log.length() > 90) JSONArray().also { for (i in log.length() - 90 until log.length()) it.put(log.get(i)) } else log
                        prefs.edit().putString("work_session_log", trimmed.toString()).apply()
                    } catch (e: Exception) {}
                    // [v56] 근무세션 요약(시간·km·매출) 서버 저장 — 퇴근 시 1회. 진단·크로스디바이스용, 좌표 아닌 집계값이라 부담 거의 없음.
                    run {
                        val sStart = workStart; val sEnd = now
                        val gMin = sumGrossMin; val nMin = sumNetMin; val dKm = sumDistKm; val sF = sFare; val pH = sumPerHour
                        if (userId.isNotEmpty()) scope.launch {
                            try { withContext(Dispatchers.IO) {
                                val j = JSONObject().apply { put("user_id", userId); put("started_at", sStart); put("ended_at", sEnd); put("gross_min", gMin); put("net_min", nMin); put("dist_km", dKm.toDouble()); put("fare", sF); put("per_hour", pH) }
                                val conn = (URL("$SERVER_URL/api/work-session/close").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 8000; readTimeout = 15000 }
                                conn.outputStream.use { it.write(j.toString().toByteArray(Charsets.UTF_8)) }; conn.responseCode
                            } } catch (e: Exception) {}
                        }
                    }
                    // 이어가기용 스냅샷 저장(잘못 퇴근 시 복구)
                    prefs.edit().putLong("last_work_start", workStart).putLong("last_work_paused_total", pausedTotal).putLong("last_work_end", now).apply()
                    workStart = 0L; pausedTotal = 0L; pauseStart = 0L
                    // [km폭주 수정①] 퇴근 시 세션 거리 0으로 초기화 — 다음 세션으로 누적 이월되던 것 차단(634→1228km 원인).
                    prefs.edit().putLong("work_start", 0L).putLong("work_paused_total", 0L).putLong("work_pause_start", 0L).putFloat("work_distance_m", 0f).putBoolean("meter_local", false).apply()
                    workDist = 0f
                    pushWorkSession(0L, 0L, 0L, 0)
                    stopMeter()
                    com.callradar.app.TrackSync.uploadRecent(context)   // [v44] 퇴근 시 오늘 궤적 서버 백업
                    com.callradar.app.WorkAutoEnd.cancel(context)
                    com.callradar.app.ScreenCaptureService.stopSession(context)   // [v24] 퇴근 시 화면권한 해제
                    com.callradar.app.Telemetry.log(context, "shift_end", "home", meta = sumFare.toString())
                    showEndSummary = true
                  } catch (e: Exception) {
                    // 안전망: 예상치 못한 예외에도 세션은 종료 상태로 만들고 요약을 띄운다(강제종료 방지).
                    try {
                        workStart = 0L; pausedTotal = 0L; pauseStart = 0L
                        prefs.edit().putLong("work_start", 0L).putLong("work_paused_total", 0L).putLong("work_pause_start", 0L).putFloat("work_distance_m", 0f).putBoolean("meter_local", false).apply()
                        workDist = 0f
                    } catch (e2: Exception) {}
                    try { stopMeter() } catch (e2: Exception) {}
                    showEndSummary = true
                  }
                }
                LaunchedEffect(Unit) { if (workStart > 0L) { com.callradar.app.WorkAutoEnd.schedule(context, workStart, prefs.getInt("work_max_hours", 0)); if (prefs.getBoolean("meter_local", false) && paused == false && distEnabled) startMeter() } }  // [v23 예약복원 + km폭주③ 소유폰만 미터 재개]
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
                            if ((rws != workStart || rpt != pausedTotal || rps != pauseStart) && System.currentTimeMillis() - lastLocalWorkChange > 30000L) {
                                workStart = rws; pausedTotal = rpt; pauseStart = rps; nowTick = System.currentTimeMillis()
                                prefs.edit().putLong("work_start", rws).putLong("work_paused_total", rpt).putLong("work_pause_start", rps).apply()
                                // [km폭주 수정③] pull은 미터 자동시작 안 함(유휴 보조폰 유령거리 차단). 로컬 출근한 폰만 미터.
                                //  원격 퇴근(rws==0) 시 미터 중지 + 이 폰의 거리 리셋(다른 폰에서 퇴근해도 유령거리 안 남게).
                                if (rws == 0L) { stopMeter(); prefs.edit().putBoolean("meter_local", false).putFloat("work_distance_m", 0f).apply(); workDist = 0f }
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
                // [거리초기화] 오염된 세션 거리(예: GPS 튐으로 548km)를 근무 중 수동 리셋. 확인 다이얼로그로 실수 방지.
                if (showDistReset) {
                    AlertDialog(
                        onDismissRequest = { showDistReset = false },
                        title = { Text("이동 거리 초기화?", color = AppTheme.text, fontWeight = FontWeight.Bold) },
                        text = { Text("이번 근무의 누적 이동 거리를 0km로 되돌려요. GPS 튐 등으로 거리가 비정상적으로 크게 잡혔을 때 사용하세요. (근무 시간·매출은 그대로예요.)", fontSize = 13.sp, color = muted) },
                        confirmButton = { Button(onClick = {
                            prefs.edit().putFloat("work_distance_m", 0f).apply(); workDist = 0f; showDistReset = false
                        }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("초기화", color = Color.Black, fontWeight = FontWeight.Bold) } },
                        dismissButton = { OutlinedButton(onClick = { showDistReset = false }) { Text("취소") } },
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
                // [v41] 지난 근무 기록 목록 + 각 건 공유 — 자정이 지나도 과거 근무 영수증을 다시 공유 가능.
                if (showPastSessions) {
                    val logArr = try { JSONArray(prefs.getString("work_session_log", "[]")) } catch (e: Exception) { JSONArray() }
                    val fmtP = java.text.SimpleDateFormat("M/d(E) HH:mm", java.util.Locale.KOREA).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul") }
                    AlertDialog(
                        onDismissRequest = { showPastSessions = false },
                        title = { Text("📋 지난 근무 기록", color = AppTheme.text, fontWeight = FontWeight.Bold) },
                        text = {
                            if (logArr.length() == 0) {
                                Text("아직 퇴근 기록이 없어요. 퇴근하면 여기에 쌓여요.", fontSize = 13.sp, color = muted)
                            } else {
                                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (i in logArr.length() - 1 downTo 0) {
                                        val o = logArr.optJSONObject(i) ?: continue
                                        val endMs = o.optLong("end", 0L)
                                        val nmP = o.optLong("netMin", 0L); val nhP = nmP / 60; val nmmP = nmP % 60
                                        val fareP = o.optInt("fare", 0)
                                        val phP = o.optInt("perHour", 0)
                                        val dkP = o.optDouble("distKm", 0.0)
                                        val dateP = if (endMs > 0) fmtP.format(java.util.Date(endMs)) else "-"
                                        Column(Modifier.fillMaxWidth().background(AppTheme.surface2, RoundedCornerShape(10.dp)).padding(12.dp)) {
                                            Text(dateP, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                                            Text("근무 ${nhP}시간 ${nmmP}분 · ${String.format("%.1f", dkP)}km", fontSize = 12.sp, color = muted)
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("매출 ${String.format("%,d", fareP)}원 · 시간당 ${String.format("%,d", phP)}원", fontSize = 12.sp, color = green)
                                                TextButton(onClick = {
                                                    val dashP = "─".repeat(22)
                                                    val txtP = buildString {
                                                        append("📻 콜레이더 · 근무 영수증\n"); append("$dashP\n")
                                                        append("날짜   $dateP\n")
                                                        append("근무   ${nhP}시간 ${nmmP}분\n")
                                                        append("거리   ${String.format("%.1f", dkP)} km\n")
                                                        append("매출   ${String.format("%,d", fareP)}원\n")
                                                        append("시간당 ${String.format("%,d", phP)}원\n")
                                                        append("$dashP\n수고하셨습니다!")
                                                    }
                                                    try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, txtP) }, "영수증 공유")) } catch (e: Exception) {}
                                                }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("📤 공유", fontSize = 12.sp, color = accent) }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = { Button(onClick = { showPastSessions = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("닫기", color = Color.Black) } },
                        containerColor = card
                    )
                }
                if (sessionEnabled) {
                    // [v41] 라이브 카드도 '하루 누적'으로 — 당일 완료 세션분 + 현재 세션분을 이어서 표시.
                    val sameDayLive = prefs.getLong("work_day_key", 0L) == workDayKey()
                    // [버그수정] '퇴근 깜빡' 등으로 비현실적(>16h) 누적이 박히면 오염값 → 무시하고 자가치유(pref 정리).
                    //  (25시간처럼 말이 안 되는 근무시간이 출근해도 계속 뜨던 문제 해결)
                    val rawDayNet = if (sameDayLive) prefs.getLong("work_day_net_ms", 0L) else 0L
                    val dayNetPrev = if (rawDayNet > 16L * 3600000L) {
                        prefs.edit().putLong("work_day_net_ms", 0L).putLong("work_day_gross_ms", 0L).apply(); 0L
                    } else rawDayNet
                    val curNet = if (!active) 0L else ((nowTick - workStart) - pausedTotal - (if (paused) nowTick - pauseStart else 0L)).coerceAtLeast(0L)
                    val workedMs = dayNetPrev + curNet
                    val workedMin = workedMs / 60000L
                    val hh = workedMin / 60; val mm = workedMin % 60
                    val workedHours = workedMs.toDouble() / 3600000.0
                    // [버그수정] 시간당/㎞당 매출은 '하루 매출(당일 첫 출근 기준)'을 '하루 근무시간'으로 나눔.
                    // (짧은 세션 하나로 나눠 시간당 수백만원 나오던 버그는 하루 누적시간으로 방지)
                    val sStartFare = if (sameDayLive) prefs.getInt("work_day_start_fare", prefs.getInt("work_start_fare", 0)) else prefs.getInt("work_start_fare", 0)
                    val sessionFare = (todayFare - sStartFare).coerceAtLeast(0)
                    val perHour = if (workedHours > 0.05) (sessionFare / workedHours).toInt() else 0
                    val distKm = workDist / 1000f
                    val perKm = if (distKm > 0.3f) (sessionFare / distKm).toInt() else 0
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
                                    Box(modifier = Modifier.weight(1f).background(AppTheme.surface2, RoundedCornerShape(10.dp)).clickable { showDistReset = true }.padding(vertical = 8.dp, horizontal = 10.dp)) {
                                        Column {
                                            Text("이동 거리 · 탭 초기화", fontSize = 10.sp, color = muted)
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
                            Spacer(Modifier.height(8.dp))
                            // [v57] 영업일 시작시각 — 자정 넘긴 운행을 어느 날에 귀속할지 기준. 탭하면 순환(자정/새벽4·5·6시). 설정하면 완료시각 기준 귀속 활성화.
                            run {
                                var dsh by remember { mutableStateOf(prefs.getInt("day_start_hour", 0)) }
                                val opts = listOf(0, 4, 5, 6)
                                Row(modifier = Modifier.fillMaxWidth().background(AppTheme.surface2, RoundedCornerShape(10.dp)).clickable {
                                    val idx = opts.indexOf(dsh).let { if (it < 0) 0 else it }
                                    val nv = opts[(idx + 1) % opts.size]
                                    dsh = nv; prefs.edit().putInt("day_start_hour", nv).putBoolean("day_start_set", true).apply()
                                }.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("📅 영업일 시작시각(자정 넘김 귀속)", fontSize = 12.sp, color = muted)
                                    Text(if (prefs.getBoolean("day_start_set", false)) (if (dsh == 0) "자정(0시)" else "새벽 ${dsh}시") + " · 탭변경" else "미설정 · 탭해서 설정", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (prefs.getBoolean("day_start_set", false)) accent else muted)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            if (!active) {
                                Button(onClick = { val t = System.currentTimeMillis(); workStart = t; pausedTotal = 0L; pauseStart = 0L; nowTick = t
                                    // [v41] 같은 영업일 재출근이면 당일 누적(거리·시작요금·시간) 유지 → 이어서 근무. 새 영업일이면 리셋.
                                    val dayKey = workDayKey(); val newDay = prefs.getLong("work_day_key", 0L) != dayKey
                                    // [km폭주 수정③] meter_local=true → 이 폰이 미터 소유자(로컬 출근). pull은 미터 안 켜므로 소유폰만 거리 누적.
                                    val e = prefs.edit().putLong("work_start", t).putLong("work_paused_total", 0L).putLong("work_pause_start", 0L).putInt("work_start_fare", todayFare).putBoolean("meter_local", true)
                                    if (newDay) { e.putLong("work_day_key", dayKey).putLong("work_day_net_ms", 0L).putLong("work_day_gross_ms", 0L).putInt("work_day_start_fare", todayFare).putFloat("work_distance_m", 0f) }
                                    e.apply()
                                    workDist = if (newDay) 0f else prefs.getFloat("work_distance_m", 0f)
                                    pushWorkSession(t, 0L, 0L, todayFare); com.callradar.app.Telemetry.log(context, "shift_start", "home"); com.callradar.app.WorkAutoEnd.schedule(context, t, maxHours); com.callradar.app.TimingLog.send(context, "shift_start"); if (distEnabled) startMeter(); if (prefs.getBoolean("voice_on", false) && homeBrief.isNotBlank()) homeTts?.speak(homeBrief, TextToSpeech.QUEUE_FLUSH, null, "brief") }, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = green), shape = RoundedCornerShape(12.dp)) { Text("🟢 출근 (근무 시작)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
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
                            // [v41] 지난 근무 기록 보기/공유 진입 — 퇴근 후 자정이 지나도 과거 세션을 다시 공유
                            TextButton(onClick = { showPastSessions = true }, modifier = Modifier.align(Alignment.CenterHorizontally), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text("📋 지난 근무 기록", fontSize = 12.sp, color = muted) }
                        }
                    }
                }
            }

            // [v32] 🏢 회사 프로필 기반 예상 기사몫 (법인 + 활성 프로필 있을 때만). 홈 순수익 계산과 별개 표시(무손상).
            // [4-A] 개인택시(personal)는 사납금이 없으므로 이 카드를 표시하지 않는다(정관 도메인 규칙).
            // [금액중복 정리] 예상 기사몫 금액은 아래 '예상 월급 수령액' 카드와 사실상 동일값(총매출−사납) → 큰 금액 중복.
            //  여기선 큰 금액을 빼고 '회사 급여 계산기 바로가기'로만. 금액은 아래 월급 카드 하나만 표시.
            run {
                val ap = if (isCorporate) com.callradar.app.CompanyProfile.active(prefs) else null
                if (ap != null && monthFare > 0) {
                    Card(modifier = Modifier.fillMaxWidth().clickable { com.callradar.app.CompanyProfileActivity.start(context) }, colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("🏢 ${ap.label()} · 급여 계산기", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                                Text("사납 ${String.format("%,d", ap.sanapDaily * billDays)}" + (if (ap.gasBearer == "기사") " · 가스 기사부담" else " · 가스 회사부담") + " · 초과율 ${(ap.overRate * 100).toInt()}%", fontSize = 10.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
                            }
                            Text("바로가기 →", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent)
                        }
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

            // [v57] 기록 설정 — 운행기록버튼·자동기록·금액자동입력 3개 토글을 카드 하나로 합침(홈 세로공간 절약).
            run {
                var floatingOn by remember(refreshKey) { mutableStateOf(prefs.getBoolean("floating_on", false)) }
                val showAuto = com.callradar.app.BuildConfig.FLAVOR == "onestore" && (acctAdmin || acctEntitled)
                var autoRec by remember(refreshKey) { mutableStateOf(prefs.getBoolean("auto_record_on", false)) }
                var showAutoSetup by remember { mutableStateOf(false) }
                // [v53 #103/#124] 업데이트 후 삼성 '제한된 설정'으로 접근성이 꺼진 경우 자동 감지 → 설정 안내 자동 표시.
                LaunchedEffect(refreshKey) {
                    if (showAuto) {
                        val accNow = (android.provider.Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services") ?: "").contains("com.callradar.app/com.callradar.app.NaviIntentReceiver")
                        if (autoRec && !accNow) showAutoSetup = true
                    }
                }
                val showNotif = Config.NOTIF_CAPTURE_ENABLED && prefs.getBoolean("card_notif", true)
                var capOn by remember(refreshKey) { mutableStateOf(prefs.getBoolean("notif_capture_on", false) && isNotifAccessGranted()) }
                val anyOn = floatingOn || (showAuto && autoRec) || (showNotif && capOn)
                fun divider() = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp)
                Card(colors = CardDefaults.cardColors(containerColor = if (anyOn) green.copy(alpha = 0.10f) else AppTheme.card), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        // ── 운행 기록 버튼
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(if (floatingOn) "🟢 운행 기록 버튼 켜짐" else "🚕 운행 기록 버튼", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                                Text("시작·완료 두 번이면 기록 끝", fontSize = 11.sp, color = muted)
                            }
                            Switch(checked = floatingOn, onCheckedChange = { on ->
                                onToggleFloating(on)
                                floatingOn = if (on) isOverlayGranted() else false
                                com.callradar.app.Telemetry.log(context, if (on) "floating_on" else "floating_off", "home")
                            })
                        }
                        // ── 자동 기록 (관리자)
                        if (showAuto) {
                            Box(modifier = divider().background(AppTheme.surface2))
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f).clickable { showAutoSetup = true }) {
                                    Text(if (autoRec) "🤖 자동 기록 켜짐 (관리자)" else "🤖 자동 기록 (관리자)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                                    Text("택시앱 운행·요금 자동 기록 (탭: 설정 점검)", fontSize = 11.sp, color = muted)
                                }
                                Switch(checked = autoRec, onCheckedChange = { on ->
                                    autoRec = on
                                    // [v56] auto_record_touched: 유저가 토글을 한 번이라도 만지면 그 선택이 유일 기준(auto_free_open 무시) → OFF면 진짜 OFF.
                                    prefs.edit().putBoolean("auto_record_on", on).putBoolean("auto_record_touched", true).apply()
                                    if (on) showAutoSetup = true
                                    else try { context.stopService(Intent(context, com.callradar.app.LocationTrackingService::class.java)) } catch (e: Exception) {}
                                    com.callradar.app.Telemetry.log(context, if (on) "auto_record_on" else "auto_record_off", "home")
                                })
                            }
                        }
                        // ── 금액 자동 입력 (베타)
                        if (showNotif) {
                            Box(modifier = divider().background(AppTheme.surface2))
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f).clickable {
                                    try { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=택시투데이")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                    catch (e: Exception) { try { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/search?q=택시투데이&c=apps")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) {} }
                                }) {
                                    Text(if (capOn) "💰 금액 자동 입력 켜짐" else "💰 금액 자동 입력 (베타)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                                    Text("카드결제 알림 금액 자동 입력 (탭: 택시투데이 설치)", fontSize = 11.sp, color = muted)
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
                }
                if (showAutoSetup) com.callradar.app.screen.AutoRecordSetupDialog(context) {
                    showAutoSetup = false
                    val acc = (android.provider.Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services") ?: "").contains("com.callradar.app/com.callradar.app.NaviIntentReceiver")
                    if (acc) try { ContextCompat.startForegroundService(context, Intent(context, com.callradar.app.LocationTrackingService::class.java)) } catch (e: Exception) {}
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
                    Triple("gas", "⛽", "가스"), Triple("elec", "🔌", "전기"),
                    Triple("expense", "🧾", "지출촬영"),
                    Triple("track", "🗺️", "운행궤적"),
                    Triple("import", "📥", "가져오기"), Triple("records", "📋", "기록"),
                    Triple("airport", "✈️", "공항"), Triple("namecard", "📇", "명함"),
                    Triple("ai", "🤖", "AI비서"), Triple("events", "📅", "이벤트"),
                    Triple("bookings", "🚕", "예약"), Triple("stats", "📊", "분석"),
                    Triple("ranking", "🏆", "랭킹"), Triple("links", "🌐", "링크"),
                    Triple("settings", "⚙️", "기사설정"), Triple("more", "⋯", "더보기")
                )
                var blockCsv by remember { mutableStateOf(prefs.getString("home_blocks", "gas,elec,expense,records,import,more") ?: "gas,elec,expense,records,import,more") }
                // [v32] 기존 사용자 홈에 '지출촬영' 1회 자동 추가(레이아웃 보존 + 한 칸만 추가)
                LaunchedEffect(Unit) {
                    if (!prefs.getBoolean("home_expense_migrated", false)) {
                        val cur = prefs.getString("home_blocks", null)
                        if (cur != null && !cur.split(",").contains("expense")) {
                            val l = cur.split(",").toMutableList(); l.add(minOf(2, l.size), "expense")
                            val nv = l.joinToString(","); prefs.edit().putString("home_blocks", nv).apply(); blockCsv = nv
                        }
                        prefs.edit().putBoolean("home_expense_migrated", true).apply()
                    }
                }
                var blockEdit by remember { mutableStateOf(false) }
                val order = blockCsv.split(",").map { it.trim() }.filter { id -> registry.any { it.first == id } }
                val save: (List<String>) -> Unit = { l -> blockCsv = l.joinToString(","); prefs.edit().putString("home_blocks", blockCsv).apply() }
                val runBlock: (String) -> Unit = { id ->
                    when (id) {
                        "gas" -> com.callradar.app.ReceiptScanActivity.start(context, "가스")
                        "elec" -> com.callradar.app.ReceiptScanActivity.start(context, "전기")
                        "expense" -> com.callradar.app.ReceiptScanActivity.start(context, "지출")
                        "track" -> com.callradar.app.TrackActivity.start(context)
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
                // [v54] '정확도 %'는 우버 0원 등으로 낮게 나와 고장처럼 보였음 → 학습이 실제로 쌓이는 '누적 교정 건수'로 표시(정직·안심).
                if (accScored >= 20) {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🤖", fontSize = 24.sp, modifier = Modifier.padding(end = 12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("앱이 스스로 학습 중", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                                Text("누적 교정 ${String.format("%,d", accScored)}건을 반영해 인식을 개선하고 있어요", fontSize = 12.sp, color = muted)
                            }
                            Text("${String.format("%,d", accScored)}건", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = green)
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

// [자동기록 온보딩] 필요한 권한을 자동 감지(✅/❌)하고, 안 된 건 원탭으로 정확한 설정화면으로 이동.
//   안드로이드 보안상 마지막 스위치는 사용자가 눌러야 함 → '감지 + 안내'가 최선. 돌아오면 1.5초마다 자동 재확인.
@Composable
fun AutoRecordSetupDialog(context: Context, onDone: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1500); tick++ } }
    val refreshTrigger = tick  // tick 변화 시 재구성 → 아래 권한상태 재확인
    if (refreshTrigger < 0) return  // (사용 보장용, 실행 안 됨)

    val accOn = (Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services") ?: "").contains("com.callradar.app/com.callradar.app.NaviIntentReceiver")
    // [근본해결] 접근성이 켜지면 자동기록 토글을 자동으로 ON. "접근성만 켜고 토글 안 켜서 안 되는" 고질병 제거.
    //   (이 다이얼로그는 유저가 자동기록을 켜려고 열었을 때만 뜨므로, 접근성 켜지는 순간 켜주는 게 의도에 맞음)
    if (accOn) { try { context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).edit().putBoolean("auto_record_on", true).apply() } catch (e: Exception) {} }
    val overlayOn = try { Settings.canDrawOverlays(context) } catch (e: Exception) { false }
    val locOn = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val batteryOn = try { (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(context.packageName) } catch (e: Exception) { true }
    val notifOn = try { androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) } catch (e: Exception) { false }

    fun open(intent: Intent) { try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {} }
    val pkgUri = android.net.Uri.parse("package:" + context.packageName)

    AlertDialog(
        onDismissRequest = onDone,
        confirmButton = { TextButton(onClick = onDone) { Text(if (accOn) "완료" else "닫기") } },
        title = { Text(if (accOn) "자동기록 설정 점검" else "자동기록을 켜려면", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("아래를 켜면 자동기록이 완전히 작동해요. 안드로이드 보안상 마지막 스위치는 직접 눌러야 합니다. (돌아오면 자동으로 ✅ 표시)", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(12.dp))
                AutoSetupRow("① 접근성 (필수)", "택시앱 화면 읽어 자동기록", accOn) {
                    // [접근성 바로가기 수정] 전체 목록(ACTION_ACCESSIBILITY_SETTINGS)이 아니라 콜레이더 전용 페이지로 딥링크.
                    //   삼성 One UI 등에서 목록 깊이 묻혀 '바로가기가 안 되는' 문제 해결. 실패 시 전체 목록으로 폴백.
                    try {
                        val cn = android.content.ComponentName(context, com.callradar.app.NaviIntentReceiver::class.java).flattenToString()
                        val b = android.os.Bundle().apply { putString(":settings:fragment_args_key", cn) }
                        context.startActivity(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                            .putExtra(":settings:fragment_args_key", cn)
                            .putExtra(":settings:show_fragment_args", b)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
                // [v53 #103/#124] 삼성 '제한된 설정' — 원스토어/사이드로드 앱은 업데이트 후 접근성이 막혀 안 켜짐.
                //   앱 정보 ⋮ → '제한된 설정 허용'을 먼저 눌러야 ①접근성 스위치가 켜진다. (접근성 꺼져있을 때만 안내)
                if (android.os.Build.MANUFACTURER.contains("samsung", true) && !accOn) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("🔒", fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("①-1 접근성이 안 켜지면: '제한된 설정 허용'", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE24B4A))
                            Text("앱 정보 우측 상단 ⋮ → '제한된 설정 허용' 누른 뒤 ①접근성을 켜세요 (삼성 업데이트 후 필수)", fontSize = 11.sp, color = Color.Gray)
                        }
                        TextButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)) }) { Text("앱정보 열기") }
                    }
                }
                AutoSetupRow("② 화면 위 표시", "플로팅 배지·버튼", overlayOn) { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)) }
                AutoSetupRow("③ 위치 (항상 허용)", "GPS 운행 자동기록", locOn) { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)) }
                AutoSetupRow("④ 배터리 최적화 제외", "백그라운드서 안 끊기게", batteryOn) { open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)) }
                // [삼성 전용] 표준 배터리 최적화 제외(④)로도 One UI의 '앱 잠자기/딥슬립'이 남아 접근성이 죽는다.
                //   앱 정보 > 배터리 > '제한 없음'을 직접 눌러야 완전 해결. (이 상태는 API로 감지 불가라 항상 안내)
                if (android.os.Build.MANUFACTURER.contains("samsung", true)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("⚙️", fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("⑤ 삼성 필수: 배터리 '제한 없음'", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                            Text("연 화면에서 '배터리' → '제한 없음' 선택 (삼성은 이게 핵심)", fontSize = 11.sp, color = Color.Gray)
                        }
                        TextButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)) }) { Text("열기") }
                    }
                }
                Spacer(Modifier.height(4.dp))
                AutoSetupRow("(선택) 알림 접근", "카드 결제금액 자동입력", notifOn) { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
        }
    )
}

@Composable
private fun AutoSetupRow(title: String, desc: String, granted: Boolean, onEnable: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(if (granted) "✅" else "⬜", fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
            Text(desc, fontSize = 11.sp, color = Color.Gray)
        }
        if (!granted) TextButton(onClick = onEnable) { Text("켜기") }
        else Text("완료", fontSize = 12.sp, color = Color(0xFF10B981))
    }
}
