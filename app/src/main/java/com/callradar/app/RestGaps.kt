package com.callradar.app

import android.content.Context

/**
 * [v93] 근무 세션 안에서 '운행이 없던 긴 구간'을 찾아낸다.
 *
 * 왜 만들었나 (2026-08-26 서버 실측):
 *   90일치 근무세션을 훑었더니 52명이 1시간 넘는 무운행 구간을 일시정지 없이
 *   근무시간에 그대로 넣고 있었다. 밥 먹고 온 시간, 집에 다녀온 시간이 근무로 잡히면
 *   시간당 매출이 실제보다 낮게 나온다. 콜레이더의 핵심 지표가 틀리는 것이다.
 *
 * 왜 자동으로 안 빼나:
 *   택시에서 '대기'는 애매하다. 공항 대기줄 3시간은 명백히 근무다. 차고지에서 콜을
 *   기다린 2시간도 근무다. 집에 가서 밥 먹은 2시간은 아니다.
 *   **앱은 이 셋을 구분할 수 없고, 기사만 안다.** GPS로 '집인지 공항인지' 추론하는 건
 *   사생활을 캐는 짓이라 더 나쁘다.
 *   그래서 여기서는 '후보'만 찾고, 뺄지 말지는 퇴근할 때 기사에게 묻는다.
 *
 * 판단 근거로 실차(loaded) 궤적 점을 쓴다. 공차 점은 콜을 기다리며 돌아다닌 것일 수도
 * 있어 근무가 아니라고 볼 수 없다. '손님을 태운 적이 없는 구간'만 후보로 본다.
 */
object RestGaps {

    /** 이 시간 이상 비어 있어야 물어본다. 짧은 공백까지 묻으면 퇴근할 때마다 성가시다. */
    const val MIN_GAP_MS = 60 * 60 * 1000L

    data class Gap(val start: Long, val end: Long) {
        val minutes: Long get() = (end - start) / 60_000L
    }

    /**
     * @param workStart 출근 시각
     * @param now       퇴근 시각
     * @return 실차 기록이 없는 MIN_GAP_MS 이상 구간들. 판단할 근거가 없으면 빈 목록.
     */
    fun find(ctx: Context, workStart: Long, now: Long): List<Gap> {
        if (workStart <= 0L || now <= workStart) return emptyList()
        val pts = try {
            LocalTrackDatabase.getInstance(ctx).pointsBetween(workStart, now)
        } catch (e: Exception) { return emptyList() }

        // 궤적이 아예 없으면 '운행이 없었다'가 아니라 '알 수 없다'이다.
        //  위치 권한이 꺼져 있었거나 서비스가 안 떴을 수 있다. 모르면 묻지 않는다.
        if (pts.isEmpty()) return emptyList()

        val loaded = pts.filter { it.loaded }.map { it.ts }.sorted()
        // 하루 종일 실차가 한 번도 없었다면 그건 근무 자체가 없었던 날일 가능성이 크다.
        //  섣불리 "8시간 쉬셨죠?"라고 묻는 건 무례하고 위험하다. 넘어간다.
        if (loaded.isEmpty()) return emptyList()

        val out = ArrayList<Gap>()
        var prev = workStart
        for (t in loaded) {
            if (t - prev >= MIN_GAP_MS) out.add(Gap(prev, t))
            prev = maxOf(prev, t)
        }
        if (now - prev >= MIN_GAP_MS) out.add(Gap(prev, now))
        return out
    }

    /** "14:00~16:30 (2시간 30분)" */
    fun label(g: Gap): String {
        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
            .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul") }
        val m = g.minutes
        val dur = if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
        return "${f.format(java.util.Date(g.start))}~${f.format(java.util.Date(g.end))}  ($dur)"
    }
}
