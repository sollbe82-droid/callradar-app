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

    /* 출근 시각(workStart) + 일시정지 누적 + maxHours 시점에 예약.
     * maxHours<=0 또는 workStart<=0이면 취소.
     *
     * ★★ [일시정지 미반영] 2026-10-07 실운행에서 잡힌 결함.
     *   예전 식: triggerAt = workStart + maxHours
     *   일시정지 누적을 빼지 않아 **벽시계** 기준이었다. 쉰 시간도 근무로 센 것이다.
     *
     *   실측(영진, 10-07):
     *     07:13 출근 → 16:08 일시정지 → 19:10 재개 → 00:49 까지 운행
     *     일시정지 3시간 2분. 15시간 알람은 07:13+15h = 22:13 에 걸렸고,
     *     그 시점의 **실제 근무는 11시간 58분**이었다.
     *     운행 중 유예(30분)로 22:46 에 마감 → 일하는 중에 퇴근 처리됐다.
     *     그 뒤 52초 만에 새 콜이 자동출근을 띄워 세션이 쪼개졌다.
     *
     *   pausedTotal 을 더하면 '실근무 maxHours' 가 된다. 쉰 만큼 뒤로 밀린다.
     *
     * ★ 일시정지가 끝날 때마다 pausedTotal 이 커지므로 **재개 시 다시 불러야 한다.**
     *   예전에는 WorkResume 이 WorkAutoEnd 를 한 번도 부르지 않았다(전수 확인).
     *   그래서 재개해도 알람이 옛 시각에 그대로 남아 있었다.
     *
     * ★ 일시정지 중에 알람이 터지는 경우는 WorkAutoEndReceiver 가 유예로 처리한다.
     *   거기서 끊어버리면 '쉬는 중'이 '퇴근'이 된다. 반대로 영구히 안 끊으면
     *   런어웨이 세션을 막는 목적을 잃으므로, 유예 횟수 상한으로 끝에는 마감한다. */
    @JvmOverloads
    fun schedule(context: Context, workStart: Long, maxHours: Int, pausedTotalMs: Long = 0L) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        if (maxHours <= 0 || workStart <= 0L) { cancel(context); return }
        val paused = pausedTotalMs.coerceAtLeast(0L)
        val triggerAt = workStart + paused + maxHours.toLong() * 3600_000L
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending(context))
        } catch (e: Exception) {
            try { am.set(AlarmManager.RTC_WAKEUP, triggerAt, pending(context)) } catch (e2: Exception) {}
        }
    }

    /**
     * 절대 시각으로 예약한다. 진행 중 운행 때문에 마감을 **유예**할 때만 쓴다.
     * workStart 를 바꾸지 않으므로 '연장' 이 아니다 — 세션의 기준 시각은 그대로고,
     * 마감 시도만 뒤로 미룬다. 같은 PendingIntent 라 이전 예약을 대체한다.
     */
    fun scheduleAt(context: Context, triggerAt: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        if (triggerAt <= 0L) return
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

    /** 자동마감 직후 다시 자동출근이 붙는 창(이 안이면 '끊긴 게 아니라 이어진 것'으로 본다). */
    const val CHAIN_WINDOW_MS = 30L * 60_000L

    /**
     * 자동출근이 **자동마감 직후**에 일어났다는 것을 기록한다.
     *
     * 왜 필요한가 (2026-10-05 추적 결과):
     *   자동마감은 근무 상태를 초기화하는데, 그 뒤 운행이 하나 시작되면 자동출근이 **새 세션**을
     *   열고 거기에 또 15시간 예약이 걸린다. 즉 기사가 퇴근을 계속 안 누르면 마감은
     *   '무한 누적 방지' 가 아니라 '15시간짜리 구간 분할' 로 동작한다. 세션이 쪼개지면
     *   하루 시급·근무시간 집계도 그 경계에서 끊긴다.
     *
     *   자동출근 자체를 막지는 않는다 — 막으면 그 운행의 시간·거리가 아예 안 잡힌다.
     *   대신 **이어진 것이라는 사실을 남긴다.** 집계가 나중에 이 표시를 보고 세션을 이어 붙일 수
     *   있고, 관측(R15)에서 '자동마감 → 즉시 재출근' 반복을 찾아낼 수 있다.
     */
    fun noteChainedAutoStart(context: Context, now: Long) {
        try {
            val p = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            val lastEnd = p.getLong("last_work_end", 0L)
            if (lastEnd <= 0L || now - lastEnd > CHAIN_WINDOW_MS) {
                // 이어진 게 아니다 — 사슬을 끊는다.
                p.edit().remove("work_chain_count").remove("work_chain_root").apply()
                return
            }
            val root = p.getLong("work_chain_root", 0L)
            val lastStart = p.getLong("last_work_start", 0L)
            p.edit()
                .putInt("work_chain_count", p.getInt("work_chain_count", 0) + 1)
                // 사슬의 뿌리 = 최초 세션의 출근 시각. 집계가 '실제로는 한 번 출근' 임을 알 수 있다.
                .putLong("work_chain_root", if (root > 0L) root else (if (lastStart > 0L) lastStart else now))
                .putLong("work_chain_last_gap_ms", now - lastEnd)
                .apply()
        } catch (e: Exception) {}
    }
}
