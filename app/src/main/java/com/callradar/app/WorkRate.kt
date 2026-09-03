package com.callradar.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 시간당 매출 — **일시정지를 뺀 실제 근무시간**으로 계산한다.
 *
 * ★ 왜 만들었나 (2026-09-03 유저 108 제보)
 *   "일 시작하고 잠시 멈춤 누르고 다시 재개하면, 실제 일한 시간이 아니라
 *    잠시멈춤했던 시간까지 포함돼서 시간당 매출이 계산됩니다."
 *
 *   맞는 제보였다. 두 화면이 이렇게 쓰고 있었다:
 *       hoursForRate = maxOf(workedHours, todayActiveHours)
 *   `workedHours` 는 정지를 제대로 뺐는데, `todayActiveHours`(서버가 준 '운행이 있었던 시의 개수')는
 *   **정지를 전혀 모른다.** `maxOf` 가 큰 쪽을 고르므로, 정지로 workedHours 가 줄면
 *   **activeHours 가 이겨서 정지 구간이 분모에 그대로 남는다.**
 *   실측 시뮬(정지 2.5시간): 실근무 1.33h 인데 분모 3.00h → 시간당이 실제의 **-56%**.
 *   정지를 안 쓰는 기사는 오차 0% 라서 몇 달간 안 드러났다.
 *
 * ★ 왜 activeHours 를 바닥으로 깔았었나 — 반대 방향 사고 때문이다(정관 2026-08-28).
 *   자동기록으로 20만원 쌓인 뒤 출근을 3분 전에 누르면 시간당 **399만원**이 찍혔다.
 *   그때 문제의 본질은 **"분자와 분모의 기간이 다르다"** 였고, maxOf 는 그걸 우회한 임시방편이었다.
 *
 * ★ 그래서 지금은 기간을 맞춘다.
 *       분모 = 근무 구간의 합 (WorkSegments · 정지 제외)
 *       분자 = **그 구간 안에서** 발생한 매출 (`/api/fare-segments`)
 *   기간이 같아지면 maxOf 가 필요 없다. 출근을 늦게 눌러도 분자가 같이 잘려 폭주도 안 난다.
 *
 * ★ 폴백 — 출근 버튼을 안 쓰는 기사는 구간이 없다. 그때만 예전 방식(활동시간)을 쓴다.
 *   구간이 없으면 정지도 없으므로 예전 방식이 틀리지 않는다.
 */
object WorkRate {

    /** @param fare 근무 구간 안의 매출  @param workedMs 정지를 뺀 실제 근무 ms  @param fallback 폴백을 썼는지 */
    data class Rate(val fare: Int, val workedMs: Long, val trips: Int, val fallback: Boolean) {
        val hours: Double get() = workedMs / 3_600_000.0
        /** 1시간 미만이면 -1. 화면은 이때 숫자 대신 "1시간 후"를 보여준다(정관 표시 원칙). */
        val perHour: Int get() = if (hours >= 1.0) (fare / hours).toInt() else -1
    }

    /**
     * 근무 구간 기준 시간당 매출. 구간이 없거나 조회가 실패하면 null → 화면이 폴백을 쓴다.
     */
    suspend fun ofSegments(ctx: Context, userId: String): Rate? {
        if (userId.isBlank()) return null
        val segs = try { WorkSegments.segments(ctx) } catch (e: Exception) { return null }
        if (segs.isEmpty()) return null
        val q = segs.joinToString(",") { (s, e) -> "$s-$e" }
        return try {
            val body = withContext(Dispatchers.IO) {
                (URL("${com.callradar.app.screen.Config.SERVER_URL}/api/fare-segments/$userId?seg=$q")
                    .openConnection().apply {
                        Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") }
                    } as HttpURLConnection).apply { connectTimeout = 7000; readTimeout = 12000 }
                    .inputStream.bufferedReader().use { it.readText() }
            }
            val o = JSONObject(body)
            // 서버가 겹친 구간을 합쳐서 workedMs 를 돌려준다 — 앱에서 더하면 같은 시간을 두 번 셀 수 있다.
            val worked = o.optLong("workedMs", 0L)
            if (worked <= 0L) null
            else Rate(o.optInt("fare", 0), worked, o.optInt("tripCount", 0), false)
        } catch (e: Exception) { null }
    }
}
