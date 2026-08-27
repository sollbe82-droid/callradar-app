package com.callradar.app

import com.google.mlkit.vision.text.Text
import kotlin.math.abs

/**
 * [v96] 주유·충전 영수증 전용 판독기.
 *
 * ─ 왜 또 만드나 ───────────────────────────────────────────────────
 * `OcrLayout` 은 **낱말(Element) 하나** 안에서 라벨을 찾는다. 그런데 영수증은
 * 글자를 띄워서 인쇄한다.
 *
 *     남서울가스:  "수     량:  40.510 L"   → ML Kit: ["수"] ["량:"] ["40.510"] ["L"]
 *     해양가스:    "합 계 금 액   28,596원" → ML Kit: ["합"]["계"]["금"]["액"]["28,596원"]
 *
 * `"수"` 는 `"수량"` 을 포함하지 않는다. 그래서 라벨을 **영원히 못 찾았다.**
 * 대표가 "2달째 이건 학습이 안되나보다" 라고 한 게 이 지점이다.
 *
 * ─ 어떻게 고치나 ──────────────────────────────────────────────────
 *  1. y 좌표로 낱말을 **행**으로 묶는다 (OcrLayout.rows).
 *  2. 행 안의 낱말을 **공백 없이 이어붙여** 라벨을 찾는다 → "수량:40.510L" ✔
 *  3. 라벨 뒤(오른쪽)의 숫자만 값으로 인정한다.
 *  4. 라벨이 머리글에만 있는 **표형**은 머리글의 x좌표로 열을 잡아 아래 행에서 값을 집는다.
 *
 * ─ 이 판독기의 핵심: 찍지 않고 **검산한다** ────────────────────────
 * 주유 영수증은 항상 이 항등식이 성립한다.
 *
 *      수량 × 단가 = 금액        공급가액 + 세액 = 금액
 *
 *   해양가스   23.850 × 1,199.00 = 28,596.15 ≈ 28,596   |  25,996 + 2,600 = 28,596
 *   남서울가스 40.510 × 1,162.00 = 47,072.62 ≈ 47,073   |  42,794 + 4,279 = 47,073
 *
 * 세 값이 서로 맞아떨어지면 **셋 다 제대로 읽은 것**이고, 어긋나면 잘못 읽은 것이다.
 * 그래서 이 판독기는 "제일 큰 숫자를 총액으로 찍는" 짓을 하지 않는다.
 * 검산이 안 되면 `verified=false` 로 돌려주고, 화면은 기사에게 확인을 요청한다.
 */
object FuelReceipt {

    /** 판독 결과. 못 읽은 값은 null 이다 — 0 으로 채워 거짓 확신을 주지 않는다. */
    data class Result(
        val year: Int?,
        val month: Int?,
        val day: Int?,
        val amount: Int?,        // 총 결제금액(원)
        val liters: Double?,     // 수량 (L 또는 kWh)
        val unitPrice: Double?,  // 단가 (원/L, 원/kWh)
        val verified: Boolean,   // 수량×단가=금액 또는 공급가액+세액=금액 이 맞는가
        val how: String,         // 어떻게 얻었는지 (디버그·안내용)
        // [대표 지시] "영수증 항목을 다 파싱해서 입력 칸을 넣어라. 필요한 3항목만 표시되게."
        //  하나의 정답을 맞히려다 두 번 빗나갔다. 읽은 것을 **전부 보여주고** 기사가 확인·수정하게 한다.
        //  공급가액·세액은 기록에 넣지 않지만, 검산 근거로 화면에 보여준다.
        val supply: Int? = null, // 공급가액
        val tax: Int? = null,    // 세액
        val store: String = ""   // 상호명(있으면)
    ) {
        val usable: Boolean get() = amount != null && amount > 0
        /** 하나라도 읽었는가 — 영수증 편집 화면을 띄울지 판단. */
        val anyField: Boolean get() = amount != null || liters != null || unitPrice != null ||
            supply != null || tax != null || day != null
    }

    // ── 값의 상식 범위 ────────────────────────────────────────────────
    //  범위를 벗어난 값은 OCR 오독으로 본다. (사업자번호·승인번호·전화번호 오긁기 차단)
    private const val AMT_MIN = 1_000
    private const val AMT_MAX = 1_000_000      // 택시 1회 충전이 100만원일 수 없다
    private const val QTY_MIN = 0.5
    private const val QTY_MAX = 300.0          // LPG 탱크·전기차 배터리 상한 여유
    private const val PRICE_MIN = 100.0        // 전기 급속 ~300원/kWh
    private const val PRICE_MAX = 5_000.0      // 휘발유 고가 주유소 여유

    // 라벨 후보 — 앞에 있을수록 신뢰도가 높다.
    private val L_TOTAL = listOf(
        "합계금액", "총합계", "합계", "총액", "결제금액", "승인금액",
        "받을금액", "청구금액", "판매금액", "거래금액", "금액"
    )
    private val L_QTY = listOf("수량", "충전량", "주유량", "판매량", "리터")
    private val L_PRICE = listOf("단가", "판매단가", "리터당")
    private val L_SUPPLY = listOf("공급가액", "공급가")
    private val L_TAX = listOf("부가세액", "부가세", "세액")
    private val L_DATE = listOf("판매일시", "거래일시", "승인일시", "일시", "일자", "날짜")

    private val RE_DATE = Regex("(20\\d{2})[./\\-](\\d{1,2})[./\\-](\\d{1,2})")
    private val RE_DATE2 = Regex("(\\d{2})[./\\-](\\d{1,2})[./\\-](\\d{1,2})")

    /** 행의 낱말을 공백 없이 이어붙인 문자열. 라벨 탐색용. */
    private fun rowText(row: List<OcrLayout.Word>): String =
        row.joinToString("") { it.text }.replace(" ", "")

    /**
     * 행 안에서 라벨을 찾고, **라벨 오른쪽**에 남은 글자 조각들을 돌려준다.
     * 라벨과 값이 한 낱말에 붙어 있는 경우("합계금액28,596원")도 잘라낸다.
     */
    private fun afterLabel(row: List<OcrLayout.Word>, label: String): List<String>? {
        val sb = StringBuilder()
        for (i in row.indices) {
            sb.append(row[i].text.replace(" ", ""))
            val at = sb.indexOf(label)
            if (at >= 0) {
                val out = ArrayList<String>()
                // 라벨이 끝난 지점이 이 낱말 안쪽이면, 그 뒤 꼬리를 먼저 넣는다.
                val consumed = sb.length - (at + label.length)
                val cur = row[i].text.replace(" ", "")
                if (consumed in 1..cur.length) {
                    val tail = cur.substring(cur.length - consumed)
                    if (tail.isNotBlank()) out.add(tail)
                }
                for (j in i + 1 until row.size) out.add(row[j].text)
                return out
            }
        }
        return null
    }

    /** 라벨 목록을 우선순위대로 훑어, 라벨 오른쪽의 금액을 찾는다. */
    private fun findMoney(rows: List<List<OcrLayout.Word>>, labels: List<String>): Pair<Int, String>? {
        for (lab in labels) {
            for (row in rows) {
                val t = rowText(row)
                if (!t.contains(lab)) continue
                val tail = afterLabel(row, lab) ?: continue
                // 오른쪽 끝에서부터 본다 — 값 열은 보통 맨 오른쪽이다.
                for (s in tail.asReversed()) {
                    val m = OcrLayout.asMoney(s) ?: continue
                    if (m in AMT_MIN..AMT_MAX) return m to lab
                }
            }
        }
        return null
    }

    /** 라벨 오른쪽의 소수값(수량·단가). 범위를 벗어나면 버린다. */
    private fun findDecimal(
        rows: List<List<OcrLayout.Word>>, labels: List<String>, lo: Double, hi: Double
    ): Pair<Double, String>? {
        for (lab in labels) {
            for (row in rows) {
                val t = rowText(row)
                if (!t.contains(lab)) continue
                val tail = afterLabel(row, lab) ?: continue
                for (s in tail) {
                    val d = OcrLayout.asDecimal(s) ?: continue
                    if (d in lo..hi) return d to lab
                }
            }
        }
        return null
    }

    /**
     * 표형(해양가스) 대응.
     *  머리글 행에서 `단가`·`수량`·`금액` 의 x좌표를 잡고,
     *  그 아래 **숫자가 2개 이상인 첫 행**(=상품 행)에서 각 열에 가장 가까운 숫자를 집는다.
     *
     *  상품명   단가      수량     금액        ← 머리글 (x좌표만 쓴다)
     *  부탄(LPG) 1,199.00  23.850  28,596원    ← 값
     */
    private data class Cols(val qty: Double?, val price: Double?, val amount: Int?)

    private fun byColumns(rows: List<List<OcrLayout.Word>>): Cols {
        var hdrIdx = -1
        var xQty: Int? = null; var xPrice: Int? = null; var xAmt: Int? = null

        for ((i, row) in rows.withIndex()) {
            val t = rowText(row)
            // 머리글은 '단가'와 '수량'이 같은 줄에 있고, 숫자가 거의 없다.
            if (!(t.contains("수량") && t.contains("단가"))) continue
            var acc = 0
            for (w in row) {
                val before = acc
                acc += w.text.replace(" ", "").length
                val seg = t.substring(before, acc)
                if (seg.contains("수") || seg.contains("량")) xQty = w.cx
                if (seg.contains("단") || seg.contains("가")) xPrice = xPrice ?: w.cx
                if (seg.contains("금") || seg.contains("액")) xAmt = w.cx
            }
            // 낱말이 쪼개졌으면 위 근사가 흔들린다 — 라벨 낱말 자체로 다시 잡는다.
            row.forEach { w ->
                val s = w.text.replace(" ", "")
                if (s.contains("수량")) xQty = w.cx
                if (s.contains("단가")) xPrice = w.cx
                if (s.contains("금액")) xAmt = w.cx
            }
            hdrIdx = i
            break
        }
        if (hdrIdx < 0) return Cols(null, null, null)

        // 머리글 바로 아래에서 숫자가 2개 이상인 첫 행 = 상품 행
        for (j in hdrIdx + 1 until rows.size) {
            val row = rows[j]
            val nums = row.filter { OcrLayout.asDecimal(it.text) != null && it.text.any { c -> c.isDigit() } }
            if (nums.size < 2) continue
            fun nearest(x: Int?): OcrLayout.Word? =
                if (x == null) null else nums.minByOrNull { abs(it.cx - x) }

            val qw = nearest(xQty); val pw = nearest(xPrice); val aw = nearest(xAmt)
            val qty = qw?.let { OcrLayout.asDecimal(it.text) }?.takeIf { it in QTY_MIN..QTY_MAX }
            val price = pw?.let { OcrLayout.asDecimal(it.text) }?.takeIf { it in PRICE_MIN..PRICE_MAX }
            val amt = aw?.let { OcrLayout.asMoney(it.text) }?.takeIf { it in AMT_MIN..AMT_MAX }
            if (qty != null || price != null || amt != null) return Cols(qty, price, amt)
        }
        return Cols(null, null, null)
    }

    // ── [실기기 덤프로 확인] 라벨은 못 믿는다. 숫자의 '관계'로 찾는다 ──────────────
    //
    //  실제 폰에서 이 영수증을 읽힌 결과(2026-08-27 덤프):
    //     15: [수단공세@346,1491]   ← '수/단/공/세' 라벨 네 글자가 세로로 한 덩어리
    //     16: [고가액:@354,1513]    ← 공급가액
    //     20: [애@532,1705]         ← 금액
    //  즉 '수량'·'금액'·'합계금액' 이라는 글자가 **영수증 어디에도 없다.**
    //  띄어 인쇄된 한글이 흐리면 ML Kit 이 왼쪽 라벨 열을 통째로 뭉갠다.
    //  라벨을 찾는 방식은 이 영수증에서 원리적으로 불가능하다.
    //
    //  그런데 숫자는 살아 있었다: 40,428 / 4,042 / 1162.0 / 38.2?0
    //  주유 영수증에는 반드시 이 관계가 성립한다.
    //     세액 = 공급가액 ÷ 10        (부가세 10%)
    //     금액 = 공급가액 + 세액
    //     수량 = 금액 ÷ 단가
    //  숫자를 전부 모아 이 관계를 만족하는 조합만 고르면 라벨이 없어도 정답이 나온다.
    //  승인번호(464043)·카드번호·전화번호는 이 관계를 만족할 수 없으므로 저절로 걸러진다.
    //  ※ 표에 찍히던 40,428 과 464,043 이 각각 공급가액과 승인번호였다.

    private data class Tok(val v: Double, val dec: Boolean)

    /** 행을 이어붙여 숫자 덩어리를 전부 뽑는다. '4,' + '042' 처럼 쪼개진 것도 붙여서 읽는다. */
    private fun tokens(rows: List<List<OcrLayout.Word>>): List<Tok> {
        val out = ArrayList<Tok>()
        val seen = HashSet<String>()
        for (r in rows) {
            val t = r.joinToString("") { it.text }.replace(" ", "")
            for (m in Regex("[0-9][0-9,.]{1,13}").findAll(t)) {
                val raw = m.value.trimEnd('.', ',')
                if (raw.length < 2) continue
                if (!seen.add(raw)) continue
                val dec = Regex("\\.\\d{1,3}$").containsMatchIn(raw)
                val v = raw.replace(",", "").toDoubleOrNull() ?: continue
                out.add(Tok(v, dec))
            }
        }
        return out
    }

    private data class Money3(val supply: Int, val tax: Int, val amount: Int)

    /**
     * 부가세 관계(세액 = 공급가액/10)를 만족하는 짝을 찾는다.
     * 여러 개면 금액이 가장 큰 것 — 영수증에서 가장 의미 있는 금액 조합이다.
     */
    private fun byTaxIdentity(toks: List<Tok>): Money3? {
        val ints = toks.filter { !it.dec }.map { it.v.toInt() }.distinct()
        val strs = toks.map { it.v.toInt().toString() }

        /**
         * 뒷받침 근거 점수. 우연히 t ≈ s/10 인 숫자쌍(승인번호 등)을 걸러내기 위한 것.
         *  2점 = 계산한 금액이 영수증에도 그대로 있다
         *  1점 = 앞자리만 잘린 채로 있다(44,470 을 4,470 으로 읽는 일이 잦다 — 금액 줄은 글자가 커서 잘 잘린다)
         *  0점 = 근거 없음
         */
        fun corroboration(a: Int): Int {
            val s = a.toString()
            if (strs.any { it == s }) return 2
            if (s.length >= 3 && strs.any { it.length in (s.length - 2) until s.length && s.endsWith(it) }) return 1
            return 0
        }

        var best: Money3? = null
        var bestScore = -1
        for (s in ints) {
            if (s < 900 || s > AMT_MAX) continue
            for (t in ints) {
                if (t == s || t < 90) continue
                // 반올림·절사 편차 1~2원까지 인정한다.
                if (abs(s / 10.0 - t) > 2.0) continue
                val a = s + t
                if (a !in AMT_MIN..AMT_MAX) continue
                val sc = corroboration(a)
                // 근거 점수 우선, 같으면 큰 금액 우선.
                if (sc > bestScore || (sc == bestScore && (best == null || a > best!!.amount))) {
                    best = Money3(s, t, a); bestScore = sc
                }
            }
        }
        return best
    }

    /** 영수증 어디서든 날짜를 찾는다. 날짜 라벨이 있는 행을 먼저 본다. */
    private fun findDate(rows: List<List<OcrLayout.Word>>): Triple<Int, Int, Int>? {
        fun pick(t: String): Triple<Int, Int, Int>? {
            RE_DATE.find(t)?.let { m ->
                val y = m.groupValues[1].toInt()
                val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
                if (mo in 1..12 && d in 1..31) return Triple(y, mo, d)
            }
            RE_DATE2.find(t)?.let { m ->
                val y = 2000 + m.groupValues[1].toInt()
                val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
                if (mo in 1..12 && d in 1..31 && y in 2020..2099) return Triple(y, mo, d)
            }
            return null
        }
        // 1순위: 날짜 라벨이 붙은 행
        for (row in rows) {
            val t = rowText(row)
            if (L_DATE.none { t.contains(it) }) continue
            pick(t)?.let { return it }
        }
        // 2순위: 아무 행
        for (row in rows) pick(rowText(row))?.let { return it }
        return null
    }

    /**
     * 주 진입점. 가스·전기 영수증이 아니거나 금액을 확신 못 하면 null 을 돌려준다.
     * (null 이면 호출자는 기존 경로로 넘어간다 — 되던 게 안 되는 회귀를 막는다.)
     */
    fun parse(v: Text): Result? {
        val ws = OcrLayout.words(v)
        if (ws.size < 4) return null

        // [기울기 대응] 행을 두 방식으로 만들어 **둘 다** 뒤진다.
        //  ① ML Kit 이 묶어준 줄 — 각도·휨 보정이 이미 들어간 결과. 이게 1순위다.
        //  ② 기울기를 측정해 뺀 좌표로 다시 묶은 것 — ①이 줄을 쪼갠 경우의 보험.
        //  라벨 우선순위 순서로 찾으므로, 같은 라벨이면 ①의 결과가 먼저 잡힌다.
        val lineRows = OcrLayout.lineRows(v)
        val k = OcrLayout.slope(lineRows)
        val rows = lineRows + OcrLayout.rowsDeskewed(ws, k)
        if (rows.isEmpty()) return null

        val date = findDate(rows)

        // ── 1) 라벨형(남서울가스) 먼저 ────────────────────────────────
        var qty = findDecimal(rows, L_QTY, QTY_MIN, QTY_MAX)?.first
        var price = findDecimal(rows, L_PRICE, PRICE_MIN, PRICE_MAX)?.first
        var amount = findMoney(rows, L_TOTAL)?.first
        val how = StringBuilder()
        if (qty != null || price != null || amount != null) how.append("라벨")

        // ── 2) 표형(해양가스) 보강 ───────────────────────────────────
        if (qty == null || price == null || amount == null) {
            val c = byColumns(rows)
            if (qty == null && c.qty != null) { qty = c.qty; how.append("+열") }
            if (price == null && c.price != null) { price = c.price; if (!how.contains("열")) how.append("+열") }
            if (amount == null && c.amount != null) { amount = c.amount; if (!how.contains("열")) how.append("+열") }
        }

        // ── 3) 공급가액 + 세액 = 금액 (합계 라벨을 못 읽었을 때) ────────
        var supply = findMoney(rows, L_SUPPLY)?.first
        var tax = findMoney(rows, L_TAX)?.first

        // ── 3-b) 라벨이 다 뭉개졌으면 숫자의 관계로 찾는다 ─────────────────
        //  이게 실기기에서 실제로 통하는 유일한 경로였다(위 주석 참고).
        if (supply == null || tax == null || amount == null) {
            val toks = tokens(rows)
            byTaxIdentity(toks)?.let { m ->
                if (supply == null) supply = m.supply
                if (tax == null) tax = m.tax
                if (amount == null) { amount = m.amount; how.append("+부가세관계") }
                else if (abs(amount!! - m.amount) > 2) {
                    // 라벨로 읽은 금액이 관계식과 어긋난다 → 관계식을 믿는다.
                    //  (금액 줄은 글자가 커서 앞자리가 잘려 나가는 일이 잦다: 44,470 → 4,470)
                    how.append("+부가세관계로교정(").append(amount).append("→").append(m.amount).append(")")
                    amount = m.amount
                }
            }
            // 단가: 라벨이 없으면 소수 형태의 숫자 중 단가 범위에 드는 것.
            if (price == null) {
                price = toks.filter { it.dec && it.v in PRICE_MIN..PRICE_MAX }
                    .minByOrNull { abs(it.v - 1200.0) }?.v          // LPG 대역에 가까운 것 우선
                    ?: toks.filter { !it.dec && it.v in PRICE_MIN..PRICE_MAX }
                        .minByOrNull { abs(it.v - 1200.0) }?.v
                if (price != null) how.append("+단가추정")
            }
        }
        if (amount == null && supply != null && tax != null) {
            amount = supply + tax
            how.append("+공급가액합")
        }

        // [대표 지시] 금액을 못 읽었어도 **읽은 것만이라도 돌려준다.**
        //  예전엔 여기서 null 을 반환해 장부 파서로 넘어갔고, 그 장부 파서가
        //  승인번호·가맹점번호 같은 숫자를 골라 40428 / 464043 같은 값을 만들었다.
        //  차라리 빈 칸을 보여주고 기사가 채우는 게 낫다 — 틀린 숫자를 조용히 넣는 것보다.
        if (amount != null && amount !in AMT_MIN..AMT_MAX) amount = null
        if (amount == null && qty == null && price == null && supply == null && tax == null && date == null) return null

        // ── 4) 검산 ─────────────────────────────────────────────────
        var verified = false
        if (amount != null && qty != null && price != null) {
            val expect = qty * price
            if (abs(expect - amount) <= maxOf(2.0, amount * 0.02)) { verified = true; how.append("·검산OK") }
            else how.append("·검산불일치")
        }
        if (!verified && amount != null && supply != null && tax != null && abs((supply + tax) - amount) <= 2) {
            verified = true; how.append("·세액검산OK")
        }

        // ── 5) 빈 칸을 산수로 메운다 (추측이 아니라 계산이다) ─────────────
        if (amount == null && supply != null && tax != null) { amount = supply + tax; how.append("·금액=공급+세액") }
        if (amount == null && qty != null && price != null) {
            val a = Math.round(qty * price).toInt()
            if (a in AMT_MIN..AMT_MAX) { amount = a; how.append("·금액=수량×단가") }
        }
        if (qty == null && price != null && price > 0 && amount != null) {
            val d = amount / price
            if (d in QTY_MIN..QTY_MAX) { qty = Math.round(d * 1000.0) / 1000.0; how.append("·수량역산") }
        }

        return Result(
            year = date?.first, month = date?.second, day = date?.third,
            amount = amount, liters = qty, unitPrice = price,
            verified = verified, how = how.toString().trimStart('+'),
            supply = supply, tax = tax
        )
    }
}
