package com.callradar.app

import android.content.Context
import java.util.Calendar
import java.util.TimeZone

/**
 * [영업일 판정 — 유일한 곳] (2026-09-10 신설)
 *
 * ★ 왜 만들었나 (유저 103 제보 "기록에 뭔가 이상하네요")
 *   화면 한 카드 안에 근무시간이 두 개 떠 있었다. `근무 중 1시간 41분` 과 `÷ 10시간 53분`.
 *   두 값이 **서로 다른 영업일 공식**을 쓰고 있었기 때문이다.
 *
 *   HomeScreen.workDayKey()   Calendar(KST) 기준 — 컴포저블 **안의 지역 함수**라 재사용이 불가능했다
 *   WorkSegments.dayKey()     (t + 9h - shift) / 86400000  — 그래서 새 파일이 새로 썼다
 *   SimpleHomeScreen          또 하나 복사돼 있었다
 *
 *   **재사용할 수 없는 자리에 둔 것이 원인이다.** 공식이 셋이면 언젠가 어긋난다.
 *   실제로 영업일 시작시각(10시)을 넘긴 뒤에도 전날 03:40 근무 구간이 살아남아
 *   시간당 매출 분모가 9시간 30분 부풀었고, 그 결과 3,239원(실제의 약 1/6)이 표시됐다.
 *
 * ★ 규칙: 영업일을 묻는 코드는 **반드시 여기를 부른다.** 새로 계산하지 않는다.
 *   정관 "같은 지표의 가드가 파일마다 다르면 이미 사고가 난 것"이 이 자리다.
 */
object BusinessDay {

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)

    /** 영업일 시작시각(0~23). 기사가 '자정 넘김 귀속'에서 정한다. */
    fun startHour(ctx: Context): Int = prefs(ctx).getInt("day_start_hour", 0)

    /**
     * `t` 가 속한 영업일의 **시작 시각(ms)**.
     * 예) 시작시각 10시일 때 09-09 03:40 → 09-08 10:00 을 돌려준다.
     *
     * 이 값을 그대로 **영업일 키**로도 쓴다. 별도의 인덱스를 만들지 않는 이유는,
     * 키와 경계가 따로 놀면 "키는 같은데 경계는 다른" 상태가 생기기 때문이다
     * (이번 사고가 정확히 그 상태였다 — 저장 시점 키만 비교하니 지난 구간이 살아남았다).
     */
    fun startOf(ctx: Context, t: Long = System.currentTimeMillis()): Long {
        val h = startHour(ctx)
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))
        c.timeInMillis = t
        if (c.get(Calendar.HOUR_OF_DAY) < h) c.add(Calendar.DAY_OF_YEAR, -1)
        c.set(Calendar.HOUR_OF_DAY, h)
        c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** 지금이 속한 영업일의 시작 시각. 저장 키로 쓴다. */
    fun key(ctx: Context, t: Long = System.currentTimeMillis()): Long = startOf(ctx, t)

    /** `t` 가 **지금과 같은 영업일**인가. 지난 영업일의 값을 오늘 것으로 되살리지 않기 위한 가드. */
    fun isToday(ctx: Context, t: Long): Boolean =
        t > 0L && startOf(ctx, t) == startOf(ctx)
}
