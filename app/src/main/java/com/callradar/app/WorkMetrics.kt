package com.callradar.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * [근무 파생지표 — 한 입력에서 같이 만든다] (2026-09-10 신설)
 *
 * ★ 왜 만들었나 (유저 103 제보)
 *   시간당 매출과 km당 매출이 **서로 다른 분자**를 쓰고 있었다.
 *     perHour = segRate.fare   / hours     35,300원 (근무 구간 안만)
 *     perKm   = sessionFare    / distKm    52,600원 (오늘 총매출, 근무 밖 포함)
 *   같은 카드에 "근무 밖 17,300원 제외"라고 써 놓고 바로 아래 칸은 그걸 포함했다.
 *   km당 매출이 49% 부풀었고, 기사가 암산하면 절대 안 맞는다.
 *
 *   경위: 09-03(0162df4)에 시간당의 **분자**를 근무구간 매출로 바꾸면서,
 *   두 줄 아래 있던 km당(08-10 이후 그대로)을 안 봤다.
 *   분모만 고치는 작업인 줄 알았는데 분자까지 바뀌었고, 그러면 **그 분자를 쓰는 다른 곳**을
 *   세었어야 했다. 안 세었다. (정관: 한 곳을 고칠 때 '같은 종류'를 좁게 잡으면 나머지가 남는다)
 *
 * ★ 그래서 분자를 인자로 받지 않는다. **여기서 한 번만 정하고 둘 다 그걸로 만든다.**
 *   한쪽만 바꾸는 것이 구조적으로 불가능해진다.
 */
object WorkMetrics {

    /**
     * @param fare      근무 구간 안에서 발생한 매출 (분자 — 두 지표가 공유한다)
     * @param workedMs  일시정지를 뺀 실제 근무시간 (시간당의 분모)
     * @param distKm    근무 중 이동 거리 (km당의 분모)
     * @param fallback  근무 구간을 못 구해 예전 방식(활동시간)으로 계산했는지
     */
    data class Snap(
        val fare: Int,
        val workedMs: Long,
        val distKm: Float,
        val fallback: Boolean,
    ) {
        val hours: Double get() = workedMs / 3_600_000.0

        /** 1시간 미만이면 null → 화면은 "1시간 후"를 보여준다(정관 표시 원칙). */
        val perHour: Int? get() = if (hours >= 1.0) (fare / hours).toInt() else null

        /** 5km 미만이면 null → 화면은 "5km 후". 분모가 '탭 초기화'로 0이 될 수 있어 하한이 필요하다. */
        val perKm: Int? get() = if (distKm >= 5f) (fare / distKm).toInt() else null
    }

    /**
     * 화면이 **나란히 보여줄 숫자들이 서로 검산되는지** 확인한다.
     *
     * ★ 이번 사고에서 제일 아픈 점은, 한 카드 안에 `1시간 41분` 과 `÷ 10시간 53분` 이
     *   위아래로 붙어 있었는데 **앱이 그게 모순인 줄 몰랐다**는 것이다.
     *   알아챈 건 기사였다. 지금 구조에서 마지막 검증자가 기사인 셈이다.
     *   → 앱이 스스로 검산하고, 안 맞으면 **숫자를 보여주지 않고 서버에 보고한다.**
     *   틀린 숫자를 보여주는 것보다 안 보여주는 게 낫다(공항 '대기 0대'를 안 보여주기로 한 것과 같은 이유).
     *
     * @param displayedMs 큰 글씨로 보여주는 근무시간(prefs 기준)
     * @return 어긋난 사유. null 이면 정상.
     */
    fun mismatch(snap: Snap, displayedMs: Long): String? {
        if (snap.fallback) return null            // 폴백은 애초에 기준이 다르다 — 비교 대상 아님
        if (displayedMs <= 0L || snap.workedMs <= 0L) return null
        val diff = kotlin.math.abs(snap.workedMs - displayedMs)
        // 5분·10% 를 둘 다 넘을 때만 잡는다. 초 단위 반올림·틱 지연은 정상이다.
        if (diff <= 5 * 60_000L) return null
        if (diff * 10 < maxOf(snap.workedMs, displayedMs)) return null
        return "worked_ms_mismatch disp=${displayedMs / 60000}m seg=${snap.workedMs / 60000}m"
    }

    /** 어긋남을 서버에 남긴다. 기사가 제보하기 전에 우리가 먼저 보기 위한 것이다. */
    fun report(ctx: Context, reason: String) {
        val p = ctx.getSharedPreferences("callradar_prefs", android.content.Context.MODE_PRIVATE)
        // 같은 사유를 화면 그릴 때마다 보내면 로그가 못 쓰게 된다. 하루 한 번으로 제한한다.
        val key = "metric_report_day"
        val today = BusinessDay.key(ctx)
        if (p.getLong(key, 0L) == today) return
        p.edit().putLong(key, today).apply()
        val userId = p.getString("user_id", null) ?: return
        Thread {
            try {
                val json = JSONObject().apply {
                    put("user_id", userId)
                    put("event", "METRIC_MISMATCH")
                    put("detail", reason)
                }
                val conn = (URL("${com.callradar.app.screen.Config.SERVER_URL}/api/debug/log")
                    .openConnection().apply {
                        Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") }
                    } as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true; connectTimeout = 5000; readTimeout = 5000
                }
                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) { /* 보고 실패가 화면을 막으면 안 된다 */ }
        }.start()
    }
}
