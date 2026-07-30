package com.callradar.app

import android.content.SharedPreferences

// [보안 v24] IDOR 방어 클라이언트 측 — 계정 토큰을 메모리에 보관하고, 모든 HTTP 요청에
//  Authorization: Bearer <tok> 헤더로 첨부한다(HttpURLConnection 호출부에서 일괄 주입).
//  · tok 은 로그인/게스트/페어링 응답 또는 /api/auth/token 으로 발급받아 저장.
//  · 계정이 바뀌면 반드시 clear() → 무토큰 상태(서버는 레거시로 통과, 안전)로 되돌린 뒤 재발급.
object Auth {
    @Volatile var tok: String? = null
    private const val KEY = "auth_token"

    fun load(prefs: SharedPreferences) {
        tok = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }
    }

    fun save(prefs: SharedPreferences, t: String?) {
        tok = t?.takeIf { it.isNotBlank() }
        prefs.edit().apply { if (tok == null) remove(KEY) else putString(KEY, tok) }.apply()
    }

    fun clear(prefs: SharedPreferences) {
        tok = null
        prefs.edit().remove(KEY).apply()
    }
}
