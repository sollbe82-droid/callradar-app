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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    @Volatile private var backupRestored = false   // BackupSync.restore 완료 여부(완료 후에만 서버 백업 push → 부분데이터 덮어쓰기 방지)
    private val PREFS_NAME = "callradar_prefs"
    private val KEY_USER_ID = "user_id"
    private val KEY_NICKNAME = "nickname"
    private val KEY_ONBOARDING_DONE = "onboarding_done"
    private val KEY_AUTO_LOGIN = "auto_login"   // [v17] 자동 로그인(기본 해제). 체크 안 하면 재시작 때 로그인 화면.

    companion object {
        // 이번 프로세스에서 방금 로그인했는지(딥링크 recreate·구성변경에도 유지). 콜드스타트 시 false로 리셋.
        var sessionLoggedIn = false
        // [설치 도움말 재진입] 메뉴에서 온보딩 마법사를 복습 모드로 다시 열기
        val wizardReopen = androidx.compose.runtime.mutableStateOf(false)
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
                    requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 30000
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
        // [위치정보법 동의 게이트] 위치 수집·서비스 시작 전에 필수 동의부터. 미동의면 동의화면으로 보내고 여기서 중단.
        //  (동의 후 ConsentActivity가 MainActivity를 다시 띄우면 이 검사를 통과해 정상 진행)
        if (com.callradar.app.ConsentActivity.needed(this)) {
            startActivity(android.content.Intent(this, com.callradar.app.ConsentActivity::class.java))
            finish(); return
        }
        // [업데이트 유령 플로팅] 앱을 직접 열었으니 업데이트 후 억제 상태 해제 → 서비스/플로팅 정상 재개.
        try { getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean("float_suppressed", false).apply() } catch (e: Exception) {}
        // [알림 리스너 재바인딩] 앱 재설치/업데이트 후엔 알림접근 권한은 남지만 실제 바인딩이 끊긴다
        //   (안드로이드 알려진 동작) → 카드승인 알림([택시승인]) 캡처가 조용히 멈춤. 시작 시 강제 재바인딩.
        try {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(this, com.callradar.app.CallCaptureService::class.java)
            )
        } catch (e: Exception) {}
        com.callradar.app.Auth.load(getSharedPreferences(PREFS_NAME, MODE_PRIVATE))  // [보안 v24] 저장된 토큰 로드
        ensureAuthToken()  // [보안 v24] 현재 계정 토큰 서버와 재동기화(자가치유)
        com.callradar.app.BackupSync.restore(this) { backupRestored = true }  // [v32] 기변 첫 실행 복원(로컬에 없는 키만). 완료 표시 → onStop의 pushAll이 부분데이터로 서버백업 덮는 레이스 방지
        // [v22] 카카오맵 SDK 초기화 (네이티브 앱 키는 BuildConfig=local.properties). 키 없으면 지도 화면에서 안내.
        try { if (BuildConfig.KAKAO_NATIVE_KEY.isNotBlank()) com.kakao.vectormap.KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_KEY) } catch (e: Exception) {}
        // [v43] 카카오 로그인 SDK 초기화(네이티브 1탭 로그인). 지도와 같은 네이티브 키 사용.
        try { if (BuildConfig.KAKAO_NATIVE_KEY.isNotBlank()) com.kakao.sdk.common.KakaoSdk.init(this, BuildConfig.KAKAO_NATIVE_KEY) } catch (e: Exception) {}
        handleKakaoDeepLink(intent)  // [v12] 카카오 딥링크 로그인 수신
        checkAndStartServices()
        // [v13] 플로팅 버튼: 사용자가 켜뒀고 권한 있으면 재시작
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("floating_on", false) && isOverlayGranted()) {
            try { startService(Intent(this, FloatingTripService::class.java)) } catch (e: Exception) {}
        }
        // 서버 웜업 (슬립 깨우기) — 타임아웃 없으면 무한 대기/소켓 누수
        Thread { try { (URL("https://callradar-server.onrender.com/api/health").openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 30000 }.getInputStream().close() } catch (e: Exception) {} }.start()
        // [v31] 로컬에 남은 pending 트립·지출 재전송 — 앱 열 때마다. [A-Z] 로컬DB 읽기를 백그라운드로(메인스레드 I/O 제거).
        Thread { try { val ldb = com.callradar.app.LocalTripDatabase.getInstance(this); ldb.syncPendingTrips(this); ldb.syncFareUpdates(); ldb.syncPendingExpenses(this) } catch (e: Exception) {} }.start()
        com.callradar.app.TrackSync.uploadRecent(this)   // [v44] 최근 궤적 서버 백업(기기변경 대비)
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

    // [v32] 앱을 벗어날 때 회사프로필·급여설정을 서버에 백업(기변 대비)
    //  ★복원이 끝난 뒤에만 백업 — 복원 전에 push하면 아직 안 채워진 로컬(부분)로 서버 백업을 덮어 프로필 유실 위험.
    override fun onStop() {
        super.onStop()
        if (!backupRestored) return
        try { com.callradar.app.BackupSync.pushAll(this) } catch (e: Exception) {}
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
            val token = data.getQueryParameter("token")
            // [보안] 딥링크는 외부(브라우저/타앱)에서도 던질 수 있다(BROWSABLE). user_id/token을 무검증 저장하면
            //  임의 계정으로 세션이 바뀔 수 있음 → 서버 whoami로 "이 토큰의 실제 주인 = uid" 확인된 경우에만 저장.
            if (token.isNullOrBlank()) return
            Thread {
                var okUid: String? = null
                var mismatch = false   // 서버가 '다른 주인'이라고 명확히 답한 경우(진짜 실패)와 네트워크 오류 구분
                // Render 콜드스타트(최대 30~50s) 대비 최대 2회 시도. 명확한 불일치면 즉시 중단.
                for (attempt in 0 until 2) {
                    try {
                        val conn = (URL("$SERVER_URL/api/auth/whoami").openConnection().apply {
                            setRequestProperty("Authorization", "Bearer $token")
                        } as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 8000; readTimeout = 45000 }
                        val code = conn.responseCode
                        if (code in 200..299) {
                            val who = org.json.JSONObject(conn.inputStream.bufferedReader().readText()).optString("user_id", "")
                            if (who.isNotBlank() && who == uid) okUid = who else mismatch = true
                        } else if (code == 401 || code == 403) { mismatch = true }
                        conn.disconnect()
                    } catch (e: Exception) {}
                    if (okUid != null || mismatch) break
                }
                runOnUiThread {
                    if (okUid != null) {
                        val prefsDl = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        prefsDl.edit().putString(KEY_USER_ID, uid).putString(KEY_NICKNAME, nickname).apply()
                        com.callradar.app.Auth.save(prefsDl, token)
                        sessionLoggedIn = true
                        recreate()
                    } else {
                        android.widget.Toast.makeText(this, "로그인 확인에 실패했어요. 다시 시도해 주세요.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
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
        // [v23] 온보딩 갇힘 해소: 전체화면 권한게이트 제거 → 홈 바로 진입 + 작은 위치권한 팝업. 신규 설치만 1회 노출.
        var showSetupPopup by remember { mutableStateOf(!prefs.getBoolean("setup_complete", false)) }
        // [온보딩 자동 마법사] 위치 팝업 다음 1회 — 자동화 권한 3종(오버레이·접근성·알림접근) 단계 안내
        var showAutoWizard by remember { mutableStateOf(!prefs.getBoolean("auto_wizard_done", false)) }
        when {
            !isLoggedIn -> LoginScreen(onLoginSuccess = { uid, nickname ->
                prefs.edit().putString(KEY_USER_ID, uid).putString(KEY_NICKNAME, nickname).apply()
                sessionLoggedIn = true   // [v17] 게스트/페어링 로그인도 이번 세션 진입
                userId = uid; userNickname = nickname; isLoggedIn = true
            })
            else -> {
                // [v32] 온보딩·기사유형 선택을 전체화면 대신 홈 위 팝업으로. 홈이 뒤에 보여 갇힘 없음.
                val onEndShiftCb: () -> Unit = {
                    stopService(Intent(this, LocationTrackingService::class.java)); finishAffinity()
                }
                val onLogoutCb: () -> Unit = {
                    // [v17][#10] 완전 로그아웃 = 자동로그인 플래그 + 계정 자격정보만 제거 → 로그인 화면.
                    prefs.edit().remove(KEY_USER_ID).remove(KEY_NICKNAME).putBoolean(KEY_AUTO_LOGIN, false).apply()
                    com.callradar.app.Auth.clear(prefs)
                    sessionLoggedIn = false
                    stopService(Intent(this, LocationTrackingService::class.java))
                    isLoggedIn = false; userNickname = ""; userId = ""
                }
                // [심플 홈 · 옵트인] home_mode=simple이면 무탭 심플 UI, 아니면 기존 탭 UI. 전환은 recreate()로 재읽기.
                val homeMode = prefs.getString("home_mode", "classic") ?: "classic"
                if (homeMode == "simple") {
                    SimpleMain(nickname = userNickname, userId = userId, onEndShift = onEndShiftCb, onLogout = onLogoutCb)
                } else {
                    MainWithTabs(nickname = userNickname, userId = userId, onEndShift = onEndShiftCb, onLogout = onLogoutCb)
                }
                // 팝업은 한 번에 하나씩: 온보딩 → 기사유형 → 위치권한 설정
                if (!onboardingDone) OnboardingPopup(nickname = userNickname, onDone = {
                    // [정식버전] 신규 유저는 간편모드로 시작 (기존 유저의 미설정 상태는 건드리지 않음 — 온보딩 시점에만)
                    val e = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true)
                    if (prefs.getString("home_mode", null) == null) e.putString("home_mode", "simple")
                    e.apply(); onboardingDone = true
                })
                else if (!driverTypeChosen) DriverTypePopup(onPicked = { type ->
                    if (type != null) prefs.edit().putString("driver_type", type).apply()
                    prefs.edit().putBoolean("driver_type_chosen", true).apply(); driverTypeChosen = true
                })
                else if (showSetupPopup) com.callradar.app.screen.SetupPopup(onFinish = { showSetupPopup = false })
                else if (showAutoWizard) com.callradar.app.screen.AutoSetupWizardPopup(onFinish = { startFloat ->
                    showAutoWizard = false
                    if (startFloat) startFloatingButton()   // 오버레이 허용됐으면 운행 버튼 즉시 표시
                })
                // [설치 도움말] 메뉴에서 재진입한 복습 모드 — 다 켜져 있어도 화면 표시
                if (wizardReopen.value) com.callradar.app.screen.AutoSetupWizardPopup(force = true, onFinish = { startFloat ->
                    wizardReopen.value = false
                    if (startFloat) startFloatingButton()
                })
            }
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

    // [v32] 온보딩 팝업 — 홈 위에 뜨는 카드형. 내용은 기존 OnboardingScreen과 동일.
    @Composable
    fun OnboardingPopup(nickname: String, onDone: () -> Unit) {
        val card = AppTheme.card; val accent = Color(0xFFF59E0B); val muted = Color(0xFF6B7280)
        var currentStep by remember { mutableStateOf(0) }
        val steps = listOf(
            OnboardingStep("👋", "${nickname}님, 환영해요!", "콜레이더는 택시 기사를 위한\n수입 관리 + 콜 정보 앱이에요.\n\n함께 만들어가는 기사 커뮤니티입니다.", "다음", false),
            OnboardingStep("📊", "무료로 이런 걸 쓸 수 있어요", "· 수입·지출 기록과 통계\n· 인천공항 실시간 입국 정보\n· LPG·사납금 정산 계산\n· 급여명세서 스캔·예상급여\n· 기사 랭킹", "다음", false),
            OnboardingStep("📡", "콜 제보로 함께 만드는 콜 지도", "\"여기 콜 많아요\" 한 번의 제보가\n모두의 실시간 콜 지도가 됩니다.\n\n제보 많은 기사님께는\n정보원 배지와 포인트를 드려요!", "다음", false),
            OnboardingStep("🚀", "준비 완료!", "지금 바로 시작해보세요.\n\n운행 기록을 남기고,\n콜을 제보하고, 공항 정보를 확인하세요.", "시작하기", false)
        )
        androidx.compose.ui.window.Dialog(onDismissRequest = { /* 닫기 방지: 시작하기로만 종료 */ }, properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Surface(shape = RoundedCornerShape(20.dp), color = card, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 20.dp)) {
                        steps.forEachIndexed { i, _ -> Box(modifier = Modifier.height(4.dp).width(if (i == currentStep) 28.dp else 14.dp).background(if (i <= currentStep) accent else AppTheme.surface2, RoundedCornerShape(2.dp))) }
                    }
                    val step = steps[currentStep]
                    Text(step.emoji, fontSize = 52.sp); Spacer(Modifier.height(16.dp))
                    Text(step.title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = AppTheme.text, textAlign = TextAlign.Center); Spacer(Modifier.height(12.dp))
                    Text(step.desc, fontSize = 14.sp, color = muted, textAlign = TextAlign.Center, lineHeight = 22.sp)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { if (currentStep == steps.size - 1) onDone() else currentStep++ },
                        modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(14.dp)) {
                        Text(step.buttonText, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    if (currentStep > 0) TextButton(onClick = { currentStep-- }) { Text("이전", color = muted) }
                    else TextButton(onClick = { onDone() }) { Text("건너뛰기", color = muted, fontSize = 13.sp) }
                }
            }
        }
    }

    // [v32] 기사 유형 선택 팝업 — 홈 위 카드형.
    @Composable
    fun DriverTypePopup(onPicked: (String?) -> Unit) {
        val card = AppTheme.card; val accent = Color(0xFFF59E0B); val muted = Color(0xFF6B7280)
        androidx.compose.ui.window.Dialog(onDismissRequest = { onPicked(null) }) {
            Surface(shape = RoundedCornerShape(20.dp), color = card, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🚕", fontSize = 36.sp)
                    Text("어떤 기사님이세요?", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppTheme.text, modifier = Modifier.padding(top = 8.dp))
                    Text("정산 방식이 달라져요 · 나중에 설정에서 바꿀 수 있어요", fontSize = 12.sp, color = muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                    listOf(Triple("🚖 개인택시", "내 차, 내 매출", "personal"), Triple("🏢 법인택시", "회사 소속 · 사납금 정산", "corporate"), Triple("🤝 조합택시", "우선 법인과 동일 정산", "corporate")).forEach { (title, desc, type) ->
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onPicked(type) }, colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(14.dp)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppTheme.text); Text(desc, fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp)) }
                                Text("›", fontSize = 20.sp, color = accent)
                            }
                        }
                    }
                    TextButton(onClick = { onPicked(null) }, modifier = Modifier.padding(top = 6.dp)) { Text("나중에 선택할게요", fontSize = 13.sp, color = muted) }
                }
            }
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

    // [심플 홈 · 옵트인 B안] 무탭 모드 라우팅. 기존 화면 composable을 재사용(기능 무손상) + B 뒤로가기 헤더로 감쌈.
    @Composable
    fun SimpleMain(nickname: String, userId: String, onEndShift: () -> Unit, onLogout: () -> Unit) {
        var route by remember { mutableStateOf("home") }
        var showSettle by remember { mutableStateOf(false) }
        val switchClassic: () -> Unit = {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString("home_mode", "classic").apply()
            recreate()
        }
        BackHandler(enabled = route != "home" || showSettle) { if (showSettle) showSettle = false else route = "home" }
        val openCard: (String) -> Unit = { id ->
            when (id) {
                "track" -> try { com.callradar.app.TrackActivity.start(this@MainActivity) } catch (e: Exception) {}
                "settlement" -> showSettle = true
                "knowhow" -> try { com.callradar.app.KnowhowActivity.start(this@MainActivity) } catch (e: Exception) {}   // [v83] 내 노하우 콜카드
                "events" -> try { com.callradar.app.EventsActivity.start(this@MainActivity) } catch (e: Exception) {}   // [행사개편] 행사 수요 예보
                "salary" -> try { com.callradar.app.CompanyProfileActivity.start(this@MainActivity) } catch (e: Exception) {}   // [v83] 월급 예상
                "tax" -> try { com.callradar.app.TaxReportActivity.start(this@MainActivity) } catch (e: Exception) {}   // [v83] 세무 리포트 노출
                else -> route = id
            }
        }
        Box(modifier = Modifier.fillMaxSize().background(AppTheme.bg)) {
            when (route) {
                "home" -> com.callradar.app.screen.SimpleHomeScreen(userId = userId, onOpenMenu = { route = "menu" }, onOpenCard = openCard,
                    onToggleFloating = { on -> if (on) startFloatingButton() else stopFloatingButton() },
                    isOverlayGranted = { isOverlayGranted() },
                    onToggleNotifCapture = { on -> if (on && !isNotifAccessGranted()) openNotifAccessSettings() },
                    isNotifAccessGranted = { isNotifAccessGranted() })
                "menu" -> com.callradar.app.screen.SimpleMenuScreen(onBack = { route = "home" }, onOpen = openCard, onFullMenu = { route = "full" }, onSwitchClassic = switchClassic)
                "records" -> SimpleWrap("기록·정산", { route = "home" }) { com.callradar.app.screen.RecordsScreen(userId = userId, onOpenDailySettlement = { showSettle = true }, onOpenSettings = { route = "full" }, embedded = true) }
                "radar" -> SimpleWrap("레이더", { route = "home" }) { com.callradar.app.screen.RadarScreen(userId = userId, embedded = true) }
                "airport" -> SimpleWrap("공항", { route = "home" }) { com.callradar.app.screen.AirportScreen() }
                "stats" -> SimpleWrap("분석", { route = "home" }) { com.callradar.app.screen.Stats2Screen(userId = userId) }   // [분석 2.0] 브리핑·KPI·히트맵
                "ranking" -> SimpleWrap("랭킹", { route = "home" }) { com.callradar.app.screen.RankingScreen(userId = userId) }
                "full" -> com.callradar.app.screen.MoreScreen(userId = userId, onLogout = onLogout, onOpenDailySettlement = { showSettle = true })
                else -> com.callradar.app.screen.SimpleHomeScreen(userId = userId, onOpenMenu = { route = "menu" }, onOpenCard = openCard)
            }
            if (showSettle) {
                Box(modifier = Modifier.fillMaxSize().background(AppTheme.bg)) {
                    com.callradar.app.screen.DailySettlementScreen(userId = userId, onClose = { showSettle = false })
                }
            }
        }
    }

    @Composable
    private fun SimpleWrap(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().background(AppTheme.bg)) {
            Row(modifier = Modifier.fillMaxWidth().background(AppTheme.card).padding(top = 34.dp, start = 10.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("‹", fontSize = 24.sp, color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold); Spacer(Modifier.width(4.dp)); Text("홈", fontSize = 14.sp, color = Color(0xFFF59E0B)) }
                Spacer(Modifier.width(4.dp))
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
            }
            Box(modifier = Modifier.weight(1f)) { content() }
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
                                val response = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/today/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 30000 }; conn.inputStream.bufferedReader().readText() }
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
        var showPair by remember { mutableStateOf(false) }
        var pairCode by remember { mutableStateOf("") }
        var pairMsg by remember { mutableStateOf("") }
        val pairScope = rememberCoroutineScope()
        var guestLoading by remember { mutableStateOf(false) }
        val guestScope = rememberCoroutineScope()
        fun doGuest() {
            if (guestLoading) return
            guestLoading = true
            guestScope.launch {
                try {
                    val androidId = try { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "" } catch (e: Exception) { "" }
                    val deviceId = if (androidId.isNotEmpty()) "guest_$androidId" else "guest_${System.currentTimeMillis()}"
                    val resp = withContext(Dispatchers.IO) {
                        val json = org.json.JSONObject().apply { put("device_id", deviceId); put("nickname", "기사님") }
                        val conn = (URL("$SERVER_URL/api/auth/guest").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 30000 }
                        conn.outputStream.write(json.toString().toByteArray())
                        conn.inputStream.bufferedReader().readText()
                    }
                    val j = org.json.JSONObject(resp)
                    val uid = j.optString("user_id", ""); val nick = j.optString("nickname", "기사님")
                    if (uid.isNotEmpty()) { com.callradar.app.Auth.save(getSharedPreferences(PREFS_NAME, MODE_PRIVATE), j.optString("token", "")); onLoginSuccess(uid, nick) } else guestLoading = false
                } catch (e: Exception) { guestLoading = false }
            }
        }
        // [v43] 카카오 네이티브 SDK 로그인 — 크롬창 없이 카카오톡 1탭. 받은 액세스토큰을 서버가 검증→user_id/토큰 반환.
        var kakaoLoading by remember { mutableStateOf(false) }
        val kakaoScope = rememberCoroutineScope()
        fun exchangeKakao(accessToken: String) {
            kakaoScope.launch {
                try {
                    val resp = withContext(Dispatchers.IO) {
                        val json = org.json.JSONObject().apply { put("access_token", accessToken) }
                        val conn = (URL("$SERVER_URL/api/auth/kakao-native").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 10000; readTimeout = 30000 }
                        conn.outputStream.write(json.toString().toByteArray(Charsets.UTF_8))
                        val rc = conn.responseCode
                        (if (rc in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                    }
                    val j = try { org.json.JSONObject(resp) } catch (e: Exception) { org.json.JSONObject() }
                    val uid = j.optString("user_id", "")
                    if (uid.isNotEmpty()) {
                        loginPrefs.edit().putBoolean(KEY_AUTO_LOGIN, autoLogin).apply()
                        com.callradar.app.Auth.save(getSharedPreferences(PREFS_NAME, MODE_PRIVATE), j.optString("token", ""))
                        onLoginSuccess(uid, j.optString("nickname", "기사님"))
                    } else { kakaoLoading = false; android.widget.Toast.makeText(context, "카카오 로그인 실패 · 다시 시도해 주세요", android.widget.Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) { kakaoLoading = false; android.widget.Toast.makeText(context, "카카오 로그인 오류 · 네트워크 확인", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
        fun doKakaoNative(forceAccount: Boolean = false) {
            if (kakaoLoading) return
            kakaoLoading = true
            val act = this@MainActivity
            val cb: (com.kakao.sdk.auth.model.OAuthToken?, Throwable?) -> Unit = { token, error ->
                if (token != null) exchangeKakao(token.accessToken)
                else { kakaoLoading = false }
            }
            try {
                if (!forceAccount && com.kakao.sdk.user.UserApiClient.instance.isKakaoTalkLoginAvailable(act)) {
                    com.kakao.sdk.user.UserApiClient.instance.loginWithKakaoTalk(act) { token, error ->
                        if (error != null) com.kakao.sdk.user.UserApiClient.instance.loginWithKakaoAccount(act, callback = cb)
                        else if (token != null) exchangeKakao(token.accessToken)
                        else { kakaoLoading = false }
                    }
                } else {
                    com.kakao.sdk.user.UserApiClient.instance.loginWithKakaoAccount(act, callback = cb)
                }
            } catch (e: Exception) { kakaoLoading = false }
        }
        // [테스트 전용 · 숨김] 로고를 길게 누르면 아이디 로그인 (일반 사용자에겐 노출 안 됨)
        var showTestLogin by remember { mutableStateOf(false) }
        var tId by remember { mutableStateOf("") }
        var tPw by remember { mutableStateOf("") }
        var tMsg by remember { mutableStateOf("") }
        var tBusy by remember { mutableStateOf(false) }
        val tScope = rememberCoroutineScope()
        fun doTestLogin() {
            if (tId.isBlank() || tPw.isBlank()) { tMsg = "아이디·비밀번호 입력"; return }
            if (tBusy) return
            tBusy = true; tMsg = ""
            tScope.launch {
                try {
                    val androidId = try { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "" } catch (e: Exception) { "" }
                    val deviceId = if (androidId.isNotEmpty()) "guest_$androidId" else "guest_${System.currentTimeMillis()}"
                    val resp = withContext(Dispatchers.IO) {
                        val json = JSONObject().apply { put("login_id", tId.trim()); put("password", tPw); put("device_id", deviceId) }
                        val conn = (URL("$SERVER_URL/api/auth/login").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 12000; readTimeout = 12000 }
                        conn.outputStream.write(json.toString().toByteArray(Charsets.UTF_8))
                        val rc = conn.responseCode
                        val body = (if (rc in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                        Pair(rc, body)
                    }
                    val j = try { JSONObject(resp.second) } catch (e: Exception) { JSONObject() }
                    if (resp.first in 200..299 && j.optString("user_id", "").isNotEmpty()) {
                        loginPrefs.edit().putBoolean(KEY_AUTO_LOGIN, autoLogin).apply()
                        com.callradar.app.Auth.save(getSharedPreferences(PREFS_NAME, MODE_PRIVATE), j.optString("token", ""))
                        onLoginSuccess(j.optString("user_id", ""), j.optString("nickname", "테스트"))
                    } else { tMsg = j.optString("error", "로그인 실패"); tBusy = false }
                } catch (e: Exception) { tMsg = "네트워크 오류"; tBusy = false }
            }
        }

        Column(modifier = Modifier.fillMaxSize().background(bg).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 28.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(24.dp))
                Text("콜레이더", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = accent,
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { if (com.callradar.app.BuildConfig.DEBUG) showTestLogin = true }) })
                Text("택시 기사 수입 최적화", fontSize = 13.sp, color = muted, modifier = Modifier.padding(top = 6.dp, bottom = 36.dp))

                if (showTestLogin) {
                    AlertDialog(
                        onDismissRequest = { showTestLogin = false },
                        title = { Text("테스트 로그인 (내부용)", color = AppTheme.text, fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                OutlinedTextField(value = tId, onValueChange = { tId = it }, singleLine = true, label = { Text("아이디") })
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = tPw, onValueChange = { tPw = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("비밀번호") })
                                if (tMsg.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(tMsg, fontSize = 12.sp, color = Color(0xFFEF4444)) }
                            }
                        },
                        confirmButton = { Button(onClick = { doTestLogin() }) { Text(if (tBusy) "..." else "로그인") } },
                        dismissButton = { OutlinedButton(onClick = { showTestLogin = false }) { Text("취소") } },
                        containerColor = AppTheme.card
                    )
                }

                // 1순위: 카카오로 시작 — [v43] 네이티브 SDK 1탭(크롬창 없음)
                Button(onClick = {
                    loginPrefs.edit().putBoolean(KEY_AUTO_LOGIN, autoLogin).apply()
                    doKakaoNative()
                }, enabled = !kakaoLoading, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFE812)), shape = RoundedCornerShape(12.dp)) { Text(if (kakaoLoading) "로그인 중..." else "카카오로 시작하기", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp) }

                // 2순위: 게스트 둘러보기
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { doGuest() }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF374151))) {
                    Text(if (guestLoading) "시작하는 중..." else "로그인 없이 둘러보기", color = AppTheme.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                // [v43] 게스트는 기기·스토어 변경 시 데이터가 옮겨지지 않음을 명확히 고지(카카오 로그인 유도)
                Text("⚠️ 게스트는 폰을 바꾸거나 스토어(구글↔원스토어)를 옮기면 기록이 옮겨지지 않아요. 카카오로 로그인하면 어디서든 유지됩니다.", fontSize = 11.sp, color = Color(0xFF9CA3AF), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp))

                // [디버그 전용] 테스트 로그인 버튼 — 릴리스/스토어 빌드엔 표시 안 됨
                if (com.callradar.app.BuildConfig.DEBUG) {
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { showTestLogin = true }, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)), shape = RoundedCornerShape(12.dp)) {
                        Text("🔧 테스트 로그인 (내부용)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }

                // 자동 로그인 (항상 표시)
                Row(modifier = Modifier.fillMaxWidth().clickable { autoLogin = !autoLogin; loginPrefs.edit().putBoolean(KEY_AUTO_LOGIN, autoLogin).apply() }.padding(top = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoLogin, onCheckedChange = { checked -> autoLogin = checked; loginPrefs.edit().putBoolean(KEY_AUTO_LOGIN, checked).apply() }, colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = muted))
                    Text("이 기기에서 자동 로그인", fontSize = 12.sp, color = AppTheme.text)
                }

                // [폐기] 아이디/비밀번호 로그인·회원가입 방식은 제거함.
                //  이유: 카카오 계정과 아이디 계정이 서버상 별도(kakao_id vs device_id/login_id)라
                //  섞어 쓰면 데이터가 갈리는 문제 + 복잡성. 카카오 + 게스트만 사용.

                // 하단: 부가 진입점 (작게)
                Spacer(Modifier.height(16.dp))
                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF1E2532)))
                Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("다른 폰 연결", fontSize = 12.sp, color = muted, modifier = Modifier.clickable { showPair = true })
                    Text("   ·   ", fontSize = 12.sp, color = Color(0xFF334155))
                    Text("다른 계정", fontSize = 12.sp, color = muted, modifier = Modifier.clickable {
                        loginPrefs.edit().putBoolean(KEY_AUTO_LOGIN, autoLogin).apply()
                        doKakaoNative(forceAccount = true)  // [v43] 카카오계정 화면으로 전환(다른 계정 로그인)
                    })
                }

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
                                            val conn = (URL("$SERVER_URL/api/pair/claim").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 30000 }
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
