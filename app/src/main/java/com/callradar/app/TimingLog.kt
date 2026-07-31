package com.callradar.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// [v24 진화⑥] 자동화 타이밍 학습 — 유저가 시작/종료 '누르는 순간'의 컨텍스트를 서버로.
//  나중에 "언제 자동 시작/종료할지"를 데이터가 알려줌(로드맵 §2). 스샷 없이 컨텍스트만 = 심사 안전.
//  실패해도 앱 흐름 무영향(백그라운드 1회).
object TimingLog {
    private const val SERVER_URL = "https://callradar-server.onrender.com"

    fun send(
        context: Context,
        action: String,
        appSource: String? = null,
        lat: Double = 0.0,
        lng: Double = 0.0,
        parsedText: String? = null,
        amount: Int = 0
    ) {
        val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", "") ?: ""
        if (userId.isEmpty()) return
        Thread {
            try {
                val body = JSONObject().apply {
                    put("user_id", userId)
                    put("action", action)
                    if (!appSource.isNullOrBlank()) put("app_source", appSource)
                    if (lat != 0.0) put("lat", lat)
                    if (lng != 0.0) put("lng", lng)
                    if (!parsedText.isNullOrBlank()) put("parsed_text", parsedText)
                    if (amount > 0) put("amount", amount)
                    put("ts", System.currentTimeMillis())
                }
                val conn = (URL("$SERVER_URL/api/timing-log").openConnection() as HttpURLConnection).apply {
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
