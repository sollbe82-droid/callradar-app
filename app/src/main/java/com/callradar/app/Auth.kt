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

    /**
     * [보안 2026-08-28] 토큰이 stale 일 때 **무토큰으로 떨어지지 않고 다시 발급받는다.**
     *
     * 예전 자가치유는 401/403 을 받으면 토큰을 지우고 무토큰으로 재시도했다.
     * 서버가 무토큰을 통과시키던 동안엔 그게 복구책이었지만, 그건 곧
     * "누구나 user_id 만 바꾸면 남의 데이터를 본다"는 구멍과 같은 말이다.
     * 서버에서 그 구멍을 막는 순간(ENFORCE_TOKEN), 이 코드는 복구가 아니라
     * **토큰을 지워버린 뒤 영구 로그아웃시키는 코드**가 된다.
     *
     * 그래서 기기 식별자로 조용히 재발급받는 경로로 바꾼다.
     * 실패하면 토큰을 지우지 않고 그대로 둔다 — 일시 장애로 로그인을 날리지 않기 위해서다.
     * 반환값: 재발급 성공 여부.
     */
    fun reissue(ctx: android.content.Context, prefs: SharedPreferences): Boolean {
        return try {
            val uid = prefs.getString("user_id", "") ?: ""
            if (uid.isBlank()) return false
            val androidId = android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
            if (androidId.isEmpty()) return false
            val body = org.json.JSONObject().apply {
                put("device_id", "guest_$androidId"); put("user_id", uid)
            }.toString()
            val c = (java.net.URL("${com.callradar.app.screen.Config.SERVER_URL}/api/auth/token")
                .openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"; setRequestProperty("Content-Type", "application/json")
                doOutput = true; connectTimeout = 8000; readTimeout = 15000
            }
            c.outputStream.write(body.toByteArray())
            if (c.responseCode !in 200..299) return false
            val t = org.json.JSONObject(c.inputStream.bufferedReader().readText()).optString("token", "")
            if (t.isBlank()) return false
            save(prefs, t)
            true
        } catch (e: Exception) { false }
    }
}
