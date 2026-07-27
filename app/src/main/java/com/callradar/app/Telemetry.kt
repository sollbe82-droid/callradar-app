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
        if (!prefs.getBoolean("telemetry_on", true)) return
        val anon = prefs.getString("anon_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("anon_id", it).apply() }
        scope.launch {
            try {
                val body = JSONObject().apply {
                    put("anon_id", anon); put("event", event.take(64)); put("screen", screen.take(64)); put("ok", ok); put("meta", meta.take(200))
                }
                val conn = (URL("${com.callradar.app.screen.Config.SERVER_URL}/api/usage").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 6000; readTimeout = 6000
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)); it.flush() }
                conn.responseCode; conn.disconnect()
            } catch (_: Exception) { /* 텔레메트리 실패는 무시 */ }
        }
    }
}
