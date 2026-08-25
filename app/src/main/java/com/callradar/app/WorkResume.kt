package com.callradar.app

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * [v93] 일시정지 자동 재개 — 한 곳에서만 처리한다.
 *
 * 왜 만들었나 (2026-08-25 실측):
 *   work_start 07:24 / work_segments [[07:24, 11:19]] / pause_start 0 / paused_total 33분
 *   → 11:52에 일시정지가 저절로 풀렸고, 그날 운행은 07:46·10:20 두 건뿐이었다.
 *     11:52엔 운행이 없었다. 근무시간 3시간 50분이 허공에 잡혔고 시급이 그만큼 낮아졌다.
 *
 * 원인은 두 겹이었다:
 *   ① FloatingTripService.ensureWorkSessionActive()와 NaviIntentReceiver.ensureWorkStarted()가
 *      '운행 시작 시도'만으로 일시정지를 풀었다. 플로팅 탑승→취소, 자동기록 오탐도 전부 통과했다.
 *   ② 재개할 때 WorkSegments.open()을 안 불러, 재개 구간이 타임라인에 아예 안 남았다.
 *      나중에 왜 켜졌는지 알 방법이 없었다.
 *
 * 그래서 이렇게 바꿨다:
 *   · 자동 재개는 **운행이 확정 저장된 시점**에만 건다(시작 시도로는 안 풀린다).
 *   · 재개하면 구간을 열고, 되돌릴 수 있게 직전 값을 남긴다.
 *
 * 원칙: 일시정지는 기사가 명시적으로 누른 것이다. 조용히 뒤집지 않는다.
 *       뒤집어야 한다면 근거를 남기고 되돌릴 길을 준다.
 */
object WorkResume {

    private const val PREFS = "callradar_prefs"

    /** 자동 재개가 일어난 시각(ms). 0이면 표시할 게 없다. 홈 근무카드가 이걸 읽는다. */
    const val K_AT = "auto_resume_at"
    /** 재개 직전 work_paused_total — 되돌리기용 */
    const val K_PREV_PT = "auto_resume_prev_pt"
    /** 재개 직전 work_pause_start — 되돌리기용 */
    const val K_PREV_PS = "auto_resume_prev_ps"
    /** 무엇 때문에 재개됐는지 (예: "운행 저장") */
    const val K_REASON = "auto_resume_reason"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 일시정지 중이면 근무를 재개한다.
     *
     * **운행이 확정 저장된 시점에만 호출할 것.** 운행 시작 시도에서 부르면 예전 버그로 되돌아간다.
     *   · FloatingTripService: 로컬 DB savePending 성공 직후
     *   · NaviIntentReceiver : finalizeCurrentTrip (운행 완료 마감)
     *
     * @return 실제로 재개했으면 true. 일시정지가 아니었거나 미출근이면 false.
     */
    fun resumeIfPaused(ctx: Context, reason: String): Boolean {
        try {
            val p = prefs(ctx)
            val ws = p.getLong("work_start", 0L)
            if (ws <= 0L) return false                 // 미출근 — 재개할 근무가 없다
            val ps = p.getLong("work_pause_start", 0L)
            if (ps <= 0L) return false                 // 일시정지 아님 — 건드릴 것 없다

            val now = System.currentTimeMillis()
            val prevPt = p.getLong("work_paused_total", 0L)
            val newPt = prevPt + (now - ps)

            p.edit()
                .putLong("work_paused_total", newPt)
                .putLong("work_pause_start", 0L)
                // 되돌리기용 흔적 — 홈 근무카드가 읽어 "HH:mm 자동 재개됨 · 되돌리기"를 띄운다
                .putLong(K_AT, now)
                .putLong(K_PREV_PT, prevPt)
                .putLong(K_PREV_PS, ps)
                .putString(K_REASON, reason)
                .apply()

            // [핵심] 재개 구간을 연다. 이게 없어서 재개분이 타임라인에 안 남았다.
            try { WorkSegments.open(ctx, now) } catch (e: Exception) {}

            pushWorkSession(ctx, ws, newPt, 0L)
            try { Telemetry.log(ctx, "work_auto_resume", reason) } catch (e: Exception) {}
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /** 표시할 자동 재개가 있으면 그 시각(ms), 없으면 0 */
    fun pendingAt(ctx: Context): Long =
        try { prefs(ctx).getLong(K_AT, 0L) } catch (e: Exception) { 0L }

    fun reason(ctx: Context): String =
        try { prefs(ctx).getString(K_REASON, "") ?: "" } catch (e: Exception) { "" }

    /**
     * 되돌리기 — 자동 재개를 취소하고 기사가 눌러둔 일시정지 상태로 복원한다.
     * 재개 이후에 열린 구간도 걷어내, 타임라인이 일시정지 직전 모습으로 돌아간다.
     */
    fun undo(ctx: Context): Boolean {
        try {
            val p = prefs(ctx)
            val at = p.getLong(K_AT, 0L)
            if (at <= 0L) return false
            val ws = p.getLong("work_start", 0L)
            if (ws <= 0L) { clear(ctx); return false }   // 그 사이 퇴근했으면 되돌릴 대상이 없다

            val prevPt = p.getLong(K_PREV_PT, 0L)
            val prevPs = p.getLong(K_PREV_PS, 0L)

            p.edit()
                .putLong("work_paused_total", prevPt)
                .putLong("work_pause_start", prevPs)
                .remove(K_AT).remove(K_PREV_PT).remove(K_PREV_PS).remove(K_REASON)
                .apply()

            try { WorkSegments.dropSince(ctx, at) } catch (e: Exception) {}

            pushWorkSession(ctx, ws, prevPt, prevPs)
            try { Telemetry.log(ctx, "work_auto_resume_undo", "") } catch (e: Exception) {}
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /** 흔적만 지운다(기사가 '확인'만 누른 경우 · 퇴근 시 정리). 근무 상태는 안 건드린다. */
    fun clear(ctx: Context) {
        try {
            prefs(ctx).edit()
                .remove(K_AT).remove(K_PREV_PT).remove(K_PREV_PS).remove(K_REASON)
                .apply()
        } catch (e: Exception) {}
    }

    /**
     * 서버에 근무세션 상태를 밀어넣는다.
     * 서버가 마지막 방어선이다 — 폰만 고치면 20초 pull이 옛 상태로 되돌린다.
     */
    private fun pushWorkSession(ctx: Context, workStart: Long, pausedTotal: Long, pauseStart: Long) {
        val p = prefs(ctx)
        val uid = p.getString("user_id", null) ?: return
        if (uid.isBlank()) return
        val startFare = p.getInt("work_start_fare", 0)
        Thread {
            try {
                val json = JSONObject().apply {
                    put("user_id", uid)
                    put("work_start", workStart)
                    put("paused_total", pausedTotal)
                    put("pause_start", pauseStart)
                    put("start_fare", startFare)
                }
                val conn = (URL("${com.callradar.app.screen.Config.SERVER_URL}/api/work-session")
                    .openConnection()
                    .apply {
                        Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") }
                    } as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true; connectTimeout = 15000; readTimeout = 20000
                }
                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {}
        }.start()
    }

    /** 궤적이 끊기지 않게 근무세션 서비스만 확실히 띄운다(근무 상태는 안 바꾼다). */
    fun ensureTrackingService(ctx: Context) {
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                ctx, Intent(ctx, WorkSessionService::class.java)
            )
        } catch (e: Exception) {}
    }
}
