package com.callradar.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// [v24 A단계] 인식 학습 업로드 — 인식값(ai) + 유저 확정값(user)을 서버(/api/feedback)로.
//  서버가 자동 채점 → 규칙 자동개선(카나리). 실패해도 앱 흐름엔 무영향(백그라운드 1회).
object Feedback {
    private const val SERVER_URL = "https://callradar-server.onrender.com"

    fun send(
        context: Context,
        feature: String,
        appSource: String?,
        rawText: String?,
        aiValue: String?,
        userValue: String?
    ) {
        val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", "") ?: ""
        if (userId.isEmpty()) return
        // 정답도 인식값도 없으면 보낼 게 없음
        if ((userValue.isNullOrBlank()) && (aiValue.isNullOrBlank())) return
        Thread {
            try {
                val body = JSONObject().apply {
                    put("user_id", userId)
                    put("feature", feature)
                    if (!appSource.isNullOrBlank()) put("app_source", appSource)
                    if (!rawText.isNullOrBlank()) put("raw_text", rawText)
                    if (aiValue != null) put("ai_value", aiValue)
                    if (userValue != null) put("user_value", userValue)
                    put("rule_arm", "baseline")
                }
                val conn = (URL("$SERVER_URL/api/feedback").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    Auth.tok?.let { if (it.isNotBlank()) setRequestProperty("Authorization", "Bearer $it") }
                    doOutput = true; connectTimeout = 8000; readTimeout = 8000
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } catch (e: Exception) {}
        }.start()
    }
}
