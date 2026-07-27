package com.callradar.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 익명 사용성 텔레메트리 (자기진화 루프 앱측). 개인정보 미수집: 무작위 anon_id + event/screen/성공여부/meta.
 * 옵트아웃: prefs "telemetry_on"(기본 true)이 false면 전송 안 함. 실패해도 앱에 영향 없음.
 */
object Telemetry {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun log(ctx: Context, event: String, screen: String = "", ok: Boolean = true, meta: String = "") {
        val prefs = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val on = prefs.getBoolean("telemetry_on", true)
        android.util.Log.d("CRTelemetry", "log() called event=$event on=$on")
        if (!on) return
        val anon = prefs.getString("anon_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("anon_id", it).apply() }
        scope.launch {
            try {
                val body = JSONObject().apply {
                    put("anon_id", anon); put("event", event.take(64)); put("screen", screen.take(64)); put("ok", ok); put("meta", meta.take(200))
                }
                val url = "${com.callradar.app.screen.Config.SERVER_URL}/api/usage"
                android.util.Log.d("CRTelemetry", "posting event=$event → $url")
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 6000; readTimeout = 6000
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)); it.flush() }
                val code = conn.responseCode; conn.disconnect()
                android.util.Log.d("CRTelemetry", "sent event=$event code=$code")
            } catch (e: Exception) { android.util.Log.e("CRTelemetry", "FAIL event=$event: ${e.message}") }
        }
    }
}
