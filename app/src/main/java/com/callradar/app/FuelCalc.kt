package com.callradar.app

/**
 * 연료(LPG·전기) 입력칸의 **산수 자동채움**.
 *
 * [2026-08-29 대표 결정] "OCR 기능이 영 좋지 않아. 개발·테스트 시간을 쏟는 건 비효율적이다."
 *  → 연료 입력은 **수동 4칸**이 기본 동선이 된다. 사진 판독은 과거 장부 가져오기에만 남긴다.
 *
 * 그래서 이 파일이 필요하다. 수동이라고 네 칸을 다 치게 하면 안 된다.
 * 부가세와 단가는 **항등식**이라 두 칸만 알면 나머지가 나온다. 가게 양식과 무관하다.
 *
 *      세액 = 공급가액 / 10        금액 = 공급가액 + 세액        금액 = 수량 × 단가
 *
 * 실제 영수증(남서울가스 2026-08-16)으로 확인:
 *      세액 5,491  →  공급가액 54,910  →  금액 60,401  →  수량 60,401/1,162 = 51.98L
 *  영수증 인쇄값과 전부 일치한다.
 *
 * **채워 넣지 않고 제안만 한다.** 기사가 눈으로 보고 누를 때만 들어간다.
 * 빈 칸에만 나오고 직접 적은 값은 절대 건드리지 않는다.
 *
 * ★ 정관: 같은 지표의 가드가 파일마다 다르면 이미 사고가 난 것이다.
 *   지출추가(RecordsScreen)와 영수증카드(ImageImportActivity)가 **이 파일 하나만** 쓴다.
 */
object FuelCalc {

    // 상식 범위 — 벗어나면 오타로 본다. (승인번호·사업자번호 오입력 차단)
    const val AMT_MIN = 1_000
    const val AMT_MAX = 1_000_000        // 택시 1회 충전이 100만원일 수 없다
    const val QTY_MIN = 0.5
    const val QTY_MAX = 300.0            // LPG 탱크·전기차 배터리 상한 여유
    const val PRICE_MIN = 100.0          // 전기 급속 ~300원/kWh
    const val PRICE_MAX = 5_000.0

    /** 네 칸(+검산 두 칸). 문자열로 다루는 이유는 "빈 칸"과 "0"을 구분해야 하기 때문이다. */
    data class Fields(
        val qty: String = "",       // 수량 (L / kWh)
        val price: String = "",     // 단가
        val amount: String = "",    // 금액 ← 기록에 들어가는 값
        val supply: String = "",    // 공급가액 (검산용)
        val tax: String = ""        // 세액 (검산용)
    )

    /** 40.510 → "40.51", 40.0 → "40". 군더더기 0 을 안 보이게 한다. */
    fun trim(d: Double): String {
        val s = String.format("%.3f", d).trimEnd('0').trimEnd('.')
        return if (s.isEmpty() || s == "-") "0" else s
    }

    /**
     * 빈 칸에 넣을 **제안값**을 돌려준다. 채워진 칸은 빈 문자열로 온다(= 제안 없음).
     * 금액은 절대 계산값으로 덮지 않는다 — 영수증에 인쇄된 실결제금액이 항상 이긴다.
     */
    fun suggest(f: Fields): Fields {
        var q = f.qty.toDoubleOrNull() ?: 0.0
        var p = f.price.toDoubleOrNull() ?: 0.0
        var sup = f.supply.toIntOrNull() ?: 0
        var tax = f.tax.toIntOrNull() ?: 0
        var amt = f.amount.toIntOrNull() ?: 0
        if (q !in QTY_MIN..QTY_MAX) q = 0.0
        if (p !in PRICE_MIN..PRICE_MAX) p = 0.0
        if (sup < 0) sup = 0
        if (tax < 0) tax = 0

        // 한 칸이 채워지면 다음 칸이 계산된다 — 연쇄를 위해 두 바퀴.
        repeat(2) {
            if (amt == 0 && sup > 0 && tax > 0) amt = sup + tax
            if (amt == 0 && sup > 0) amt = sup + Math.round(sup / 10.0).toInt()
            if (amt == 0 && tax > 0) amt = tax * 11
            if (amt == 0 && q > 0 && p > 0) amt = Math.round(q * p).toInt()
            if (amt !in AMT_MIN..AMT_MAX) { amt = 0; return@repeat }
            if (sup == 0) sup = Math.round(amt / 1.1).toInt()
            if (tax == 0) tax = amt - Math.round(amt / 1.1).toInt()
            if (q == 0.0 && p > 0) { val d = amt / p; if (d in QTY_MIN..QTY_MAX) q = Math.round(d * 1000.0) / 1000.0 }
            if (p == 0.0 && q > 0) { val d = amt / q; if (d in PRICE_MIN..PRICE_MAX) p = Math.round(d * 100.0) / 100.0 }
        }

        return Fields(
            qty = if (f.qty.isBlank() && q > 0) trim(q) else "",
            price = if (f.price.isBlank() && p > 0) trim(p) else "",
            amount = if (f.amount.isBlank() && amt in AMT_MIN..AMT_MAX) amt.toString() else "",
            supply = if (f.supply.isBlank() && sup > 0) sup.toString() else "",
            tax = if (f.tax.isBlank() && tax > 0) tax.toString() else ""
        )
    }

    /**
     * 검산 문구. 기계가 맞았는지 **기사가 눈으로** 확인할 근거를 준다.
     * 값이 모자라면 빈 문자열 — 정관 "숫자를 못 만들면 '—' 를 쓰지 않는다".
     */
    fun checkLine(f: Fields): String {
        val q = f.qty.toDoubleOrNull() ?: 0.0
        val p = f.price.toDoubleOrNull() ?: 0.0
        val a = f.amount.toIntOrNull() ?: 0
        if (a <= 0 || q <= 0 || p <= 0) return ""
        val calc = Math.round(q * p).toInt()
        // 단가는 소수 둘째자리까지라 반올림 편차가 난다. 2% 또는 2원까지 인정.
        val ok = kotlin.math.abs(calc - a) <= maxOf(2, a / 50)
        return (if (ok) "✅ " else "⚠️ ") + "수량×단가 = ${String.format("%,d", calc)}원"
    }

    /** 월 누적 할인은 지출 한 줄(음수)로 저장한다. 이 카테고리만 음수를 허용한다. */
    const val CAT_DISCOUNT = "연료할인"
}
