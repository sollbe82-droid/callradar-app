package com.callradar.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

// [v23] 근무 최대시간 자동 마감 — 알람 스케줄러.
//  출근 시각 + work_max_hours 시점에 WorkAutoEndReceiver를 깨워 세션을 자동 종료(퇴근 깜빡 방지).
//  ★부정확 알람(setAndAllowWhileIdle) 사용 → SCHEDULE_EXACT_ALARM 특수권한 불필요 = 심사 영향 없음.
//   몇 분 오차는 "깜빡 방지"엔 무해.
object WorkAutoEnd {
    private const val REQ = 3102
    const val ACTION = "com.callradar.app.WORK_AUTO_END"

    private fun pending(context: Context): PendingIntent {
        val i = Intent(context, WorkAutoEndReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context, REQ, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // 출근 시각(workStart) + maxHours 시점에 예약. maxHours<=0 또는 workStart<=0이면 취소.
    fun schedule(context: Context, workStart: Long, maxHours: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        if (maxHours <= 0 || workStart <= 0L) { cancel(context); return }
        val triggerAt = workStart + maxHours.toLong() * 3600_000L
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending(context))
        } catch (e: Exception) {
            try { am.set(AlarmManager.RTC_WAKEUP, triggerAt, pending(context)) } catch (e2: Exception) {}
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try { am.cancel(pending(context)) } catch (e: Exception) {}
    }
}
