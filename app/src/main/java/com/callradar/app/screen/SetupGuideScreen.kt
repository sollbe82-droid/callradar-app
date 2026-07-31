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
