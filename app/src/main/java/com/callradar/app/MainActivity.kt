package com.callradar.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.callradar.app.ui.theme.CallRadarTheme
import com.callradar.app.screen.AppTheme
import com.callradar.app.screen.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val SERVER_URL = Config.SERVER_URL
    private val PREFS_NAME = "callradar_prefs"
    private val KEY_USER_ID = "user_id"
    private val KEY_NICKNAME = "nickname"
    private val KEY_ONBOARDING_DONE = "onboarding_done"
    private val KEY_AUTO_LOGIN = "auto_login"   // [v17] 자동 로그인(기본 해제). 체크 안 하면 재시작 때 로그인 화면.

    companion object {
        // 이번 프로세스에서 방금 로그인했는지(딥링크 recreate·구성변경에도 유지). 콜드스타트 시 false로 리셋.
        var sessionLoggedIn = false
    }

    private var manualEntryTrigger = false
    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) startLocationService()
    }

    // [v13] 플로팅 운행 버튼 제어
    fun isOverlayGranted(): Boolean = Settings.canDrawOverlays(this)

    // [v23] 알림 자동캡처 — 알림 접근 권한 확인/요청
    fun isNotifAccessGranted(): Boolean = try {
        (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)
    } catch (e: Exception) { false }
    fun openNotifAccessSettings() {
        try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (e: Exception) {}
    }

    fun requestOverlayPermission() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (e: Exception) {}
    }

    fun startFloatingButton() {
        if (!isOverlayGranted()) { requestOverlayPermission(); return }
        try { startService(Intent(this, FloatingTripService::class.java)) } catch (e: Exception) {}
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean("floating_on", true).apply()
    }

    fun stopFloatingButton() {
        try { stopService(Intent(this, FloatingTripService::class.java)) } catch (e: Exception) {}
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean("floating_on", false).apply()
    }


    // [보안 v24] 저장된 계정(user_id)에 맞는 인증 토큰을 서버에서 받아 저장.
    //  계정 전환·구버전 설치(토큰 없음)를 다음 실행 때 자가치유. 이 요청은 옛 토큰을 보내지 않는다(불일치 403 방지).
    private fun ensureAuthToken() {
        Thread {
            try {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val uid = prefs.getString(KEY_USER_ID, "") ?: ""
                if (uid.isBlank()) return@Thread
                val androidId = try { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "" } catch (e: Exception) { "" }
                if (androidId.isEmpty()) return@Thread
                val deviceId = "guest_$androidId"
                val json = org.json.JSONObject().apply { put("device_id", deviceId); put("user_id", uid) }
                val conn = (URL("$SERVER_URL/api/auth/token").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000
                }
                conn.outputStream.write(json.toString().toByteArray())
                if (conn.responseCode in 200..299) {
                    val j = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                    val t = j.optString("token", "")
                    if (t.isNotEmpty()) com.callradar.app.Auth.save(prefs, t)
                }
            } catch (e: Exception) {}
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.callradar.app.Auth.load(getSharedPreferences(PREFS_NAME, MODE_PRIVATE))  // [보안 v24] 저장된 토큰 로드
        ensureAuthToken()  // [보안 v24] 현재 계정 토큰 서버와 재동기화(자가치유)
        // [v22] 카카오맵 SDK 초기화 (네이티브 앱 키는 BuildConfig=local.properties). 키 없으면 지도 화면에서 안내.
        try { if (BuildConfig.KAKAO_NATIVE_KEY.isNotBlank()) com.kakao.vectormap.KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_KEY) } catch (e: Exception) {}
        handleKakaoDeepLink(intent)  // [v12] 카카오 딥링크 로그인 수신
        checkAndStartServices()
        // [v13] 플로팅 버튼: 사용자가 켜뒀고 권한 있으면 재시작
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("floating_on", false) && isOverlayGranted()) {
            try { startService(Intent(this, FloatingTripService::class.java)) } catch (e: Exception) {}
        }
        // 서버 웜업 (슬립 깨우기)
        Thread { try { URL("https://callradar-server.onrender.com/api/health").openConnection().getInputStream().close() } catch (e: Exception) {} }.start()
        if (intent?.getBooleanExtra("openManualEntry", false) == true) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                manualEntryTrigger = true
            }, 500)
        }
        AppTheme.isDark = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getBoolean("dark_mode", true)
        setContent {
            CallRadarTheme {
                // [v19] 빈 곳 터치 시 키보드 내림 (숫자 입력 후 아무 데나 탭하면 키패드 사라짐)
                val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                Surface(
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures(onTap = { focusManager.clearFocus() })
                    },
                    color = AppTheme.bg
                ) { AppContent() }
            }
        }
    }

    // [v12] 브라우저(Custom Tabs)에서 callradar://auth?user_id=... 딥링크로 복귀 시 처리
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleKakaoDeepLink(intent)
    }

    private fun handleKakaoDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "callradar" && data.host == "auth") {
            val uid = data.getQueryParameter("user_id") ?: return
            val nickname = data.getQueryParameter("nickname") ?: "기사님"
            val prefsDl = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefsDl.edit().putString(KEY_USER_ID, uid).putString(KEY_NICKNAME, nickname).apply()
            com.callradar.app.Auth.save(prefsDl, data.getQueryParameter("token"))  // [보안 v24] 카카오 로그인 토큰 저장
            sessionLoggedIn = true   // [v17] 방금 로그인 → 자동로그인 체크 여부와 무관하게 이번 세션은 진입
            recreate()  // 저장 후 화면 재구성 → isLoggedIn=true 로 시작
        }
    }

    data class OnboardingStep(val emoji: String, val title: String, val desc: String, val buttonText: String, val isAction: Boolean)
    data class TodaySummary(val tripCount: Int, val topDest: String, val todayFare: Int)
    data class Badge(val emoji: String, val name: String)
    data class LevelInfo(val level: Int, val title: String, val next: Int)
    data class ProfileData(
        val nickname: String, val points: Int, val totalTrips: Int,
        val levelInfo: LevelInfo, val badges: List<Badge>,
        val guildName: String, val guildId: Int,
        val myRank: Int, val monthFare: Int,
        val carNumber: String, val employeeId: String, val workType: String,
        val driverType: String, val companyName: String
    )

    @Composable
    fun AppContent() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // [v17][#10] 자동 로그인 = 체크박스(기본 해제). 방금 로그인했거나(sessionLoggedIn),
        // 자동로그인을 켠 상태로 자격정보가 남아있을 때만 로그인 유지. 아니면 매번 로그인 화면 → 계정 전환 자유.
        var isLoggedIn by remember { mutableStateOf(sessionLoggedIn || (prefs.getBoolean(KEY_AUTO_LOGIN, true) && prefs.getString(KEY_NICKNAME, null) != null)) }
        var showManualFromAlert by remember { mutableStateOf(false) }
        LaunchedEffect(manualEntryTrigger) {
            if (manualEntryTrigger) { showManualFromAlert = true; manualEntryTrigger = false }
        }
        var userNickname by remember { mutableStateOf(prefs.getString(KEY_NICKNAME, "") ?: "") }
        var userId by remember { mutableStateOf(prefs.getString(KEY_USER_ID, "") ?: "") }
        var onboardingDone by remember { mutableStateOf(prefs.getBoolean(KEY_ONBOARDING_DONE, false)) }
        // 신규 설치에만 노출: 이미 온보딩 마친 기존 사용자는 건너뜀
        var driverTypeChosen by remember { mutableStateOf(prefs.getBoolean("driver_type_chosen", false) || prefs.getBoolean(KEY_ONBOARDING_DONE, false)) }
        var isSetupComplete by remember { mutableStateOf(prefs.getBoolean("setup_complete", false) && androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
        when {
            !isLoggedIn -> LoginScreen(onLoginSuccess = { uid, nickname ->
                prefs.edit().putString(KEY_USER_ID, uid).putString(KEY_NICKNAME, nickname).apply()
                sessionLoggedIn = true   // [v17] 게스트/페어링 로그인도 이번 세션 진입
                userId = uid; userNickname = nickname; isLoggedIn = true
            })
            !onboardingDone -> OnboardingScreen(nickname = userNickname, onDone = {
                prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply(); onboardingDone = true
            })
            !driverTypeChosen -> DriverTypePickScreen(onPicked = { type ->
                if (type != null) prefs.edit().putString("driver_type", type).apply()
                prefs.edit().putBoolean("driver_type_chosen", true).apply(); driverTypeChosen = true
            })
            !isSetupComplete -> com.callradar.app.screen.SetupGuideScreen(onSetupComplete = { isSetupComplete = true })             else -> MainWithTabs(nickname = userNickname, userId = userId, onEndShift = {
                stopService(Intent(this, LocationTrackingService::class.java)); finishAffinity()
            }, onLogout = {
                // [v17][#10] 완전 로그아웃 = 자동로그인 플래그 + 계정 자격정보만 제거 → 로그인 화면.
                // 설정·기록(SharedPreferences 그 외 키·로컬 DB)은 보존해 데이터가 꼬이지 않게 한다.
                prefs.edit()
                    .remove(KEY_USER_ID)
                    .remove(KEY_NICKNAME)
                    .putBoolean(KEY_AUTO_LOGIN, false)
                    .apply()
                com.callradar.app.Auth.clear(prefs)  // [보안 v24] 로그아웃 시 토큰 제거(옛 토큰으로 403 방지)
                sessionLoggedIn = false
                stopService(Intent(this, LocationTrackingService::class.java))
                isLoggedIn = false; userNickname = ""; userId = ""
            })
        }
    }

    @Composable
    fun DriverTypePickScreen(onPicked: (String?) -> Unit) {
        val bg = AppTheme.bg; val card = AppTheme.card; val accent = Color(0xFFF59E0B); val muted = Color(0xFF6B7280)
        Column(modifier = Modifier.fillMaxSize().background(bg).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🚕", fontSize = 44.sp)
            Spacer(Modifier.height(12.dp))
            Text("어떤 기사님이세요?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
            Text("정산 방식이 달라져요 · 나중에 설정에서 바꿀 수 있어요", fontSize = 13.sp, color = muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 24.dp))
            listOf(Triple("🚖 개인택시", "내 차, 내 매출", "personal"), Triple("🏢 법인택시", "회사 소속 · 사납금 정산", "corporate"), Triple("🤝 조합택시", "우선 법인과 동일 정산", "corporate")).forEach { (title, desc, type) ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onPicked(type) }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppTheme.text); Text(desc, fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp)) }
                        Text("›", fontSize = 22.sp, color = accent)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { onPicked(null) }) { Text("나중에 선택할게요", fontSize = 14.sp, color = muted) }
        }
    }

    @Composable
    fun OnboardingScreen(nickname: String, onDone: () -> Unit) {
        val bg = AppTheme.bg; val card = AppTheme.card; val accent = Color(0xFFF59E0B); val green = Color(0xFF10B981); val muted = Color(0xFF6B7280)
        var currentStep by remember { mutableStateOf(0) }
        val steps = listOf(
            OnboardingStep("👋", "${nickname}님, 환영해요!", "콜레이더는 택시 기사를 위한\n수입 관리 + 콜 정보 앱이에요.\n\n함께 만들어가는 기사 커뮤니티입니다.", "다음", false),
            OnboardingStep("📊", "무료로 이런 걸 쓸 수 있어요", "· 수입·지출 기록과 통계\n· 인천공항 실시간 입국 정보\n· LPG·사납금 정산 계산\n· 손님 응대 회화카드\n· 기사 랭킹", "다음", false),
            OnboardingStep("📡", "콜 제보로 함께 만드는 콜 지도", "\"여기 콜 많아요\" 한 번의 제보가\n모두의 실시간 콜 지도가 됩니다.\n\n제보가 쌓이면 AI가 '지금 어디 가면\n콜이 많은지' 추천해드려요.\n\n제보 많은 기사님께는\n정보원 배지와 포인트를 드려요!", "다음", false),
            OnboardingStep("🚀", "준비 완료!", "지금 바로 시작해보세요.\n\n운행 기록을 남기고,\n콜을 제보하고, 공항 정보를 확인하세요.\n\n함께 만들어가요!", "시작하기", false)
        )
        Column(modifier = Modifier.fillMaxSize().background(bg).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEachIndexed { i, _ -> Box(modifier = Modifier.height(4.dp).width(if (i == currentStep) 32.dp else 16.dp).background(if (i <= currentStep) accent else AppTheme.surface2, RoundedCornerShape(2.dp))) }
            }
            Spacer(Modifier.height(48.dp))
            val step = steps[currentStep]
            Text(step.emoji, fontSize = 64.sp); Spacer(Modifier.height(24.dp))
            Text(step.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppTheme.text, textAlign = TextAlign.Center); Spacer(Modifier.height(16.dp))
            Text(step.desc, fontSize = 15.sp, color = muted, textAlign = TextAlign.Center, lineHeight = 24.sp)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { if (currentStep == steps.size - 1) onDone() else currentStep++ },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(14.dp)
            ) { Text(step.buttonText, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            if (currentStep > 0) { Spacer(Modifier.height(12.dp)); TextButton(onClick = { currentStep-- }) { Text("이전", color = muted) } }
            Spacer(Modifier.height(20.dp))
        }
    }

    @Composable
    fun MainWithTabs(nickname: String, userId: String, onEndShift: () -> Unit, onLogout: () -> Unit) {
        var selectedTab by remember { mutableStateOf(0) }
        var showDailySettlement by remember { mutableStateOf(false) }  // [v13] 일일마감 오버레이 화면
        var homeRefreshKey by remember { mutableStateOf(0) }
        val accent = Color(0xFFF59E0B); val card = AppTheme.card; val red = Color(0xFFEF4444); val green = Color(0xFF10B981); val muted = Color(0xFF6B7280)
        var showLogoutDialog by remember { mutableStateOf(false) }
        var todaySummary by remember { mutableStateOf<TodaySummary?>(null) }
        var openSettleTick by remember { mutableStateOf(0) }   // [v19] 홈 '기사 설정' → 더보기 정산설정 열기 신호
        var moreRoute by remember { mutableStateOf("") }       // [v21] 홈 블록 → 더보기 특정 하위화면 열기
        val scope = rememberCoroutineScope()

        // [v19] 홈이 아닌 탭에서 폰 뒤로가기 → 앱 종료 대신 홈 탭으로.
        // (더보기 하위화면이 열려 있으면 MoreScreen의 BackHandler가 먼저 처리)
        BackHandler(enabled = selectedTab != 0 && !showDailySettlement) { selectedTab = 0 }

        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                title = { Text("운행 종료", color = AppTheme.text, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        todaySummary?.let { s ->
                            Card(colors = CardDefaults.cardColors(containerColor = AppTheme.bg), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("오늘 하루 요약", fontSize = 13.sp, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("총 콜", fontSize = 14.sp, color = Color(0xFF9CA3AF)); Text("${s.tripCount}건", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = green) }
                                    if (s.todayFare > 0) { Spacer(Modifier.height(6.dp)); Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("오늘 매출", fontSize = 14.sp, color = Color(0xFF9CA3AF)); Text("${String.format("%,d", s.todayFare)}원", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent) } }
                                    if (s.topDest.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("최다 목적지", fontSize = 14.sp, color = Color(0xFF9CA3AF)); Text(s.topDest.take(15), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text) } }
                                }
                            }
                        } ?: CircularProgressIndicator(color = accent, modifier = Modifier.align(Alignment.CenterHorizontally))
                        Text("GPS 기록을 중지합니다. 로그인은 유지돼요.", color = Color(0xFF9CA3AF), fontSize = 14.sp)
                    }
                },
                confirmButton = { Button(onClick = { showLogoutDialog = false; onEndShift() }, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("종료", color = AppTheme.text) } },
                dismissButton = { OutlinedButton(onClick = { showLogoutDialog = false }) { Text("취소") } },
                containerColor = AppTheme.card
            )
        }

        Column(modifier = Modifier.fillMaxSize().background(AppTheme.bg)) {
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> com.callradar.app.screen.HomeScreen(
                        nickname = nickname, userId = userId,
                        refreshKey = homeRefreshKey,
                        onLogout = {
                        scope.launch {
                            try {
                                val response = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/today/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }
                                val json = JSONObject(response)
                                todaySummary = TodaySummary(json.optInt("tripCount", 0), json.optString("topDest", ""), json.optInt("todayFare", 0))
                            } catch (e: Exception) { todaySummary = TodaySummary(0, "", 0) }
                        }
                        showLogoutDialog = true
                    }, onOpenSettings = { moreRoute = ""; selectedTab = 4; openSettleTick++ }, onNavTab = { selectedTab = it }, onNavMore = { r -> moreRoute = r; selectedTab = 4; openSettleTick++ }, onToggleFloating = { on -> if (on) startFloatingButton() else stopFloatingButton() }, isOverlayGranted = { isOverlayGranted() }, onToggleNotifCapture = { on -> if (on && !isNotifAccessGranted()) openNotifAccessSettings() }, isNotifAccessGranted = { isNotifAccessGranted() })
                    1 -> com.callradar.app.screen.RadarScreen(userId = userId)
                    2 -> com.callradar.app.screen.RecordsScreen(userId = userId, onOpenDailySettlement = { showDailySettlement = true }, onOpenSettings = { moreRoute = ""; selectedTab = 4; openSettleTick++ })
                    3 -> com.callradar.app.screen.AirportScreen()
                    4 -> com.callradar.app.screen.MoreScreen(userId = userId, onLogout = onLogout, onOpenDailySettlement = { showDailySettlement = true }, openSettleTick = openSettleTick, openRoute = moreRoute)
                }
            }
            NavigationBar(containerColor = card) {
               listOf("홈" to "🏠", "레이더" to "📡", "기록" to "📋", "공항" to "✈️", "더보기" to "⋯").forEachIndexed { index, (title, emoji) ->
                    NavigationBarItem(selected = selectedTab == index, onClick = { if (index == 0) { homeRefreshKey++ }; selectedTab = index; com.callradar.app.Telemetry.log(this@MainActivity, "open_tab", listOf("home","radar","records","airport","more").getOrElse(index) { "" }) }, icon = { Text(emoji, fontSize = 20.sp) }, label = { Text(title, fontSize = 11.sp, color = if (selectedTab == index) Color(0xFFF59E0B) else Color(0xFF6B7280)) })
                }
            }
        }

        // [v13] 일일마감 전체화면 오버레이
        if (showDailySettlement) {
            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize().background(AppTheme.bg)) {
                com.callradar.app.screen.DailySettlementScreen(userId = userId, onClose = { showDailySettlement = false })
            }
        }
    }
@Composable
    fun LoginScreen(onLoginSuccess: (String, String) -> Unit) {
        val bg = AppTheme.bg; val accent = Color(0xFFF59E0B); val muted = Color(0xFF6B7280)
        val context = androidx.compose.ui.platform.LocalContext.current  // [v12] 브라우저 로그인용
        val loginPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var autoLogin by remember { mutableStateOf(loginPrefs.getBoolean(KEY_AUTO_LOGIN, true)) }  // [v19] 기본 켜짐(업데이트해도 로그인 유지). 계정 전환은 '다른 계정으로 로그인'/로그아웃.
        var showWebView by remember { mutableStateOf(false) }
        if (false && showWebView) {  // [v12] WebView 로그인 비활성화 (브라우저 방식으로 대체)
            AndroidView(factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString() ?: return false
                            if (url.startsWith("$SERVER_URL/auth/kakao/callback")) { val uri = Uri.parse(url); val userId = uri.getQueryParameter("user_id"); val nickname = uri.getQueryParameter("nickname") ?: "기사님"; if (userId != null) { onLoginSuccess(userId, nickname); return true } }
                            return false
                        }
                    }
                    loadUrl("$SERVER_URL/auth/kakao")
                }
            }, modifier = Modifier.fillMaxSize())
        } else {
            Column(modifier = Modifier.fillMaxSize().background(bg).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("콜레이더", fontSize = 42.sp, fontWeight = FontWeight.Bold, color = accent)
                Text("택시 기사 수입 최적화", fontSize = 14.sp, color = muted, modifier = Modifier.padding(top = 8.dp, bottom = 40.dp))

                // [v17][#10] 자동 로그인 체크박스 (기본 해제). 체크하면 다음 실행 때 로그인 화면 없이 바로 진입.
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        autoLogin = !autoLogin
                        loginPrefs.edit().putBoolean(KEY_AUTO_LOGIN, autoLogin).apply()
                    }.padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = autoLogin,
                        onCheckedChange = { checked -> autoLogin = checked; loginPrefs.edit().putBoolean(KEY_AUTO_LOGIN, checked).apply() },
                        colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = muted)
                    )
                    Text("자동 로그인 (이 기기에서 계속 로그인 유지)", fontSize = 13.sp, color = AppTheme.text)
                }

                Button(onClick = {
                    // [v12] WebView 대신 외부 브라우저로 카카오 로그인 (입력 뒤집힘 해결 + 카카오톡 간편로그인 지원)
                    // 로그인 완료 후 서버가 callradar://auth 딥링크로 앱에 복귀시킴
                    loginPrefs.edit().putBoolean(KEY_AUTO_LOGIN, autoLogin).apply()  // [v17] 체크 상태 확정 저장
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$SERVER_URL/auth/kakao"))) } catch (e: Exception) {}
                }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFE812)), shape = RoundedCornerShape(12.dp)) { Text("카카오로 시작하기", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp) }

                // [v17][#10] 다른 계정으로 로그인 — 카카오 재인증 강제(prompt=login)로 이전 계정 자동복귀 방지
                TextButton(onClick = {
                    loginPrefs.edit().putBoolean(KEY_AUTO_LOGIN, autoLogin).apply()
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$SERVER_URL/auth/kakao?prompt=login"))) } catch (e: Exception) {}
                }) { Text("다른 계정으로 로그인", fontSize = 13.sp, color = muted) }

                Spacer(modifier = Modifier.height(12.dp))
                // [v18] 서브폰(2·3폰) 페어링 로그인 — 카카오 바로 아래. 주폰=카카오, 서브폰=이 버튼
                var showPair by remember { mutableStateOf(false) }
                var pairCode by remember { mutableStateOf("") }
                var pairMsg by remember { mutableStateOf("") }
                val pairScope = rememberCoroutineScope()
                OutlinedButton(onClick = { showPair = true }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.5.dp, accent)) {
                    Text("📱 다른 폰 계정 연결 (2·3폰 서브폰)", color = accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 게스트 로그인 (카카오 없이 바로 시작)
                var guestLoading by remember { mutableStateOf(false) }
                val guestScope = rememberCoroutineScope()
                OutlinedButton(
                    onClick = {
                        if (guestLoading) return@OutlinedButton
                        guestLoading = true
                        guestScope.launch {
                            try {
                                val androidId = try { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "" } catch (e: Exception) { "" }
                                val deviceId = if (androidId.isNotEmpty()) "guest_$androidId" else "guest_${System.currentTimeMillis()}"
                                val resp = withContext(Dispatchers.IO) {
                                    val json = org.json.JSONObject().apply { put("device_id", deviceId); put("nickname", "기사님") }
                                    val conn = (URL("$SERVER_URL/api/auth/guest").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000 }
                                    conn.outputStream.write(json.toString().toByteArray())
                                    conn.inputStream.bufferedReader().readText()
                                }
                                val j = org.json.JSONObject(resp)
                                val uid = j.optString("user_id", "")
                                val nick = j.optString("nickname", "기사님")
                                if (uid.isNotEmpty()) { com.callradar.app.Auth.save(getSharedPreferences(PREFS_NAME, MODE_PRIVATE), j.optString("token", "")); onLoginSuccess(uid, nick) } else guestLoading = false
                            } catch (e: Exception) { guestLoading = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF374151))
                ) {
                    Text(if (guestLoading) "시작하는 중..." else "게스트로 시작하기", color = AppTheme.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Text("로그인 없이 바로 사용해보세요", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 10.dp))

                // [v18] 서브폰 페어링 다이얼로그 (버튼은 위 카카오 로그인 아래로 이동)
                if (showPair) {
                    AlertDialog(
                        onDismissRequest = { showPair = false },
                        title = { Text("계정 연결", color = AppTheme.text, fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                Text("주폰에서 '더보기 → 다른 폰 연결'로 뜬 6자리 코드를 입력하세요.", fontSize = 12.sp, color = muted)
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(value = pairCode, onValueChange = { v -> pairCode = v.filter { it.isDigit() }.take(6) }, singleLine = true, label = { Text("6자리 코드") })
                                if (pairMsg.isNotEmpty()) { Spacer(modifier = Modifier.height(6.dp)); Text(pairMsg, fontSize = 12.sp, color = Color(0xFFEF4444)) }
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                if (pairCode.length != 6) { pairMsg = "6자리 코드를 입력하세요"; return@Button }
                                pairMsg = ""
                                pairScope.launch {
                                    try {
                                        val androidId = try { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "" } catch (e: Exception) { "" }
                                        val deviceId = if (androidId.isNotEmpty()) "guest_$androidId" else "guest_${System.currentTimeMillis()}"
                                        val resp = withContext(Dispatchers.IO) {
                                            val json = JSONObject().apply { put("device_id", deviceId); put("code", pairCode); put("label", "서브폰") }
                                            val conn = (URL("$SERVER_URL/api/pair/claim").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000 }
                                            conn.outputStream.write(json.toString().toByteArray())
                                            val rc = conn.responseCode
                                            val body = (if (rc in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                                            Pair(rc, body)
                                        }
                                        val j = try { JSONObject(resp.second) } catch (e: Exception) { JSONObject() }
                                        if (resp.first in 200..299 && j.optString("user_id", "").isNotEmpty()) {
                                            showPair = false
                                            com.callradar.app.Auth.save(getSharedPreferences(PREFS_NAME, MODE_PRIVATE), j.optString("token", ""))  // [보안 v24] 서브폰 토큰 저장
                                            onLoginSuccess(j.optString("user_id", ""), j.optString("nickname", "기사님"))
                                        } else {
                                            pairMsg = j.optString("error", "연결 실패 — 코드를 확인하세요")
                                        }
                                    } catch (e: Exception) { pairMsg = "연결 실패 — 네트워크를 확인하세요" }
                                }
                            }) { Text("연결") }
                        },
                        dismissButton = { OutlinedButton(onClick = { showPair = false }) { Text("취소") } },
                        containerColor = AppTheme.card
                    )
                }
            }
        }
    }

    private fun checkAndStartServices() {
        // 무료 버전: 위치 권한만 요청 (콜 제보용), 포그라운드 추적 서비스는 시작 안 함
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineLocation) locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun startLocationService() { /* 무료 버전: 포그라운드 위치 서비스 미사용 */ }

    private fun isNaviReceiverEnabled(): Boolean {
        return try { Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains("com.callradar.app") == true } catch (e: Exception) { false }
    }
}
