package com.callradar.app.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// 중앙 테마 — 다크는 기존과 100% 동일, 라이트는 새 옵션.
// 화면들은 하드코딩 색 대신 여기를 참조. isDark 바뀌면 자동 리컴포즈.
object AppTheme {
    var isDark by mutableStateOf(true)

    // 라이트: 순백 대신 오프화이트로 눈부심↓ (카드 F5F6F8 / 배경 E7E9EE, 텍스트도 살짝 완화)
    val bg: Color get() = if (isDark) Color(0xFF0A0E1A) else Color(0xFFE7E9EE)
    val card: Color get() = if (isDark) Color(0xFF111827) else Color(0xFFF5F6F8)
    val surface2: Color get() = if (isDark) Color(0xFF1F2937) else Color(0xFFDCE0E7)
    val text: Color get() = if (isDark) Color.White else Color(0xFF1E293B)
    val muted: Color get() = if (isDark) Color(0xFF6B7280) else Color(0xFF64748B)

    // 강조색은 두 모드 공통
    val accent: Color = Color(0xFFF59E0B)
    val green: Color = Color(0xFF10B981)
    val red: Color = Color(0xFFEF4444)
}
