package com.callradar.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.callradar.app.screen.AppTheme

// [v18] Material 기본색을 앱 테마(AppTheme.isDark)에 맞춤 — 입력창·다이얼로그 기본 글자색이
// 라이트모드에서 묻히던 버그 수정. dynamicColor(Material You)는 끔(브랜드색 유지).
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFF59E0B),
    onPrimary = Color.Black,
    background = Color(0xFF0A0E1A),
    onBackground = Color.White,
    surface = Color(0xFF111827),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1F2937),
    onSurfaceVariant = Color(0xFFB6BECB)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFF59E0B),
    onPrimary = Color.Black,
    background = Color(0xFFF2F4F7),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE8EBF0),
    onSurfaceVariant = Color(0xFF475569)
)

@Composable
fun CallRadarTheme(
    darkTheme: Boolean = AppTheme.isDark,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}