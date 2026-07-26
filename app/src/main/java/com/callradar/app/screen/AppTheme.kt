package com.callradar.app.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// 중앙 테마 — 다크는 기존과 100% 동일, 라이트는 새 옵션.
// 화면들은 하드코딩 색 대신 여기를 참조. isDark 바뀌면 자동 리컴포즈.
object AppTheme {
    var isDark by mutableStateOf(true)

    val bg: Color get() = if (isDark) Color(0xFF0A0E1A) else Color(0xFFF2F4F7)
    val card: Color get() = if (isDark) Color(0xFF111827) else Color(0xFFFFFFFF)
    val surface2: Color get() = if (isDark) Color(0xFF1F2937) else Color(0xFFE8EBF0)
    val text: Color get() = if (isDark) Color.White else Color(0xFF0F172A)
    val muted: Color get() = if (isDark) Color(0xFF6B7280) else Color(0xFF64748B)

    // 강조색은 두 모드 공통
    val accent: Color = Color(0xFFF59E0B)
    val green: Color = Color(0xFF10B981)
    val red: Color = Color(0xFFEF4444)
}
