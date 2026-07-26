package com.callradar.app.screen

import android.content.SharedPreferences

// 서버 주소 한 곳 관리 — 바뀌면 여기 한 줄만 수정
object Config {
    const val SERVER_URL = "https://callradar-server.onrender.com"
}

// [v17] 플랫폼 수수료 소수점 지원. 기존엔 Int(putInt)로 저장돼 있어서
// 곧바로 getFloat 하면 ClassCastException으로 앱이 죽는다 → 최초 읽을 때 안전하게 Float로 이관.
fun feeRateFloat(prefs: SharedPreferences, key: String): Float {
    return try {
        prefs.getFloat(key, 0f)
    } catch (e: ClassCastException) {
        val v = try { prefs.getInt(key, 0).toFloat() } catch (e2: Exception) { 0f }
        prefs.edit().putFloat(key, v).apply()
        v
    }
}

// 소수점이 0이면 정수로, 아니면 1자리로 표시 (예: 3 → "3", 3.3 → "3.3")
fun fmtFee(v: Float): String =
    if (v == v.toLong().toFloat()) v.toLong().toString() else String.format("%.1f", v)
