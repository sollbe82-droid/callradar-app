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

    private val SERVER_URL: String get() = com.callradar.app.Endpoint.base

    companion object {
        /** 운행 중 마감을 미루는 간격. */
        const val DEFER_MS = 30L * 60_000L
        /** 유예 상한. 30분 × 8 = 4시간. 넘으면 운행 중이라도 마감한다. */
        const val MAX_DEFER = 8
    }

    override fun onReceive(context: Context, intent: Intent?) {
        /* [서버 주소 확정] 이 경로는 MainActivity 없이 깨어난다(부팅·알람·접근성·알림).
         *  여기서 안 부르면 이 진입점의 요청이 기본값(운영)으로 나가 검증 환경이 무의미해진다. */
        com.callradar.app.Endpoint.init(context)
        val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val workStart = prefs.getLong("work_start", 0L)
        if (workStart <= 0L) return                       // 이미 퇴근
        val maxHours = prefs.getInt("work_max_hours", 0)
        if (maxHours <= 0) return                         // 기능 꺼짐
        val now = System.currentTimeMillis()
        val thresholdMs = maxHours.toLong() * 3600_000L

        /* ★★ [일시정지 반영] 2026-10-07 실운행에서 잡힌 결함.
         *  예전 판정: now - workStart < thresholdMs  ← 벽시계. 쉰 시간을 근무로 셌다.
         *  실측(영진, 10-07): 07:13 출근 / 16:08~19:10 정지(3시간 2분) / 00:49 까지 운행.
         *  22:13 에 15시간이 걸렸지만 그때 실근무는 11시간 58분이었다. 유예로 22:46 에
         *  마감돼 **일하는 중에 퇴근 처리**됐고, 52초 뒤 새 콜이 세션을 쪼갰다.
         *  이제 정지 누적을 빼고 센다 — 'maxHours 만큼 실제로 일했는가' 를 본다. */
        val pausedTotal = prefs.getLong("work_paused_total", 0L).coerceAtLeast(0L)
        val pauseStart = prefs.getLong("work_pause_start", 0L)

        /* ★ 지금 일시정지 중이면 마감하지 않는다.
         *  쉬는 중에 끊으면 '휴식' 이 '퇴근' 이 된다. 기사는 돌아와서 자기가 퇴근된 걸 본다.
         *  재개하면 WorkResume 이 늘어난 정지 누적으로 다시 예약하므로, 여기서는 미루면 된다.
         *  ★ 정지한 채 잊어버린 세션도 영구히 열려 있으면 안 되므로 유예 상한(MAX_DEFER)을
         *    같이 적용한다. 상한을 넘으면 마감한다 — 그게 이 기능의 원래 목적이다. */
        if (pauseStart > 0L) {
            val deferWs = prefs.getLong("autoend_defer_ws", 0L)
            if (deferWs != workStart) {
                prefs.edit().putLong("autoend_defer_ws", workStart).putInt("autoend_deferred", 0).apply()
            }
            val deferred = prefs.getInt("autoend_deferred", 0)
            if (deferred < MAX_DEFER) {
                prefs.edit()
                    .putInt("autoend_deferred", deferred + 1)
                    .putLong("autoend_deferred_at", now)
                    .putString("autoend_defer_reason", "paused")
                    .apply()
                WorkAutoEnd.scheduleAt(context, now + DEFER_MS)
                return
            }
        }

        // 조기/스테일 발화 방어: 실근무가 아직 임계 미달이면 남은 시간만큼 재예약 후 종료.
        val workedMs = now - workStart - pausedTotal - (if (pauseStart > 0L) now - pauseStart else 0L)
        if (workedMs < thresholdMs - 60_000L) {
            WorkAutoEnd.schedule(context, workStart, maxHours, pausedTotal)
            return
        }

        /* ★ [진행 중 콜 보호] 2026-10-05
         *
         *  'FloatingTripService 를 멈추지 않으니 진행 중 운행은 안전하다' 는 판단은 틀렸다.
         *  마감이 그대로 돌면 실제로 이런 일이 벌어진다:
         *   · work_start=0, work_distance_m=0, meter_local=false 로 근무 상태가 초기화된다
         *   · WorkSessionService 가 멈춘다 → **진행 중 콜의 남은 거리가 아예 측정되지 않는다**
         *   · 그 콜이 끝나 저장되면 운행은 남지만, 방금 닫힌 세션에는 fare 0 으로 기록된다
         *     → 매출이 세션 밖으로 떨어져 시급이 왜곡된다
         *   · 콜 종료 후 다음 운행이 시작되면 자동출근이 새 세션을 열고(ensureWorkSessionActive)
         *     거기서 또 15시간 예약이 걸린다 → 마감이 '무한 누적 방지' 가 아니라 '구간 분할' 이 된다
         *
         *  그래서 운행 중에는 마감하지 않고 **미룬다**. 운행이 떠 있다는 것은 기사가 일하고 있다는
         *  증거이고, 손님을 태운 채로 근무를 끊는 것이 더 나쁘다.
         *  무한정 미루면 마감의 뜻이 사라지므로 상한을 둔다 — 30분씩, 최대 8회(4시간).
         *  상한을 넘으면 마감하되 '운행 중 마감' 이었음을 기록한다(사후 추적용).
         *
         *  진행 중 판정은 **디스크 상태**로 한다. 이 수신기는 별도 프로세스로 깨어날 수 있어
         *  서비스의 메모리 변수를 볼 수 없다.
         *   · auto_trip_id > 0   자동기록이 띄운 운행 (NaviIntentReceiver.saveTripState)
         *   · ride_active        수동 플로팅 탑승 중 (FloatingTripService:441)
         */
        val tripId = prefs.getInt("auto_trip_id", -1)
        val riding = prefs.getBoolean("ride_active", false)
        if (tripId > 0 || riding) {
            /* 유예 횟수는 **이 세션에 대한 것**이다. 다른 세션의 카운터가 이월되면 새 세션이
             * 유예를 못 받는다. 그래서 세션 식별자(work_start)를 같이 들고 다닌다. */
            val deferWs = prefs.getLong("autoend_defer_ws", 0L)
            if (deferWs != workStart) {
                prefs.edit().putLong("autoend_defer_ws", workStart).putInt("autoend_deferred", 0).apply()
            }
            val deferred = prefs.getInt("autoend_deferred", 0)
            if (deferred < MAX_DEFER) {
                prefs.edit()
                    .putInt("autoend_deferred", deferred + 1)
                    .putLong("autoend_deferred_at", now)
                    .putString("autoend_defer_reason", if (tripId > 0) "auto_trip_$tripId" else "ride_active")
                    .apply()
                // 지금 시각 기준 30분 뒤로 미룬다. workStart 를 바꾸지 않으므로 '연장' 이 아니라 '유예' 다.
                WorkAutoEnd.scheduleAt(context, now + DEFER_MS)
                notifyDeferred(context, maxHours, deferred + 1)
                return
            }
            // 상한 초과 — 마감한다. 다만 운행 중이었다는 사실을 남긴다.
            prefs.edit().putBoolean("autoend_closed_mid_trip", true).apply()
        }
        // 마감이 실제로 일어나므로 유예 카운터를 리셋한다(다음 세션에 이월되면 안 된다).
        prefs.edit().remove("autoend_deferred").remove("autoend_defer_reason").apply()

        // pausedTotal / pauseStart 는 위 임계 판정에서 이미 읽었다(일시정지 반영). 재선언하지 않는다.
        val grossMs = (now - workStart).coerceAtLeast(0L)
        val netMs = workedMs.coerceAtLeast(0L)   // = gross - 정지누적 - 진행중 정지분. 위 판정과 같은 값을 쓴다.
        val distKm = prefs.getFloat("work_distance_m", 0f) / 1000f
        val grossMin = grossMs / 60000L
        val netMin = netMs / 60000L

        // 근무 세션 로그에 자동마감 항목 추가
        try {
            val log = try { JSONArray(prefs.getString("work_session_log", "[]")) } catch (e: Exception) { JSONArray() }
            log.put(JSONObject().apply {
                put("end", now); put("grossMin", grossMin); put("netMin", netMin)
                put("distKm", distKm.toDouble())
                /* ★ [0원 vs 미상] 2026-10-05
                 *  예전엔 fare 0 / perHour 0 을 적었다. 그건 '0원 벌었다' 는 뜻이 되고,
                 *  실측에서 근무세션 1,000건 중 112건이 그렇게 매출 0원으로 남아 시간당 통계를
                 *  통째로 끌어내렸다. 자동마감은 **그 세션의 매출을 모른다** — 기사가 퇴근을
                 *  안 눌러서 세션 구간 매출을 집계할 기준이 없다.
                 *  그래서 숫자를 적지 않고 '모른다' 를 적는다. 읽는 쪽(HomeScreen 지난 근무 기록)은
                 *  fareUnknown 을 보고 '매출 미상' 으로 표시한다 — 0원이라고 말하지 않는다. */
                put("fareUnknown", true)
                put("fareUnknownReason", "auto_ended_no_shift_end")
                // 운행 중 마감이었으면 그 사실도 남긴다(그 콜의 매출·거리가 세션 밖으로 떨어진다)
                if (prefs.getBoolean("autoend_closed_mid_trip", false)) put("closedMidTrip", true)
                put("autoEnded", true)
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
            // [거리이월 수정] 수동 퇴근(Home/SimpleHome)은 이미 지우는데 자동마감만 빠져 있었다.
            //  같은 영업일에 다시 출근하면 newDay=false 라 리셋이 안 돌아 거리가 그대로 이월된다.
            .putFloat("work_distance_m", 0f)
            .putBoolean("meter_local", false)
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

    /** 유예했음을 알린다. 기사가 '왜 마감이 안 됐지' 하지 않게. */
    private fun notifyDeferred(context: Context, maxHours: Int, nth: Int) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chId = "callradar_autoend"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(NotificationChannel(chId, "근무 자동 마감", NotificationManager.IMPORTANCE_LOW))
            }
            val left = MAX_DEFER - nth
            val noti = Notification.Builder(context, chId)
                .setContentTitle("운행 중이라 자동 마감을 미뤘어요")
                .setContentText("${maxHours}시간을 넘겼지만 운행이 진행 중이라 30분 뒤 다시 확인합니다.")
                .setStyle(Notification.BigTextStyle().bigText(
                    "설정한 최대 근무 ${maxHours}시간을 넘겼는데 운행이 진행 중이라 마감하지 않았어요. " +
                    "손님을 태운 채로 근무를 끊으면 그 운행의 거리·시간·매출이 어긋납니다. " +
                    "30분 뒤 다시 확인하고, 그때도 운행 중이면 또 미룹니다(최대 ${left}회 남음). " +
                    "지금 끝내려면 앱에서 퇴근을 눌러 주세요."))
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setAutoCancel(true)
                .build()
            nm.notify(3104, noti)
        } catch (e: Exception) {}
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
