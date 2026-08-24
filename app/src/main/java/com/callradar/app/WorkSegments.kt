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

    /** 영업일 키 — day_start_hour(기본 0시) 기준으로 하루를 가른다 */
    private fun dayKey(ctx: Context, t: Long = System.currentTimeMillis()): Long {
        val shift = prefs(ctx).getInt("day_start_hour", 0) * 3600_000L
        return (t + 9L * 3600_000L - shift) / 86_400_000L
    }

    private fun load(ctx: Context): MutableList<LongArray> {
        val p = prefs(ctx)
        // 영업일이 바뀌었으면 어제 구간은 버린다(오늘 표시용이므로)
        if (p.getLong(KEY_DAY, -1L) != dayKey(ctx)) return mutableListOf()
        return try {
            val arr = JSONArray(p.getString(KEY, "[]"))
            MutableList(arr.length()) { i ->
                val e = arr.getJSONArray(i); longArrayOf(e.optLong(0), e.optLong(1))
            }
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
