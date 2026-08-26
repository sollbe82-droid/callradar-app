package com.callradar.app

import android.graphics.Rect
import com.google.mlkit.vision.text.Text

/**
 * [v96] OCR 결과를 '납작한 글자'가 아니라 '자리 잡힌 글자'로 다루는 도구.
 *
 * ─ 왜 만들었나 ────────────────────────────────────────────────────
 * 기존 파서는 ML Kit 이 준 `Text.text` (줄바꿈으로 이어붙인 문자열)에 정규식을 돌렸다.
 * 그런데 영수증·장부는 **표**다. 표를 문자열로 납작하게 만들면 이런 일이 생긴다.
 *
 *   실제 종이:            납작해진 문자열:
 *   ┌──────┬────────┐      "합계
 *   │ 합계 │ 152,300│      수량
 *   │ 수량 │  38.412│      152,300
 *   └──────┴────────┘       38.412"
 *
 * 라벨과 값이 떨어져 버려서, "합계 옆의 숫자"를 정규식으로 잡을 방법이 없다.
 * 그래서 `Regex("총합계\\s*([0-9,]+)")` 같은 패턴이 계속 빗나갔다.
 * 달력형 장부는 더 심하다 — 어느 숫자가 어느 날짜 칸인지 복원이 아예 불가능하다.
 *
 * **그런데 ML Kit 은 애초에 좌표를 같이 준다.** Element 마다 boundingBox(Rect)가 있다.
 * 우리가 그걸 버리고 있었을 뿐이다. 사진을 밖으로 보낼 필요가 없다 — 폰 안에서 풀린다.
 *
 * ─ 무엇을 하나 ───────────────────────────────────────────────────
 *  · valueRightOf("합계")  : '합계' 라는 글자를 찾고, **같은 줄 높이에서 오른쪽**의 숫자를 집는다
 *  · valueBelow("수량")    : 라벨 **바로 아래 칸**의 숫자를 집는다 (세로 표)
 *  · rows() / columns()   : y·x 좌표를 군집화해 표의 행·열을 복원한다 (달력형 장부)
 *
 * 좌표는 사진 크기에 따라 달라지므로, 임계값은 전부 **글자 높이 기준 상대값**으로 잡는다.
 * (절대 픽셀로 잡으면 고해상도 사진에서 전부 어긋난다 — 예전 실수의 반복을 막는다.)
 */
object OcrLayout {

    /** 좌표를 가진 낱말 하나. ML Kit 의 Element 를 우리가 쓰기 쉬운 형태로 옮긴 것. */
    data class Word(val text: String, val box: Rect) {
        val cx: Int get() = box.centerX()
        val cy: Int get() = box.centerY()
        val h: Int get() = box.height().coerceAtLeast(1)
    }

    /** ML Kit 결과 → 낱말 목록. 빈 글자는 버린다. */
    fun words(v: Text): List<Word> {
        val out = ArrayList<Word>(256)
        for (block in v.textBlocks) for (line in block.lines) for (el in line.elements) {
            val b = el.boundingBox ?: continue
            val t = el.text.trim()
            if (t.isNotEmpty()) out.add(Word(t, b))
        }
        return out
    }

    /** 낱말 높이의 중앙값 — 모든 거리 임계값의 기준자. 사진 해상도가 달라도 비율은 유지된다. */
    fun unit(ws: List<Word>): Int {
        if (ws.isEmpty()) return 24
        val hs = ws.map { it.h }.sorted()
        return hs[hs.size / 2].coerceAtLeast(8)
    }

    // ── 숫자 해석 ────────────────────────────────────────────────────
    //  '152,300' '152.300' '1 52,300' 처럼 OCR 이 흘린 형태를 최대한 살린다.
    //  다만 소수(38.412 리터)는 금액과 구분해야 하므로 따로 다룬다.

    private val MONEY = Regex("^[₩\\\\]?([0-9][0-9.,\\s]{2,})원?$")

    /** 금액으로 읽는다. 천단위 구분만 있는 정수만 인정. 아니면 null. */
    fun asMoney(raw: String): Int? {
        val s = raw.trim()
        if (!MONEY.matches(s)) return null
        val digits = s.replace(Regex("[^0-9]"), "")
        if (digits.isEmpty() || digits.length > 9) return null
        // 소수점 형태(38.412)를 금액으로 오독하지 않게: 마지막 구분자 뒤가 3자리가 아니면 버린다.
        val lastSep = s.lastIndexOfAny(charArrayOf(',', '.'))
        if (lastSep >= 0) {
            val tail = s.substring(lastSep + 1).replace(Regex("[^0-9]"), "")
            if (tail.length != 3) return null
        }
        return digits.toIntOrNull()
    }

    /** 리터·단가처럼 소수를 허용해 읽는다. */
    fun asDecimal(raw: String): Double? =
        raw.trim().replace(Regex("[^0-9.]"), "").toDoubleOrNull()

    // ── 라벨 기준 값 찾기 ────────────────────────────────────────────

    /** 라벨 글자를 포함하는 낱말들. 띄어쓰기가 흘러도('합 계') 잡히게 공백을 지우고 비교. */
    private fun findLabels(ws: List<Word>, labels: List<String>): List<Word> {
        val keys = labels.map { it.replace(" ", "") }
        return ws.filter { w ->
            val t = w.text.replace(" ", "")
            keys.any { t.contains(it) }
        }
    }

    /**
     * 라벨 **오른쪽 같은 줄**에서 금액을 찾는다. 영수증에서 가장 흔한 배치다.
     *
     * 같은 줄 판정: 세로 중심 차이가 글자 높이의 0.7배 이내.
     * 여러 개면 라벨에 **가장 가까운 것**을 고른다 (합계 오른쪽에 금액과 부가세가 나란할 때 앞의 것).
     */
    fun moneyRightOf(ws: List<Word>, labels: List<String>): Int? {
        val u = unit(ws)
        var best: Pair<Int, Int>? = null   // (거리, 금액)
        for (lab in findLabels(ws, labels)) {
            for (w in ws) {
                if (w === lab) continue
                if (kotlin.math.abs(w.cy - lab.cy) > u * 0.7) continue   // 다른 줄
                if (w.box.left < lab.box.right - u * 0.3) continue        // 왼쪽에 있음
                val m = asMoney(w.text) ?: continue
                val d = w.box.left - lab.box.right
                if (d > u * 25) continue                                  // 너무 멀면 남의 칸
                if (best == null || d < best!!.first) best = d to m
            }
        }
        return best?.second
    }

    /**
     * 라벨 **바로 아래**에서 금액을 찾는다. 세로형 표(항목 위, 값 아래)용.
     * 가로 중심이 라벨과 겹치고(±2자), 아래로 4줄 이내인 것 중 가장 가까운 것.
     */
    fun moneyBelow(ws: List<Word>, labels: List<String>): Int? {
        val u = unit(ws)
        var best: Pair<Int, Int>? = null
        for (lab in findLabels(ws, labels)) {
            for (w in ws) {
                if (w === lab) continue
                if (w.cy <= lab.cy) continue
                val dy = w.cy - lab.cy
                if (dy > u * 5) continue
                if (kotlin.math.abs(w.cx - lab.cx) > u * 4) continue
                val m = asMoney(w.text) ?: continue
                if (best == null || dy < best!!.first) best = dy to m
            }
        }
        return best?.second
    }

    /** 오른쪽 우선, 없으면 아래. 영수증 레이아웃 대부분이 이 둘 중 하나다. */
    fun money(ws: List<Word>, vararg labels: String): Int? {
        val l = labels.toList()
        return moneyRightOf(ws, l) ?: moneyBelow(ws, l)
    }

    /** 라벨 오른쪽/아래의 소수값(리터·단가). */
    fun decimal(ws: List<Word>, vararg labels: String): Double? {
        val u = unit(ws)
        val l = labels.toList()
        for (lab in findLabels(ws, l)) {
            val cands = ws.filter {
                it !== lab && it.box.left >= lab.box.right - u * 0.3 &&
                    kotlin.math.abs(it.cy - lab.cy) <= u * 0.7
            }.sortedBy { it.box.left }
            for (c in cands) {
                val d = asDecimal(c.text)
                if (d != null && d > 0.0) return d
            }
        }
        return null
    }

    /**
     * 화면에서 **가장 큰 금액**. 영수증의 결제 총액은 보통 제일 크게 인쇄된다.
     * 라벨을 못 찾았을 때의 마지막 수단이며, 글자 크기 상위 30% 중에서만 고른다.
     */
    fun biggestMoney(ws: List<Word>): Int? {
        val cands = ws.mapNotNull { w -> asMoney(w.text)?.let { w to it } }
        if (cands.isEmpty()) return null
        val cut = cands.map { it.first.h }.sorted()[(cands.size * 0.7).toInt().coerceIn(0, cands.size - 1)]
        return cands.filter { it.first.h >= cut }.maxByOrNull { it.second }?.second
    }

    // ── 표(그리드) 복원 ──────────────────────────────────────────────

    /**
     * y 좌표로 군집화해 **행**을 만든다. 달력형 장부·거래내역표에 쓴다.
     * 같은 행 판정: 세로 중심 차이가 글자 높이의 0.8배 이내.
     */
    fun rows(ws: List<Word>): List<List<Word>> {
        if (ws.isEmpty()) return emptyList()
        val u = unit(ws)
        val sorted = ws.sortedBy { it.cy }
        val out = ArrayList<MutableList<Word>>()
        var cur = mutableListOf(sorted.first())
        var ref = sorted.first().cy
        for (w in sorted.drop(1)) {
            if (kotlin.math.abs(w.cy - ref) <= u * 0.8) { cur.add(w) }
            else { out.add(cur); cur = mutableListOf(w); ref = w.cy }
        }
        out.add(cur)
        return out.map { r -> r.sortedBy { it.box.left } }
    }

    /**
     * x 좌표로 군집화해 **열 경계**를 만든다. 열 개수를 미리 모를 때 쓴다.
     * 반환값은 각 열의 중심 x 목록(왼→오).
     */
    fun columnCenters(ws: List<Word>): List<Int> {
        if (ws.isEmpty()) return emptyList()
        val u = unit(ws)
        val xs = ws.map { it.cx }.sorted()
        val centers = ArrayList<Int>()
        var group = mutableListOf(xs.first())
        for (x in xs.drop(1)) {
            if (x - group.last() <= u * 2.5) group.add(x)
            else { centers.add(group.average().toInt()); group = mutableListOf(x) }
        }
        centers.add(group.average().toInt())
        return centers
    }
}
