package com.callradar.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

/**
 * [근무 구간 타임라인]
 *  예전엔 '일시정지 누적 분'만 저장해서, 하루가 끝나도 "언제 일하고 언제 쉬었는지"를 알 수 없었다.
 *  (그래서 근무시간이 06:00~23:00 한 덩어리로만 보이고, 중간에 5시간 쉰 게 안 보였다)
 *
 *  이제 출근·일시정지·재개·퇴근을 구간으로 남겨서 이렇게 보여준다:
 *    06:00~11:00 · 15:00~23:00  (총 13시간)
 *
 *  저장 형식(prefs "work_segments"): [[시작ms, 종료ms], [시작ms, 0]]  — 종료 0이면 진행 중
 *  영업일이 바뀌면 자동으로 새로 시작한다(day_start_hour 기준).
 */
object WorkSegments {
    private const val KEY = "work_segments"
    private const val KEY_DAY = "work_segments_day"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)

    /** 영업일 키 — ★ 직접 계산하지 않는다. BusinessDay 가 유일한 판정처다(2026-09-10). */
    private fun dayKey(ctx: Context, t: Long = System.currentTimeMillis()): Long =
        BusinessDay.key(ctx, t)

    /* ★★ [2026-09-10 유저 103] 저장 시점 키만 믿으면 지난 영업일 구간이 살아남는다.
     *
     *  예전엔 `저장된 KEY_DAY != 지금 dayKey` 면 리스트를 **통째로** 버리는 게 전부였다.
     *  그런데 ensureOpened() 가 지난 영업일의 work_start 로 구간을 다시 만들어 **오늘 키를 찍어**
     *  저장해 버리면, 그 뒤로는 키가 맞으니 영원히 오늘 구간 행세를 한다.
     *  실측: 영업일 시작 10시인데 03:40 구간이 남아 분모가 9시간 30분 부풀었다.
     *
     *  그래서 키 비교에 더해 **구간 하나하나가 오늘 영업일에 속하는지** 다시 본다.
     *  키는 빠른 경로일 뿐이고, 진실은 구간의 시작 시각이다. */
    private fun load(ctx: Context): MutableList<LongArray> {
        val p = prefs(ctx)
        // 영업일이 바뀌었으면 어제 구간은 버린다(오늘 표시용이므로)
        if (p.getLong(KEY_DAY, -1L) != dayKey(ctx)) return mutableListOf()
        return try {
            val arr = JSONArray(p.getString(KEY, "[]"))
            val out = mutableListOf<LongArray>()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONArray(i)
                val s = e.optLong(0)
                if (!BusinessDay.isToday(ctx, s)) continue   // ← 지난 영업일 구간은 오늘 것이 아니다
                out.add(longArrayOf(s, e.optLong(1)))
            }
            out
        } catch (e: Exception) { mutableListOf() }
    }

    private fun save(ctx: Context, list: List<LongArray>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONArray().put(it[0]).put(it[1])) }
        prefs(ctx).edit().putString(KEY, arr.toString()).putLong(KEY_DAY, dayKey(ctx)).apply()
    }

    /**
     * 근무 중인데 구간이 하나도 없으면 출근 시각으로 첫 구간을 열어준다.
     *
     * [v91] 구간 기록이 '출근 버튼'에만 걸려 있어서, 자동출근(플랫폼 콜 감지)이나
     *  서버 동기화로 시작된 세션은 구간이 아예 안 생겼다. 그러면 일시정지를 눌러도
     *  닫을 구간이 없어 무시되고, 재개하면 그 시점 구간 하나만 남아
     *  "19:22~19:22"처럼 표시 조건(· 포함)을 못 채워 타임라인이 안 보였다.
     *  자동기록 쓰는 기사는 출근 버튼을 안 누르므로 이쪽이 오히려 다수다.
     *
     *  어느 경로로 시작됐든 화면을 그릴 때 여기서 메운다.
     */
    fun ensureOpened(ctx: Context) {
        val p = prefs(ctx)
        val ws = p.getLong("work_start", 0L)
        if (ws <= 0L) return
        /* ★★ [2026-09-10 유저 103] '구간이 비었다'는 뜻이 두 가지인데 하나로 봤다.
         *   (A) 자동출근이라 애초에 안 만들어졌다   → 메우는 게 맞다 (이 함수를 만든 이유)
         *   (B) 영업일이 바뀌어 load() 가 버렸다    → 메우면 **어제 근무가 되살아난다**
         *   코드에선 둘 다 list.isEmpty() 로 똑같이 보인다.
         *   실제로 영업일 시작 10시를 넘긴 뒤 화면을 그리는 순간, 아직 남아 있던
         *   어제 출근시각(03:40)으로 오늘 구간이 새로 만들어졌다.
         *   → work_start 가 **오늘 영업일**일 때만 메운다. */
        if (!BusinessDay.isToday(ctx, ws)) return
        val list = load(ctx)
        if (list.isNotEmpty()) return
        // 일시정지 중이면 그 시각까지만, 아니면 열어둔 채로
        val ps = p.getLong("work_pause_start", 0L)
        list.add(longArrayOf(ws, if (ps > ws) ps else 0L))
        save(ctx, list)
    }

    /** 출근 또는 일시정지 해제 → 새 구간 열기 */
    fun open(ctx: Context, t: Long = System.currentTimeMillis()) {
        val list = load(ctx)
        if (list.isNotEmpty() && list.last()[1] == 0L) return   // 이미 열려 있음
        list.add(longArrayOf(t, 0L))
        save(ctx, list)
    }

    /** 일시정지 또는 퇴근 → 현재 구간 닫기 */
    fun close(ctx: Context, t: Long = System.currentTimeMillis()) {
        ensureOpened(ctx)   // 자동출근이라 구간이 없던 경우, 출근 시각으로 먼저 열어둔다
        val list = load(ctx)
        if (list.isEmpty() || list.last()[1] != 0L) return      // 열린 구간 없음
        val open = list.last()
        // 1분 미만 구간은 오조작으로 보고 버림(출근→바로 일시정지 등)
        if (t - open[0] < 60_000L) list.removeAt(list.size - 1) else open[1] = t
        save(ctx, list)
    }

    /**
     * [v93] t 이후에 열린 구간을 걷어낸다 — 자동 재개 '되돌리기'용.
     *  자동 재개로 t에 열린 구간을 지우면, 그 앞의 닫힌 구간(일시정지까지)만 남아
     *  기사가 일시정지를 누른 상태 그대로의 타임라인으로 되돌아간다.
     *  t 이전에 시작된 구간은 건드리지 않는다(진짜 근무를 지우면 안 됨).
     */
    fun dropSince(ctx: Context, t: Long) {
        val list = load(ctx)
        if (list.isEmpty()) return
        val kept = list.filter { it[0] < t }
        if (kept.size != list.size) save(ctx, kept)
    }

    /**
     * [v93] [from, to] 구간을 근무 타임라인에서 들어낸다 — 퇴근 시 '휴식이었다'고 고른 구간용.
     *
     *  paused_total에 더하는 것만으로는 부족하다. 그러면 총 근무시간은 줄어드는데
     *  화면의 근무 구간 표시("07:24~19:30")는 여전히 통으로 남아 둘이 어긋난다.
     *  어제 고친 버그(paused_total과 work_segments가 따로 노는 것)와 같은 뿌리라 같이 맞춰준다.
     *
     *  구간이 잘리는 네 가지 경우를 모두 처리한다:
     *   ① 완전히 안 겹침 → 그대로   ② 통째로 먹힘 → 삭제
     *   ③ 앞/뒤만 겹침 → 잘라서 줄임  ④ 가운데가 먹힘 → 둘로 쪼갬
     */
    fun cutOut(ctx: Context, from: Long, to: Long) {
        if (to <= from) return
        val list = load(ctx)
        if (list.isEmpty()) return
        val now = System.currentTimeMillis()
        val out = ArrayList<LongArray>()
        for (s in list) {
            val a = s[0]
            val b = if (s[1] == 0L) now else s[1]      // 열린 구간은 현재까지로 보고 계산
            val wasOpen = s[1] == 0L
            if (b <= from || a >= to) { out.add(s); continue }        // ① 안 겹침
            if (a >= from && b <= to) continue                        // ② 통째로 먹힘 → 버림
            if (a < from && b > to) {                                 // ④ 가운데가 먹힘 → 둘로
                out.add(longArrayOf(a, from))
                out.add(longArrayOf(to, if (wasOpen) 0L else s[1]))
                continue
            }
            if (a < from) out.add(longArrayOf(a, from))               // ③ 뒤가 먹힘
            else out.add(longArrayOf(to, if (wasOpen) 0L else s[1]))  // ③ 앞이 먹힘
        }
        // 1분 미만으로 남은 조각은 버린다(close와 같은 기준)
        save(ctx, out.filter { it[1] == 0L || it[1] - it[0] >= 60_000L })
    }

    /** 하루 통째로 비우기(퇴근 후 새 영업일 시작 등) */
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).remove(KEY_DAY).apply()
    }

    /** 오늘 구간 목록 (진행 중이면 종료=현재시각으로 채워서 반환) */
    fun segments(ctx: Context): List<Pair<Long, Long>> {
        val now = System.currentTimeMillis()
        return load(ctx).map { it[0] to (if (it[1] == 0L) now else it[1]) }
            .filter { it.second > it.first }
    }

    /** 실제 근무 분(휴식 제외) */
    fun workedMin(ctx: Context): Long =
        segments(ctx).sumOf { (s, e) -> (e - s) } / 60_000L

    /** 쉰 시간(구간 사이 공백) 분 */
    fun restMin(ctx: Context): Long {
        val segs = segments(ctx)
        if (segs.size < 2) return 0L
        var rest = 0L
        for (i in 1 until segs.size) rest += (segs[i].first - segs[i - 1].second)
        return (rest / 60_000L).coerceAtLeast(0L)
    }

    /** "06:00~11:00 · 15:00~23:00" 형태 문자열. 구간 없으면 빈 문자열 */
    fun format(ctx: Context): String {
        val f = SimpleDateFormat("HH:mm", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }
        return segments(ctx).joinToString(" · ") { (s, e) -> "${f.format(Date(s))}~${f.format(Date(e))}" }
    }

    /** 서버 전송·영수증 저장용 JSON 문자열 */
    fun toJson(ctx: Context): String {
        val arr = JSONArray()
        segments(ctx).forEach { (s, e) -> arr.put(JSONArray().put(s).put(e)) }
        return arr.toString()
    }
}
