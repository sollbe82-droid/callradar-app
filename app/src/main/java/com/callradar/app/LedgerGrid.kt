package com.callradar.app

import com.google.mlkit.vision.text.Text

/**
 * [v96] 장부 사진에서 '날짜 → 금액'을 좌표로 복원한다. 폰 안에서만 돈다.
 *
 * ─ 왜 이렇게 하나 ────────────────────────────────────────────────
 * 기존 방식은 OCR 이 준 납작한 문자열에 정규식을 돌렸다. 장부는 표라서
 * 문자열로 만들면 날짜와 금액의 짝이 끊긴다. "하나도 안 맞는다"의 원인이 이거였다.
 *
 * 한때 이걸 풀려고 사진을 AI 에 보내려 했는데, 그건 사진을 국외로 내보내는 일이라
 * 접었다. 그리고 접고 보니 **애초에 보낼 필요가 없었다** — ML Kit 이 글자마다
 * 좌표를 주고 있었고, 표의 짝짓기는 그 좌표만으로 풀린다.
 *
 * ─ 두 가지 배치를 다룬다 ────────────────────────────────────────
 *  (A) 목록형 : 한 줄에 [날짜] [금액] 이 나란히
 *        3   152,300
 *        4   131,000
 *      → 같은 행에서 왼쪽 날짜, 오른쪽 금액을 짝짓는다.
 *
 *  (B) 달력형 : 칸 안에 날짜가 작게, 금액이 그 아래
 *        ┌────┬────┐
 *        │ 3  │ 4  │
 *        │152,│131,│
 *        │300 │000 │
 *      → 날짜 글자를 먼저 찾고, **그 아래·같은 칸 폭 안**의 금액을 붙인다.
 *
 * 어느 쪽인지 미리 모르므로 둘 다 시도해서 **더 그럴듯한 쪽**을 고른다.
 * 판단 기준은 "며칠치가 잡혔나"와 "금액이 택시 하루 매출로 말이 되나".
 *
 * ─ 안 하는 것 ────────────────────────────────────────────────────
 * 확신 없는 값을 억지로 채우지 않는다. 못 읽으면 빈 채로 두고 기사가 표에서 고친다.
 * 틀린 숫자를 자신 있게 넣는 것이 아무것도 안 넣는 것보다 나쁘다.
 */
object LedgerGrid {

    /** 택시 하루 매출·지출의 상식적 상한. 자릿수 오독(3,500 → 3500000) 방어. */
    private const val MAX_DAILY = 2_000_000

    /** 하루 금액으로 인정할 하한. 이보다 작으면 날짜·개수 같은 다른 숫자일 확률이 높다. */
    private const val MIN_DAILY = 1_000

    data class Row(val day: Int, val amount: Int)

    /**
     * @param month 사용자가 화면에서 고른 '가져올 월'. 날짜 후보를 1~31로 거르는 데만 쓴다.
     * @return 인식된 행들. 못 풀면 빈 목록(호출측이 기존 정규식 경로로 넘어간다).
     */
    fun parse(v: Text, month: Int): List<ImpRowLite> {
        val ws = OcrLayout.words(v)
        if (ws.size < 6) return emptyList()

        val listRows = parseList(ws)
        val calRows = parseCalendar(ws)

        val best = if (score(calRows) > score(listRows)) calRows else listRows
        return best
            .distinctBy { it.day }
            .sortedBy { it.day }
            .map { ImpRowLite(it.day, it.amount) }
    }

    /** 그럴듯함 점수 — 잡힌 날짜 수가 많고 금액대가 상식적일수록 높다. */
    private fun score(rows: List<Row>): Int {
        if (rows.isEmpty()) return 0
        val days = rows.map { it.day }.distinct().size
        val sane = rows.count { it.amount in MIN_DAILY..MAX_DAILY }
        return days * 2 + sane
    }

    /** 날짜로 볼 수 있는 낱말인가 (1~31의 순수 숫자). */
    private fun asDay(t: String): Int? {
        val s = t.trim().removeSuffix("일")
        if (!Regex("^[0-9]{1,2}$").matches(s)) return null
        val n = s.toIntOrNull() ?: return null
        return if (n in 1..31) n else null
    }

    // ── (A) 목록형 ───────────────────────────────────────────────────
    private fun parseList(ws: List<OcrLayout.Word>): List<Row> {
        val out = ArrayList<Row>()
        for (row in OcrLayout.rows(ws)) {
            // 행의 맨 왼쪽에서 날짜 하나, 그 오른쪽에서 가장 큰 금액 하나.
            val day = row.firstNotNullOfOrNull { asDay(it.text) } ?: continue
            val dayIdx = row.indexOfFirst { asDay(it.text) == day }
            val amount = row.drop(dayIdx + 1)
                .mapNotNull { OcrLayout.asMoney(it.text) }
                .filter { it in MIN_DAILY..MAX_DAILY }
                .maxOrNull() ?: continue
            out.add(Row(day, amount))
        }
        return out
    }

    // ── (B) 달력형 ───────────────────────────────────────────────────
    private fun parseCalendar(ws: List<OcrLayout.Word>): List<Row> {
        val u = OcrLayout.unit(ws)
        val days = ws.mapNotNull { w -> asDay(w.text)?.let { w to it } }
        if (days.isEmpty()) return emptyList()

        val out = ArrayList<Row>()
        for ((dw, d) in days) {
            // 같은 칸으로 볼 범위: 가로는 날짜 글자 기준 ±3자, 세로는 아래로 4줄까지.
            //  칸 경계선을 못 보므로 '가까운 아래쪽'을 칸으로 간주한다.
            val cands = ws.filter { w ->
                w !== dw &&
                    w.cy > dw.cy &&
                    (w.cy - dw.cy) <= u * 4.5 &&
                    kotlin.math.abs(w.cx - dw.cx) <= u * 3.5
            }
            // 한 칸에 여러 줄로 쪼개진 금액이 있을 수 있다 → 가장 위(=첫 줄) 것을 쓴다.
            val amount = cands.sortedBy { it.cy }
                .firstNotNullOfOrNull { w ->
                    OcrLayout.asMoney(w.text)?.takeIf { it in MIN_DAILY..MAX_DAILY }
                } ?: continue
            out.add(Row(d, amount))
        }
        return out
    }
}

/** ImageImportActivity 의 ImpRow 로 바꾸기 전 중간 형태(모듈 경계를 안 흐리려고 분리). */
data class ImpRowLite(val day: Int, val amount: Int)
