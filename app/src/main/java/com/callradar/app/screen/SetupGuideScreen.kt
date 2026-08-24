package com.callradar.app.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun SetupGuideScreen(onSetupComplete: () -> Unit) {
    val context = LocalContext.current
    val bg = AppTheme.bg
    val card = AppTheme.card
    val accent = Color(0xFF00C896)
    val muted = Color(0xFF6B7280)

    // ★F1: 진입 시점에 이미 위치 권한이 있었는지 = "재방문자" 판별용 (기억해두고 안 바뀜)
    //   재방문자만 즉시 통과, 신규 사용자는 위치 승인 후에도 백그라운드·배터리 "권장"을 보게 함
    val hadLocationAtStart = remember { checkLocationPermission(context) }

    var locationGranted by remember { mutableStateOf(checkLocationPermission(context)) }
    var backgroundLocationGranted by remember { mutableStateOf(checkBackgroundLocation(context)) }
    var batteryUnrestricted by remember { mutableStateOf(checkBatteryOptimization(context)) } // true = 최적화에서 "제외됨"(좋은 상태)
    var currentStep by remember { mutableStateOf(0) }

    // 뒤로가기: 두 번 눌러야 온보딩 건너뜀 (한 번은 안내 토스트, 실수 터치로 바로 튕기지 않게)
    var backPressedOnce by remember { mutableStateOf(false) }
    BackHandler {
        if (backPressedOnce) {
            // 두 번째 → 온보딩 건너뛰고 홈으로 (설정 완료 처리)
            completeSetup(context, onSetupComplete)
        } else {
            backPressedOnce = true
            android.widget.Toast.makeText(context, "한 번 더 누르면 설정을 건너뛰고 시작해요", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    // 2초 안에 두 번째를 안 누르면 초기화
    LaunchedEffect(backPressedOnce) {
        if (backPressedOnce) {
            kotlinx.coroutines.delay(2000)
            backPressedOnce = false
        }
    }

    // ★F1 핵심: 자동 통과는 "재방문자(진입 때 이미 위치 있음)"에게만.
    //   예전 버그: 셋 다 켜야 통과 → 갇힘(이탈 주범).
    //   1차 수정: 위치만 켜지면 즉시 통과 → 갇힘은 풀렸지만, 위치 켜는 순간 화면이 닫혀
    //             백그라운드 위치(=화면 꺼져도 기록, 자동기록의 핵심)를 유도할 기회가 사라짐.
    //   최종: 재방문자는 즉시 통과(안 괴롭힘), 신규는 위치 승인 후에도 화면 유지 → 아래 "시작하기"로 진행.
    LaunchedEffect(locationGranted) {
        if (locationGranted && hadLocationAtStart) {
            completeSetup(context, onSetupComplete)
        }
    }

    // 설정 화면 갔다 돌아올 때마다 권한 재확인 (ON_RESUME) — 배터리설정 갔다와도 갱신됨
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationGranted = checkLocationPermission(context)
                backgroundLocationGranted = checkBackgroundLocation(context)
                batteryUnrestricted = checkBatteryOptimization(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 위치 권한 런처
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (locationGranted) currentStep = 1
    }

    // 백그라운드 위치 런처
    //  ★F2: Android 11+(API 30↑)에선 런처 직접 요청으로는 "항상 허용"을 못 받음(구글 정책).
    //        거부로 돌아오면 앱 설정 화면으로 보내 사용자가 직접 "항상 허용"을 고르게 유도.
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        backgroundLocationGranted = granted
        if (granted) {
            currentStep = 2
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.widget.Toast.makeText(
                context,
                "설정 > 권한 > 위치에서 '항상 허용'을 선택해 주세요",
                android.widget.Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }

    // 현재 단계 자동 결정
    LaunchedEffect(locationGranted, backgroundLocationGranted, batteryUnrestricted) {
        currentStep = when {
            !locationGranted -> 0
            !backgroundLocationGranted -> 1
            !batteryUnrestricted -> 2
            else -> 3
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(24.dp))
        Text("🚕", fontSize = 48.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "콜레이더 설정",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = AppTheme.text
        )
        Text(
            "정확한 운행 기록을 위해 설정이 필요해요",
            fontSize = 14.sp,
            color = muted,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
        )

        // 단계 1: 위치 권한
        SetupItem(
            step = 1,
            title = "위치 권한 허용",
            description = "출발지/목적지를 자동으로 기록해요",
            isCompleted = locationGranted,
            isCurrent = currentStep == 0,
            accent = accent,
            card = card,
            muted = muted,
            onAction = {
                locationLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        )

        Spacer(Modifier.height(12.dp))

        // 단계 2: 백그라운드 위치 (선택·권장)
        SetupItem(
            step = 2,
            title = "백그라운드 위치 허용 (권장)",
            description = "운행 중 화면 꺼져도 위치를 기록해요",
            isCompleted = backgroundLocationGranted,
            isCurrent = currentStep == 1,
            accent = accent,
            card = card,
            muted = muted,
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
        )

        Spacer(Modifier.height(12.dp))

        // 단계 3: 배터리 최적화 제외 (선택·권장)
        SetupItem(
            step = 3,
            title = "배터리 최적화 제외 (권장)",
            description = "GPS가 절전 모드에서 꺼지지 않아요",
            isCompleted = batteryUnrestricted,
            isCurrent = currentStep == 2,
            accent = accent,
            card = card,
            muted = muted,
            onAction = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        )

        Spacer(Modifier.height(28.dp))

        // ★F1: 하단 버튼을 상태로 분기
        //   - 위치 미허용: 큰 "나중에 설정하고 시작하기"(스킵) — 갇힘 방지
        //   - 위치 허용 후(신규): 초록 "시작하기"(주 버튼) — 백그라운드·배터리 권장을 본 뒤 사용자가 눌러 진입
        if (locationGranted) {
            Button(
                onClick = { completeSetup(context, onSetupComplete) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text("시작하기", fontSize = 15.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "백그라운드·배터리는 권장이에요. 나중에 설정에서 켜도 됩니다.",
                fontSize = 12.sp,
                color = muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            OutlinedButton(
                onClick = { completeSetup(context, onSetupComplete) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("나중에 설정하고 시작하기", fontSize = 14.sp, color = accent, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// [v23] 온보딩 갇힘 해소 — 전체화면 권한 게이트 대신 홈 위에 뜨는 작은 팝업.
//  위치 권한을 '가볍게' 요청하되, 거부/나중에여도 앱엔 바로 들어가게(이탈 방지).
//  백그라운드 위치·배터리 최적화는 여기서 안 물음 → 마찰 최소화(설정에서 나중에 가능).
@Composable
fun SetupPopup(onFinish: () -> Unit) {
    val context = LocalContext.current
    val accent = Color(0xFF00C896)
    val muted = Color(0xFF6B7280)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 허용이든 거부든 팝업 닫고 진입 (권한은 기능 쓸 때 다시 유도 가능)
        completeSetup(context, onFinish)
    }
    AlertDialog(
        onDismissRequest = { completeSetup(context, onFinish) },
        icon = { Text("🚕", fontSize = 32.sp) },
        title = { Text("바로 시작할까요?", fontWeight = FontWeight.Bold, color = AppTheme.text) },
        text = {
            Text(
                "출발지·도착지를 자동으로 기록하려면 위치 권한이 필요해요.\n지금 허용하면 운행이 자동으로 채워집니다. (나중에 설정에서 바꿀 수 있어요)",
                fontSize = 13.sp, color = muted
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    launcher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = RoundedCornerShape(10.dp)
            ) { Text("위치 허용하고 시작", color = Color.Black, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = { completeSetup(context, onFinish) }) {
                Text("나중에", color = muted)
            }
        },
        containerColor = AppTheme.card
    )
}

@Composable
private fun SetupItem(
    step: Int,
    title: String,
    description: String,
    isCompleted: Boolean,
    isCurrent: Boolean,
    accent: Color,
    card: Color,
    muted: Color,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) AppTheme.surface2 else card
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 상태 아이콘
            Text(
                text = if (isCompleted) "✅" else "⬜",
                fontSize = 24.sp,
                modifier = Modifier.padding(end = 12.dp)
            )

            // 설명
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                Text(description, fontSize = 12.sp, color = muted)
            }

            // 버튼: 완료 안 됐으면 항상 표시 (isCurrent 조건 제거 — 순서 꼬여도 눌러 진행 가능)
            if (!isCompleted) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("설정", fontSize = 13.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// 설정 완료 처리 공통 함수 (중복 제거)
private fun completeSetup(context: Context, onSetupComplete: () -> Unit) {
    val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("setup_complete", true).apply()
    onSetupComplete()
}

private fun checkLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

private fun checkBackgroundLocation(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    } else true
}

private fun checkBatteryOptimization(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

// ══════════════════════════════════════════════════════════════════
// [온보딩 자동 마법사] 가입→실사용 전환 25% 병목 해소용.
//  위치 팝업 다음 1회 노출. 자동기록에 필요한 권한을 단계별 ✅ 체크로 안내.
//  - onestore: 오버레이(운행버튼) → 접근성(완전 자동기록) → 알림접근(금액 자동입력)
//  - play    : 오버레이 → 알림접근 + "완전자동은 원스토어" 전환 카드 (구글 정책상 접근성 미제공)
//  권한을 켠 항목은 완료 시 해당 토글(prefs)도 자동 ON → 마법사만 끝내면 바로 작동.
// ══════════════════════════════════════════════════════════════════
@Composable
fun AutoSetupWizardPopup(force: Boolean = false, onFinish: (startFloating: Boolean) -> Unit) {
    // force=true: 메뉴의 '설치 도움말'에서 재진입 — 다 켜져 있어도 화면을 보여주는 복습 모드.
    val context = LocalContext.current
    val accent = Color(0xFF00C896)
    val muted = Color(0xFF9CA3AF)
    val isOnestore = com.callradar.app.BuildConfig.FLAVOR == "onestore"

    fun overlayOk() = Settings.canDrawOverlays(context)
    fun accOk() = (Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services") ?: "").contains(context.packageName)
    fun notifOk() = (Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: "").contains(context.packageName)

    var overlay by remember { mutableStateOf(overlayOk()) }
    var acc by remember { mutableStateOf(accOk()) }
    var notif by remember { mutableStateOf(notifOk()) }

    fun finish() {
        val p = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val e = p.edit().putBoolean("auto_wizard_done", true)
        if (overlay) e.putBoolean("floating_on", true)
        if (isOnestore && acc) e.putBoolean("auto_record_on", true).putBoolean("auto_record_touched", true)
        if (notif) e.putBoolean("notif_capture_on", true)
        e.apply()
        onFinish(overlay)
    }

    // 이미 다 켜져 있으면(기존 유저) 조용히 통과 — 괴롭히지 않기 (복습 모드는 예외)
    val allDone = overlay && notif && (!isOnestore || acc)
    LaunchedEffect(Unit) { if (allDone && !force) finish() }
    if (allDone && !force) return

    // ── 단계 정의 (한 화면 = 한 단계, 뭘 누르는지 순서대로 다 알려줌) ──
    data class Step(
        val emoji: String, val title: String, val why: String,
        val howSteps: List<String>, val trouble: String, val granted: Boolean, val onOpen: () -> Unit
    )
    val steps = mutableListOf<Step>()
    steps.add(Step(
        "🚕", "운행 버튼 띄우기",
        "화면 위에 항상 떠 있는 버튼이에요.\n손님 태울 때 한 번, 내릴 때 한 번 — 그게 다예요.",
        listOf(
            "아래 [설정 열기]를 누르세요",
            "새 화면에서 스위치를 눌러 켜세요 (파란색/초록색이 되면 켜진 거예요)",
            "◀ 뒤로 버튼으로 콜레이더로 돌아오세요",
            "돌아오면 자동으로 다음 단계로 넘어가요"
        ),
        "스위치가 안 보이면: 화면에 '다른 앱 위에 표시'라는 글자를 찾아 누르세요.",
        overlay
    ) { try { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) } catch (e: Exception) {} })
    if (isOnestore) steps.add(Step(
        "🤖", "완전 자동 기록 (핵심!)",
        "이것만 켜면 카카오T·우버 운행이 시작·종료·요금까지\n전부 자동으로 기록돼요. 손댈 게 없어요.",
        listOf(
            "아래 [설정 열기]를 누르세요",
            "'설치된 앱' 또는 '다운로드한 앱' 목록에서 [콜레이더]를 찾아 누르세요",
            "스위치를 켜고, 경고창이 뜨면 [허용]을 누르세요",
            "◀ 뒤로 두 번 — 콜레이더로 돌아오면 자동으로 다음으로 넘어가요"
        ),
        "스위치가 회색이라 안 눌리면(제한된 설정): 휴대폰 설정 → 애플리케이션 → 콜레이더 → 오른쪽 위 ⋮ 점 세 개 → '제한된 설정 허용'을 누른 뒤 다시 해보세요.",
        acc
    ) { android.widget.Toast.makeText(context, "목록에서 '콜레이더'를 찾아 스위치를 켜세요", android.widget.Toast.LENGTH_LONG).show()
        try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) {} })
    steps.add(Step(
        "💰", "금액 자동 입력",
        "카드 결제 알림이 뜨면 요금을 콜레이더가 읽어서\n운행 기록에 자동으로 채워 넣어요.",
        listOf(
            "아래 [설정 열기]를 누르세요",
            "목록에서 [콜레이더]를 찾아 스위치를 켜세요",
            "'알림 접근을 허용하시겠습니까?' 창이 뜨면 [허용]을 누르세요",
            "◀ 뒤로 — 돌아오면 자동으로 완료돼요"
        ),
        "콜레이더가 목록에 없으면: 휴대폰을 한 번 껐다 켠 뒤 다시 해보세요.",
        notif
    ) { android.widget.Toast.makeText(context, "목록에서 '콜레이더'를 켜세요", android.widget.Toast.LENGTH_LONG).show()
        try { context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } catch (e: Exception) {} })

    var idx by remember { mutableStateOf(if (force) 0 else steps.indexOfFirst { !it.granted }.coerceAtLeast(0)) }
    var justDone by remember { mutableStateOf(false) }

    // [v91] 뒤로가기가 앱을 통째로 종료시키던 버그.
    //  이 마법사는 전체화면 Box일 뿐 Dialog가 아니라서, BackHandler가 없으면
    //  뒤로가기가 MainActivity(루트)로 내려가 앱이 꺼진다. '설치 도움말'로 들어온 기사님이
    //  뒤로 한 번 눌렀다가 앱이 닫히는 걸 겪는다.
    //  앞 단계가 있으면 그리로, 첫 단계면 마법사만 닫는다.
    androidx.activity.compose.BackHandler(enabled = true) {
        if (idx > 0) idx -= 1 else finish()
    }

    // 설정 갔다 돌아오면 재확인 → 현재 단계가 완료됐으면 자동으로 다음 단계
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) { overlay = overlayOk(); acc = accOk(); notif = notifOk() }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val grantedNow = listOf(overlay, if (isOnestore) acc else true, notif)
    val stepGranted: (Int) -> Boolean = { i ->
        when (steps[i].emoji) { "🚕" -> overlay; "🤖" -> acc; else -> notif }
    }
    // 자동 다음 단계: 첫 조합 때가 아니라 '설정 갔다 와서 새로 켜졌을 때'만 발동 (복습 모드는 수동 이동)
    var seenGranted by remember { mutableStateOf(listOf(overlay, acc, notif)) }
    LaunchedEffect(overlay, acc, notif) {
        val now = listOf(overlay, acc, notif)
        val newlyOn = now.zip(seenGranted).any { (a, b) -> a && !b }
        seenGranted = now
        if (!force && newlyOn && idx < steps.size && stepGranted(idx)) {
            justDone = true
            android.widget.Toast.makeText(context, "✅ ${steps[idx].title} 완료!", android.widget.Toast.LENGTH_SHORT).show()
            kotlinx.coroutines.delay(600)
            justDone = false
            val next = (idx + 1 until steps.size).firstOrNull { !stepGranted(it) }
            if (next != null) idx = next
            else idx = steps.size   // 전부 완료 → 마지막 축하 화면
        }
    }
    val doneCount = (0 until steps.size).count { stepGranted(it) }

    // ── 전체 화면 마법사 ──
    Box(Modifier.fillMaxSize().background(AppTheme.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(48.dp))

            // 진행 표시 (크고 명확하게)
            Row(verticalAlignment = Alignment.CenterVertically) {
                steps.forEachIndexed { i, s ->
                    val on = stepGranted(i)
                    Box(Modifier.size(34.dp).background(if (on) accent else if (i == idx) AppTheme.surface2 else AppTheme.card, RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                        Text(if (on) "✓" else "${i + 1}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (on) Color.Black else AppTheme.text)
                    }
                    if (i < steps.size - 1) Box(Modifier.weight(1f).height(3.dp).background(if (on) accent else AppTheme.card))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("$doneCount / ${steps.size} 완료", fontSize = 13.sp, color = muted)
            Spacer(Modifier.height(24.dp))

            if (idx >= steps.size) {
                // ── 완료 화면 ──
                Text("🎉", fontSize = 56.sp)
                Spacer(Modifier.height(12.dp))
                Text("설정 끝! 이제 자동이에요", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                Spacer(Modifier.height(10.dp))
                Text(if (isOnestore) "오늘부터 운행하시면 시작·종료·요금이\n알아서 기록됩니다. 퇴근할 때 앱만 열어보세요."
                     else "운행 버튼과 금액 자동 입력이 켜졌어요.\n화면 위 🚕 버튼으로 시작·완료만 눌러주세요.",
                    fontSize = 15.sp, color = muted, lineHeight = 24.sp)
                Spacer(Modifier.height(28.dp))
                Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(14.dp)) {
                    Text("콜레이더 시작하기", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            } else {
                val s = steps[idx]
                // ── 단계 화면 ──
                Text(s.emoji, fontSize = 52.sp)
                Spacer(Modifier.height(10.dp))
                Text("${idx + 1}단계 · ${s.title}", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                Spacer(Modifier.height(8.dp))
                Text(s.why, fontSize = 15.sp, color = muted, lineHeight = 23.sp)
                Spacer(Modifier.height(20.dp))

                // 이렇게 하세요 — 번호 붙은 큰 카드
                Card(colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("이렇게 하세요", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
                        Spacer(Modifier.height(10.dp))
                        s.howSteps.forEachIndexed { i, h ->
                            Row(Modifier.padding(vertical = 6.dp)) {
                                Box(Modifier.size(24.dp).background(AppTheme.surface2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                    Text("${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(h, fontSize = 14.5.sp, color = AppTheme.text, lineHeight = 21.sp, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                // [갤럭시 화면 그림] 설정 화면이 어떻게 생겼는지 미리 보여줌 — 글만으론 못 따라오는 분들용
                GalaxySettingsMock(s.emoji, accent, muted)

                Spacer(Modifier.height(12.dp))
                // 안 될 때 (트러블슈팅 — 접기 없이 항상 노출)
                Card(colors = CardDefaults.cardColors(containerColor = AppTheme.surface2.copy(alpha = 0.5f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("⚠️ 안 될 때", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                        Text(s.trouble, fontSize = 12.5.sp, color = muted, lineHeight = 19.sp)
                    }
                }

                Spacer(Modifier.height(24.dp))
                val thisGranted = stepGranted(idx)
                if (thisGranted) {
                    // 이미 켜져 있음(복습 모드 등) → 다음으로
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF10B981).copy(alpha = 0.15f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("✅ 이 설정은 이미 켜져 있어요", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981), modifier = Modifier.padding(14.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { if (idx < steps.size - 1) idx++ else idx = steps.size }, modifier = Modifier.fillMaxWidth().height(58.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(14.dp)) {
                        Text(if (idx < steps.size - 1) "다음 단계 →" else "완료 화면으로 →", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                    TextButton(onClick = { s.onOpen() }, modifier = Modifier.fillMaxWidth()) { Text("설정 화면 다시 열어보기", fontSize = 12.sp, color = muted) }
                } else {
                    Button(onClick = { s.onOpen() }, modifier = Modifier.fillMaxWidth().height(58.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(14.dp)) {
                        Text("설정 열기", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("설정을 켜고 돌아오면 자동으로 다음 단계로 넘어가요",
                        fontSize = 12.sp, color = muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    if (force) {
                        TextButton(onClick = { if (idx < steps.size - 1) idx++ else idx = steps.size }, modifier = Modifier.fillMaxWidth()) {
                            Text("다음 단계 보기 →", fontSize = 13.sp, color = accent)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        val next = (idx + 1 until steps.size).firstOrNull { !stepGranted(it) }
                        if (next != null) idx = next else idx = steps.size
                    }) { Text("이 단계 건너뛰기", fontSize = 13.sp, color = muted) }
                    TextButton(onClick = { finish() }) { Text("전부 나중에 하기", fontSize = 13.sp, color = muted) }
                }

                // [구글판] 완전자동 원스토어 전환 카드 — 마지막 단계 아래에
                if (!isOnestore && idx == steps.size - 1) {
                    Spacer(Modifier.height(8.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("⚡ 버튼도 누르기 귀찮다면?", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                            Text("원스토어 버전은 택시앱을 감지해 시작·종료·요금까지 전부 자동으로 기록해요. (구글 정책상 이 버전엔 넣을 수 없어요)", fontSize = 12.sp, color = muted, lineHeight = 18.sp)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = {
                                com.callradar.app.Telemetry.log(context, "onestore_guide_tap", "wizard")
                                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("onestore://common/product/0001007971")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                catch (e: Exception) { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://m.onestore.co.kr/v2/ko-kr/app/0001007971")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) {} }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("원스토어에서 완전자동 버전 받기", fontSize = 14.sp, color = accent, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── [갤럭시 화면 그림] 실제 설정 화면을 흉내 낸 미니 목업 — "이 화면이 나오면 여길 누르세요" ──
@Composable
private fun GalaxySettingsMock(stepEmoji: String, accent: Color, muted: Color) {
    val rowBg = Color(0xFF2A2F3A); val screenBg = Color(0xFF1C202A)
    Card(colors = CardDefaults.cardColors(containerColor = screenBg), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("📱 갤럭시 설정 화면은 이렇게 생겼어요", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = muted)
            Spacer(Modifier.height(10.dp))
            when (stepEmoji) {
                "🚕" -> {   // 오버레이: '다른 앱 위에 표시' 스위치
                    Text("다른 앱 위에 표시", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    MockRow("📡  콜레이더", true, switchOn = true, accent = accent)
                    Text("👆 이 스위치를 눌러 켜세요", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(top = 6.dp))
                }
                "🤖" -> {   // 접근성: 설치된 앱 목록에서 콜레이더
                    Text("접근성 › 설치된 앱", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    MockRow("TalkBack", false, switchOn = false, accent = accent)
                    Spacer(Modifier.height(6.dp))
                    MockRow("📡  콜레이더", true, switchOn = true, accent = accent)
                    Text("👆 '콜레이더'를 눌러 들어간 뒤 스위치를 켜고 [허용]", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(top = 6.dp))
                }
                else -> {   // 알림접근
                    Text("알림 접근 허용", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    MockRow("📡  콜레이더", true, switchOn = true, accent = accent)
                    Text("👆 스위치를 켜고 '허용'을 누르세요", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun MockRow(label: String, highlight: Boolean, switchOn: Boolean, accent: Color) {
    val rowBg = if (highlight) Color(0xFF2F3A4A) else Color(0xFF232833)
    Row(Modifier.fillMaxWidth().background(rowBg, RoundedCornerShape(10.dp))
            .let { if (highlight) it.padding(2.dp).background(Color.Transparent) else it }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = Color.White, fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        // 가짜 스위치
        Box(Modifier.width(44.dp).height(24.dp).background(if (switchOn) Color(0xFF10B981) else Color(0xFF4B5563), RoundedCornerShape(12.dp))) {
            Box(Modifier.size(18.dp).align(if (switchOn) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 3.dp).background(Color.White, RoundedCornerShape(9.dp)))
        }
    }
}
