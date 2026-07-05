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

    private val SERVER_URL = "https://callradar-server.onrender.com"
    private val PREFS_NAME = "callradar_prefs"
    private val KEY_USER_ID = "user_id"
    private val KEY_NICKNAME = "nickname"
    private val KEY_ONBOARDING_DONE = "onboarding_done"

    private var manualEntryTrigger = false
    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) startLocationService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndStartServices()
        // 서버 웜업 (슬립 깨우기)
        Thread { try { URL("https://callradar-server.onrender.com/api/health").openConnection().getInputStream().close() } catch (e: Exception) {} }.start()
        if (intent?.getBooleanExtra("openManualEntry", false) == true) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                manualEntryTrigger = true
            }, 500)
        }
        setContent {
            CallRadarTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0E1A)) { AppContent() }
            }
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
        var isLoggedIn by remember { mutableStateOf(prefs.getString(KEY_NICKNAME, null) != null) }
        var showManualFromAlert by remember { mutableStateOf(false) }
        LaunchedEffect(manualEntryTrigger) {
            if (manualEntryTrigger) { showManualFromAlert = true; manualEntryTrigger = false }
        }
        var userNickname by remember { mutableStateOf(prefs.getString(KEY_NICKNAME, "") ?: "") }
        var userId by remember { mutableStateOf(prefs.getString(KEY_USER_ID, "") ?: "") }
        var onboardingDone by remember { mutableStateOf(prefs.getBoolean(KEY_ONBOARDING_DONE, false)) }
        var isSetupComplete by remember { mutableStateOf(prefs.getBoolean("setup_complete", false) && androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
        when {
            !isLoggedIn -> LoginScreen(onLoginSuccess = { uid, nickname ->
                prefs.edit().putString(KEY_USER_ID, uid).putString(KEY_NICKNAME, nickname).apply()
                userId = uid; userNickname = nickname; isLoggedIn = true
            })
            !onboardingDone -> OnboardingScreen(nickname = userNickname, onDone = {
                prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply(); onboardingDone = true
            })
            !isSetupComplete -> com.callradar.app.screen.SetupGuideScreen(onSetupComplete = { isSetupComplete = true })             else -> MainWithTabs(nickname = userNickname, userId = userId, onEndShift = {
                stopService(Intent(this, LocationTrackingService::class.java))
            }, onLogout = {
                prefs.edit().clear().apply()
                stopService(Intent(this, LocationTrackingService::class.java))
                isLoggedIn = false; userNickname = ""; userId = ""; onboardingDone = false
            })
        }
    }

    @Composable
    fun OnboardingScreen(nickname: String, onDone: () -> Unit) {
        val bg = Color(0xFF0A0E1A); val card = Color(0xFF111827); val accent = Color(0xFFF59E0B); val green = Color(0xFF10B981); val muted = Color(0xFF6B7280)
        var currentStep by remember { mutableStateOf(0) }
        val naviEnabled = isNaviReceiverEnabled()
        val steps = listOf(
            OnboardingStep("👋", "${nickname}님, 환영해요!", "콜레이더는 택시 운행을 자동으로 기록하고\n수입 패턴을 분석해드려요.\n\n설정 2단계만 하면 바로 시작할 수 있어요.", "시작하기", false),
            OnboardingStep("📍", "GPS 위치 권한", "운행 중 위치를 자동으로 기록해요.\n승차/하차 위치가 저장되어\n내 콜 지도를 만들 수 있어요.", "권한 허용됨 ✓", false),
            OnboardingStep("🧭", "자동 기록 권한 설정", "콜 수락 후 내비 실행 시\n출발지/목적지를 자동으로 기록해드려요.\n\n접근성 서비스에서\n'콜레이더'를 찾아 켜주세요.", if (naviEnabled) "활성화됨 ✓" else "접근성 설정 열기", !naviEnabled),
            OnboardingStep("🚀", "준비 완료!", "홈 화면에서 자동으로\n내비 목적지가 기록돼요.\n\n바로 시작해볼까요?", "시작하기", false)
        )
        Column(modifier = Modifier.fillMaxSize().background(bg).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEachIndexed { i, _ -> Box(modifier = Modifier.height(4.dp).width(if (i == currentStep) 32.dp else 16.dp).background(if (i <= currentStep) accent else Color(0xFF1F2937), RoundedCornerShape(2.dp))) }
            }
            Spacer(Modifier.height(48.dp))
            val step = steps[currentStep]
            Text(step.emoji, fontSize = 64.sp); Spacer(Modifier.height(24.dp))
            Text(step.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center); Spacer(Modifier.height(16.dp))
            Text(step.desc, fontSize = 15.sp, color = muted, textAlign = TextAlign.Center, lineHeight = 24.sp)
            if (currentStep == 2 && !isNaviReceiverEnabled()) {
                Spacer(Modifier.height(24.dp))
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        listOf("1. 아래 버튼 눌러 접근성 설정 열기", "2. '설치된 앱' 또는 '다운로드된 앱' 찾기", "3. '콜레이더' 선택", "4. 토글 켜기 → 허용").forEach { Text(it, fontSize = 13.sp, color = Color(0xFFD1D5DB), modifier = Modifier.padding(vertical = 4.dp)) }
                    }
                }
            }
            if (currentStep == 2 && isNaviReceiverEnabled()) {
                Spacer(Modifier.height(24.dp))
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B)), shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text("✅", fontSize = 20.sp); Spacer(Modifier.width(12.dp)); Text("자동 기록이 활성화되어 있어요!", fontSize = 14.sp, color = green) }
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { if (currentStep == 2 && !isNaviReceiverEnabled()) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) else if (currentStep == steps.size - 1) onDone() else currentStep++ },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (currentStep == 2 && isNaviReceiverEnabled()) green else accent), shape = RoundedCornerShape(14.dp)
            ) { Text(if (currentStep == 2 && isNaviReceiverEnabled()) "다음으로 →" else step.buttonText, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            if (currentStep == 2 && !isNaviReceiverEnabled()) { Spacer(Modifier.height(8.dp)); TextButton(onClick = { currentStep = 3 }) { Text("나중에 설정하기 (무료 기능만 사용)", color = muted, fontSize = 13.sp) } }; if (currentStep > 0) { Spacer(Modifier.height(12.dp)); TextButton(onClick = { currentStep-- }) { Text("이전", color = muted) } }
            Spacer(Modifier.height(20.dp))
        }
    }

    @Composable
    fun MainWithTabs(nickname: String, userId: String, onEndShift: () -> Unit, onLogout: () -> Unit) {
        var selectedTab by remember { mutableStateOf(0) }
        var homeRefreshKey by remember { mutableStateOf(0) }
        val accent = Color(0xFFF59E0B); val card = Color(0xFF111827); val red = Color(0xFFEF4444); val green = Color(0xFF10B981); val muted = Color(0xFF6B7280)
        var showLogoutDialog by remember { mutableStateOf(false) }
        var todaySummary by remember { mutableStateOf<TodaySummary?>(null) }
        val scope = rememberCoroutineScope()

        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                title = { Text("운행 종료", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        todaySummary?.let { s ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0E1A)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("오늘 하루 요약", fontSize = 13.sp, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("총 콜", fontSize = 14.sp, color = Color(0xFF9CA3AF)); Text("${s.tripCount}건", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = green) }
                                    if (s.todayFare > 0) { Spacer(Modifier.height(6.dp)); Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("오늘 매출", fontSize = 14.sp, color = Color(0xFF9CA3AF)); Text("${String.format("%,d", s.todayFare)}원", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent) } }
                                    if (s.topDest.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("최다 목적지", fontSize = 14.sp, color = Color(0xFF9CA3AF)); Text(s.topDest.take(15), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White) } }
                                }
                            }
                        } ?: CircularProgressIndicator(color = accent, modifier = Modifier.align(Alignment.CenterHorizontally))
                        Text("GPS 기록을 중지합니다. 로그인은 유지돼요.", color = Color(0xFF9CA3AF), fontSize = 14.sp)
                    }
                },
                confirmButton = { Button(onClick = { showLogoutDialog = false; onEndShift() }, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("종료", color = Color.White) } },
                dismissButton = { OutlinedButton(onClick = { showLogoutDialog = false }) { Text("취소") } },
                containerColor = Color(0xFF111827)
            )
        }

        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E1A))) {
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> com.callradar.app.screen.HomeScreen(
                        nickname = nickname, userId = userId,
                        refreshKey = homeRefreshKey,
                        onLogout = {
                        scope.launch {
                            try {
                                val response = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/today/$userId").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }
                                val json = JSONObject(response)
                                todaySummary = TodaySummary(json.optInt("tripCount", 0), json.optString("topDest", ""), json.optInt("todayFare", 0))
                            } catch (e: Exception) { todaySummary = TodaySummary(0, "", 0) }
                        }
                        showLogoutDialog = true
                    })
                    1 -> com.callradar.app.screen.RecordsScreen(userId = userId)
                    2 -> com.callradar.app.screen.AirportScreen()
                    3 -> com.callradar.app.screen.MoreScreen(userId = userId, onLogout = onLogout)
                }
            }
            NavigationBar(containerColor = card) {
               listOf("홈" to "🏠", "기록" to "📋", "공항" to "✈️", "더보기" to "⋯").forEachIndexed { index, (title, emoji) ->
                    NavigationBarItem(selected = selectedTab == index, onClick = { if (index == 0) { homeRefreshKey++ }; selectedTab = index }, icon = { Text(emoji, fontSize = 20.sp) }, label = { Text(title, fontSize = 11.sp, color = if (selectedTab == index) Color(0xFFF59E0B) else Color(0xFF6B7280)) })
                }
            }
        }
    }
@Composable
    fun LoginScreen(onLoginSuccess: (String, String) -> Unit) {
        val bg = Color(0xFF0A0E1A); val accent = Color(0xFFF59E0B); val muted = Color(0xFF6B7280)
        var showWebView by remember { mutableStateOf(false) }
        if (showWebView) {
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
                Text("택시 기사 수입 최적화", fontSize = 14.sp, color = muted, modifier = Modifier.padding(top = 8.dp, bottom = 60.dp))
                Button(onClick = { showWebView = true }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFE812)), shape = RoundedCornerShape(12.dp)) { Text("카카오로 시작하기", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
        }
    }

    private fun checkAndStartServices() {
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineLocation) startLocationService() else locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun startLocationService() { startForegroundService(Intent(this, LocationTrackingService::class.java)) }

    private fun isNaviReceiverEnabled(): Boolean {
        return try { Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains("com.callradar.app") == true } catch (e: Exception) { false }
    }
}
