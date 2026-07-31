package com.callradar.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// [v23] 근무 최대시간 초과 시 세션 자동 종료(runaway 방지 — 퇴근 깜빡).
//  로컬 세션 초기화 + 이어가기 스냅샷 저장 + 서버 세션 0으로 push + 거리미터 중지 + 알림.
class WorkAutoEndReceiver : BroadcastReceiver() {

    private val SERVER_URL = "https://callradar-server.onrender.com"

    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val workStart = prefs.getLong("work_start", 0L)
        if (workStart <= 0L) return                       // 이미 퇴근
        val maxHours = prefs.getInt("work_max_hours", 0)
        if (maxHours <= 0) return                         // 기능 꺼짐
        val now = System.currentTimeMillis()
        val thresholdMs = maxHours.toLong() * 3600_000L
        // 조기/스테일 발화 방어: 아직 임계 미달이면 남은 시간만큼 재예약 후 종료.
        if (now - workStart < thresholdMs - 60_000L) {
            WorkAutoEnd.schedule(context, workStart, maxHours)
            return
        }

        val pausedTotal = prefs.getLong("work_paused_total", 0L)
        val pauseStart = prefs.getLong("work_pause_start", 0L)
        val grossMs = (now - workStart).coerceAtLeast(0L)
        val netMs = (grossMs - pausedTotal - (if (pauseStart > 0L) now - pauseStart else 0L)).coerceAtLeast(0L)
        val distKm = prefs.getFloat("work_distance_m", 0f) / 1000f
        val grossMin = grossMs / 60000L
        val netMin = netMs / 60000L

        // 근무 세션 로그에 자동마감 항목 추가
        try {
            val log = try { JSONArray(prefs.getString("work_session_log", "[]")) } catch (e: Exception) { JSONArray() }
            log.put(JSONObject().apply {
                put("end", now); put("grossMin", grossMin); put("netMin", netMin)
                put("distKm", distKm.toDouble()); put("fare", 0); put("perHour", 0); put("autoEnded", true)
            })
            val trimmed = if (log.length() > 90) JSONArray().also { for (i in log.length() - 90 until log.length()) it.put(log.get(i)) } else log
            prefs.edit().putString("work_session_log", trimmed.toString()).apply()
        } catch (e: Exception) {}

        // 이어가기 스냅샷(실수 자동마감 복구용) + 세션 초기화
        prefs.edit()
            .putLong("last_work_start", workStart)
            .putLong("last_work_paused_total", pausedTotal)
            .putLong("last_work_end", now)
            .putLong("work_start", 0L)
            .putLong("work_paused_total", 0L)
            .putLong("work_pause_start", 0L)
            .apply()

        // 거리 미터 서비스 중지
        try { context.stopService(Intent(context, WorkSessionService::class.java)) } catch (e: Exception) {}

        // 알림
        notifyAutoEnd(context, grossMin, maxHours)

        // 서버 세션 종료 push (백그라운드)
        val userId = prefs.getString("user_id", "") ?: ""
        if (userId.isNotEmpty()) {
            val pending = goAsync()
            Thread {
                try {
                    val body = JSONObject().apply {
                        put("user_id", userId); put("work_start", 0L)
                        put("paused_total", 0L); put("pause_start", 0L); put("start_fare", 0)
                    }
                    val conn = (URL("$SERVER_URL/api/work-session").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        com.callradar.app.Auth.tok?.let { if (it.isNotBlank()) setRequestProperty("Authorization", "Bearer $it") }
                        doOutput = true; connectTimeout = 8000; readTimeout = 12000
                    }
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    conn.responseCode
                } catch (e: Exception) {} finally { try { pending.finish() } catch (e: Exception) {} }
            }.start()
        }
    }

    private fun notifyAutoEnd(context: Context, grossMin: Long, maxHours: Int) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chId = "callradar_autoend"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(NotificationChannel(chId, "근무 자동 마감", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val pi = try {
                val i = context.packageManager.getLaunchIntentForPackage(context.packageName)
                PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            } catch (e: Exception) { null }
            val hh = grossMin / 60; val mm = grossMin % 60
            val noti = Notification.Builder(context, chId)
                .setContentTitle("🔴 근무 자동 마감")
                .setContentText("${maxHours}시간 초과(${hh}시간 ${mm}분)로 자동 퇴근했어요. 실수면 앱에서 이어가기.")
                .setStyle(Notification.BigTextStyle().bigText("설정한 최대 근무 ${maxHours}시간을 넘겨 근무가 자동 마감됐어요. 총 ${hh}시간 ${mm}분. 퇴근을 깜빡한 거면 앱을 열어 이어가기로 복구할 수 있어요."))
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setAutoCancel(true)
                .apply { if (pi != null) setContentIntent(pi) }
                .build()
            nm.notify(3103, noti)
        } catch (e: Exception) {}
    }
}
