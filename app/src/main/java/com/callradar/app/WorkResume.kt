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

            /* ★ [소유권 전이 검사 — 2026-10-05]
             *  이 경로는 세 번째 상태 변경 주체다(자동출근 2곳 + 여기). 전수점검에서 이 함수가
             *  meter_local 을 설정하지 않는다는 것이 확인됐다.
             *  그렇다고 여기서 meter_local=true 를 무조건 넣지 않는다 — 이 폰이 미터 소유자라는
             *  근거가 여기에는 없다(재개를 부른 건 '운행이 저장됐다'는 사실뿐이고, 그 운행이
             *  이 폰에서 시작된 세션의 것인지는 이 함수가 모른다). 무조건 넣으면 두 기기
             *  이중 누적 방어가 무력화된다.
             *  대신 전이 전후 값을 남겨서, 재개가 소유권·누적거리를 **건드리지 않았다는 것**을
             *  실기기 덤프로 확인할 수 있게 한다. 값이 달라지면 그게 결함 증거다. */
            val ownerBefore = p.getBoolean("meter_local", false)
            val distBefore = p.getFloat("work_distance_m", 0f)

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

            /* ★★ [자동마감 재예약] 2026-10-07 실운행에서 잡힌 결함.
             *  이 함수는 WorkAutoEnd 를 **한 번도 부르지 않았다**(전수 확인). 그래서 일시정지를
             *  풀어도 자동마감 알람이 옛 시각에 그대로 남았다. 알람 시각은 workStart 기준
             *  벽시계였으니, 쉰 시간이 전부 근무로 계산돼 일찍 터졌다.
             *
             *  실측(영진, 10-07): 07:13 출근 → 16:08 정지 → 19:10 재개 → 00:49 까지 운행.
             *  정지 3시간 2분. 15시간 알람은 22:13 에 걸렸고 그때 실근무는 11시간 58분이었다.
             *  운행 중 유예로 22:46 에 마감돼 **일하는 중에 퇴근 처리**됐고, 52초 뒤 새 콜이
             *  자동출근을 띄워 세션이 67분 조각으로 쪼개졌다.
             *
             *  여기서 newPt(늘어난 정지 누적)로 다시 예약하면 쉰 만큼 뒤로 밀린다.
             *  멱등하다 — 발화시각이 workStart + pausedTotal + maxHours 로만 정해지므로
             *  재개가 여러 번 일어나도 '실근무 maxHours' 라는 기준은 변하지 않는다.
             *
             *  maxHours<=0(기사가 자동마감을 끈 상태)이면 schedule 이 cancel 로 빠진다 —
             *  여기서 15시간을 강제로 되살리지 않는다. */
            try {
                val mh = p.getInt("work_max_hours", 0)
                com.callradar.app.WorkAutoEnd.schedule(ctx, ws, mh, newPt)
            } catch (e: Exception) {}

            // 전이 후 값을 다시 읽어 보존 여부를 기록한다. 이 함수는 둘 중 어느 것도 쓰지 않으므로
            // 정상이면 before == after 다. 어긋나면 다른 경로가 같은 창에서 끼어든 것이다.
            val ownerAfter = p.getBoolean("meter_local", false)
            val distAfter = p.getFloat("work_distance_m", 0f)
            p.edit()
                .putBoolean("resume_owner_before", ownerBefore)
                .putBoolean("resume_owner_after", ownerAfter)
                .putFloat("resume_dist_before_m", distBefore)
                .putFloat("resume_dist_after_m", distAfter)
                .putInt(
                    "resume_owner_mismatch",
                    p.getInt("resume_owner_mismatch", 0) +
                        (if (ownerBefore != ownerAfter || distBefore != distAfter) 1 else 0)
                )
                .apply()

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
