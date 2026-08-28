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

    /**
     * [2026-08-28] 회원 탈퇴.
     *
     * 개인정보처리방침 제6조가 "앱 내 더보기 > 회원 탈퇴"로 열람·삭제·처리정지 권리를 행사할 수
     * 있다고 이미 고지하고 있는데 정작 그 기능이 없었다. 고지한 것을 이행할 수단이 없는 상태였다.
     *
     * 서버는 **유효 토큰이 있어야만** 처리한다(토큰 없이 user_id 만 보내는 건 남의 계정 삭제 벡터).
     * 성공하면 서버가 즉시 device_id·kakao_id 를 끊고 토큰을 전부 폐기하므로,
     * 여기서도 로컬 자격증명과 캐시를 지워 되살아나지 않게 한다.
     *
     * ★ 로컬을 반드시 지워야 하는 이유: device_id 가 남아 있으면 다음 실행에서 게스트 로그인이
     *   같은 기기로 붙으려 시도한다. 서버가 끊었으니 새 계정이 생기지만, 옛 user_id 가 prefs 에
     *   남아 있으면 화면이 남의(=이제 없는) 계정을 가리켜 엉뚱한 상태가 된다.
     *
     * 반환값: 서버가 탈퇴를 받아들였는지. 실패 시 아무것도 지우지 않는다(통신 장애로 계정을 날리지 않기 위해).
     */
    /**
     * [2026-08-28] 내보내기용 5분짜리 일회용 티켓.
     *
     * 왜 필요한가: CSV 는 브라우저로 열어 받는데(ACTION_VIEW), 브라우저에는 Bearer 토큰이 없다.
     * 그런데 서버는 `/api/export/` 를 무토큰이면 401 로 막는다(IDOR 하드닝).
     * 그래서 앱이 토큰으로 티켓을 받아 `?t=` 로 붙여 열게 한다.
     * 실패하면 null — 호출부는 티켓 없이 열지 말고 안내를 띄운다(열어봐야 401 JSON 만 보인다).
     */
    fun exportTicket(ctx: android.content.Context): String? {
        val prefs = ctx.getSharedPreferences("callradar_prefs", android.content.Context.MODE_PRIVATE)
        return try {
            load(prefs)
            val t = tok
            if (t.isNullOrBlank()) return null
            val c = (java.net.URL("${com.callradar.app.screen.Config.SERVER_URL}/api/export-ticket")
                .openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"; setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $t")
                doOutput = true; connectTimeout = 8000; readTimeout = 15000
            }
            c.outputStream.write("{}".toByteArray())
            if (c.responseCode !in 200..299) return null
            val k = org.json.JSONObject(c.inputStream.bufferedReader().readText()).optString("ticket", "")
            c.disconnect()
            k.ifBlank { null }
        } catch (e: Exception) { null }
    }

    fun withdraw(ctx: android.content.Context): Boolean {
        val prefs = ctx.getSharedPreferences("callradar_prefs", android.content.Context.MODE_PRIVATE)
        return try {
            load(prefs)
            val t = tok
            if (t.isNullOrBlank()) return false   // 토큰 없이는 서버가 401. 헛되이 로컬을 지우지 않는다.
            val c = (java.net.URL("${com.callradar.app.screen.Config.SERVER_URL}/api/account/withdraw")
                .openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"; setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $t")
                doOutput = true; connectTimeout = 10000; readTimeout = 20000
            }
            c.outputStream.write("{}".toByteArray())
            val okCode = c.responseCode in 200..299
            c.disconnect()
            if (!okCode) return false
            // 서버가 받아들였을 때만 로컬을 비운다.
            prefs.edit().clear().apply()
            clear(prefs)
            true
        } catch (e: Exception) { false }
    }
}
