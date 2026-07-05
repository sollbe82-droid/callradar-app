package com.callradar.app.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

@Composable
fun SetupGuideScreen(onSetupComplete: () -> Unit) {
    val context = LocalContext.current
    val bg = Color(0xFF0A0E1A)
    val card = Color(0xFF111827)
    val accent = Color(0xFF00C896)
    val muted = Color(0xFF6B7280)

    var locationGranted by remember { mutableStateOf(checkLocationPermission(context)) }
    var backgroundLocationGranted by remember { mutableStateOf(checkBackgroundLocation(context)) }
    var batteryOptimized by remember { mutableStateOf(checkBatteryOptimization(context)) }
    var currentStep by remember { mutableStateOf(0) }

    // 모든 권한 확인
    LaunchedEffect(locationGranted, backgroundLocationGranted, batteryOptimized) {
        if (locationGranted && backgroundLocationGranted && batteryOptimized) {
            val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("setup_complete", true).apply()
            onSetupComplete()
        }
    }

    // 위치 권한 런처
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (locationGranted) currentStep = 1
    }

    // 백그라운드 위치 런처
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        backgroundLocationGranted = granted
        if (granted) currentStep = 2
    }

    // 권한 상태 재확인 (설정에서 돌아올 때)
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(500)
        locationGranted = checkLocationPermission(context)
        backgroundLocationGranted = checkBackgroundLocation(context)
        batteryOptimized = checkBatteryOptimization(context)
    }

    // 현재 단계 자동 결정
    LaunchedEffect(locationGranted, backgroundLocationGranted, batteryOptimized) {
        currentStep = when {
            !locationGranted -> 0
            !backgroundLocationGranted -> 1
            !batteryOptimized -> 2
            else -> 3
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(bg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🚕", fontSize = 48.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "콜레이더 설정",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
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

        // 단계 2: 백그라운드 위치
        SetupItem(
            step = 2,
            title = "백그라운드 위치 허용",
            description = "운행 중 화면 꺼져도 위치를 추적해요",
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

        // 단계 3: 배터리 최적화 제외
        SetupItem(
            step = 3,
            title = "배터리 최적화 제외",
            description = "GPS가 절전 모드에서 꺼지지 않아요",
            isCompleted = batteryOptimized,
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

        Spacer(Modifier.height(32.dp))

        // 건너뛰기 (나중에 설정)
        TextButton(onClick = {
            val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("setup_complete", true).apply()
            onSetupComplete()
        }) {
            Text("나중에 설정할게요", fontSize = 13.sp, color = muted)
        }
    }
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
            containerColor = if (isCurrent) Color(0xFF1F2937) else card
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
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(description, fontSize = 12.sp, color = muted)
            }

            // 버튼
            if (!isCompleted && isCurrent) {
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
