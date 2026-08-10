package com.callradar.app

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/**
 * [명세서 스펙 v31+] 급여명세서 "전 항목" OCR 파서.
 *
 * 기존 MoreScreen.parsePayslip 은 5개 고정칸(기본급·4대보험·조합비·기타·실수령)만 뽑았다.
 * 여기서는 명세서의 모든 {라벨, 금액} 쌍을 좌표 행매칭으로 추출하고,
 * 회사명·근무형태·연월·차인지급액도 뽑아 서버 /api/payslip 스키마에 그대로 올린다.
 *
 * 역산/분류 로직은 server/payslip.js 의 LABEL_GROUPS·classify·reverseCalc 를 1:1 포팅한 것.
 * (온디바이스에서 즉시 역산 표시 + 오프라인 동작. 서버 업로드는 저장·재검증·라벨학습용.)
 * 핵심 검증식: 차인지급액 = 발생금액 − 공제계 + 부가세경감액
 */
class PayslipOcr {

    companion object {
        private const val TAG = "CallRadar"
        private const val ROW_TOL = 30   // 같은 '행'으로 볼 centerY 허용 오차(px). 기존 parsePayslip 과 동일.

        // ── server/payslip.js LABEL_GROUPS 1:1 포팅 ──
        val EARNING = listOf("총입금", "기본급", "승무수당", "근속수당", "야간수당", "장려수당", "상여금", "인정금", "발생금액", "발생액")
        val DEDUCTION = listOf("소득세", "주민세", "국민연금", "건강요양", "건강보험", "고용보험", "경조비", "노조비", "조합비", "복지비", "전별금", "가불금", "전월미입", "과태료", "성과급세금", "연말정산", "기타공제", "기타1")
        val VAT_RELIEF = listOf("성과급부가세", "선지급부가세", "부가세경감", "부가세경감액")

        fun norm(s: String?): String = (s ?: "").replace(Regex("\\s+"), "").trim()

        /** server classify(): earning | deduction | vat_relief | unknown */
        fun classify(label: String): String {
            val l = norm(label)
            // vat_relief → deduction → earning 순서(server와 동일). 포함관계 매칭.
            for (k in VAT_RELIEF) if (norm(k) == l || l.contains(norm(k))) return "vat_relief"
            for (k in DEDUCTION) if (norm(k) == l || l.contains(norm(k))) return "deduction"
            for (k in EARNING) if (norm(k) == l || l.contains(norm(k))) return "earning"
            return "unknown"
        }
    }

    data class PayItem(val label: String, val amount: Int, val group: String)

    data class Result(
        val company: String,
        val workType: String,       // 일차 | 주간 | 야간 | 격일 | ""
        val yearMonth: String,      // "YYYY-MM" or ""
        val items: List<PayItem>,   // 분류된 전 항목(총입금은 group=info)
        val takeHome: Int,          // 명세서에 인쇄된 차인지급액(실수령) — 있으면
        val earning: Int,           // 발생금액(소계 우선)
        val deduction: Int,         // 공제계(소계 우선)
        val vatRelief: Int,         // 부가세경감액 합
        val computedTakeHome: Int,  // 역산 = 발생 − 공제 + 부가세경감
        val matched: Boolean,       // 역산 == 인쇄 차인지급액 (±1)
        val uploadItems: List<Pair<String, Int>>,  // 서버 업로드용 원본 전 쌍(소계·차인지급 포함, 서버가 재분류)
        val rawText: String
    )

    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    fun process(bitmap: Bitmap, onResult: (Result?) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { vt ->
                val lines = ArrayList<Triple<String, Int, Int>>()
                for (b in vt.textBlocks) for (l in b.lines) {
                    val r = l.boundingBox
                    if (r != null) lines.add(Triple(l.text, r.centerX(), r.centerY()))
                }
                Log.d(TAG, "명세서 OCR:\n${vt.text}")
                onResult(parse(vt.text, lines))
            }
            .addOnFailureListener { e -> Log.e(TAG, "명세서 OCR 실패: ${e.message}"); onResult(null) }
    }

    private val amtRe = Regex("[0-9]{1,3}(?:,[0-9]{3})+|\\d{4,}")
    private fun toInt(s: String) = s.replace(",", "").toIntOrNull() ?: 0
    private fun isAmountOnly(t: String) = t.isNotBlank() && t.replace(Regex("[0-9,\\s￦₩원]"), "").isEmpty() && amtRe.containsMatchIn(t)
    private fun hasHangul(t: String) = Regex("[가-힣]").containsMatchIn(t)

    private data class Amt(val v: Int, val x: Int, val y: Int)
    private data class Lab(val label: String, val x: Int, val y: Int)

    /** 라인 텍스트에서 라벨 부분만 정리(선행 번호·특수문자 제거) */
    private fun cleanLabel(t: String): String =
        t.replace(Regex("[0-9,￦₩]+"), "").replace(Regex("[|:：·\\-()\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * 전 항목 추출: 각 금액 토큰을 같은 행에서 '왼쪽의 가장 가까운 라벨'과 짝짓는다.
     * 명세서는 보통 지급|공제 2단 표라, 한 행에 좌·우 두 쌍이 있어도 각 금액이 자기 왼쪽 라벨을 잡는다.
     * ML Kit이 라벨+금액을 한 줄로 붙여 읽은 경우(인라인)도 별도로 보강.
     */
    fun parse(rawText: String, lines: List<Triple<String, Int, Int>>): Result {
        val pairs = LinkedHashMap<String, Int>()   // key: "label|amount" 로 중복 제거하며 순서 보존
        fun add(label: String, amount: Int) {
            val lb = label.trim()
            if (lb.isEmpty() || amount == 0) return
            if (!hasHangul(lb)) return
            pairs.putIfAbsent("$lb|$amount", amount)
        }

        if (lines.isNotEmpty()) {
            val amounts = lines.filter { isAmountOnly(it.first) }
                .mapNotNull { (t, x, y) -> amtRe.find(t)?.let { Amt(toInt(it.value), x, y) } }
                .filter { it.v in 100..99999999 }
            val labels = lines.filter { hasHangul(it.first) && !isAmountOnly(it.first) }
                .map { (t, x, y) -> Lab(cleanLabel(t), x, y) }
                .filter { it.label.isNotEmpty() }

            for (a in amounts) {
                val sameRow = labels.filter { kotlin.math.abs(it.y - a.y) <= ROW_TOL }
                val leftLab = sameRow.filter { it.x < a.x }.minByOrNull { a.x - it.x }
                val lab = leftLab ?: sameRow.minByOrNull { kotlin.math.abs(it.x - a.x) }
                if (lab != null) add(lab.label, a.v)
            }
        }

        // 인라인 보강: "기본급 1,234,000" 처럼 한 줄에 라벨+금액이 같이 온 경우
        for (raw in rawText.split(Regex("\\r?\\n"))) {
            val line = raw.trim()
            if (!hasHangul(line)) continue
            val m = amtRe.find(line) ?: continue
            val labelPart = line.substring(0, m.range.first)
            val lab = cleanLabel(labelPart)
            if (lab.isNotEmpty()) add(lab, toInt(m.value))
        }

        // ── server reverseCalc() 1:1 포팅 ──
        var earning = 0; var deduction = 0; var vat = 0
        var earningTotalLabel: Int? = null; var deductionTotalLabel: Int? = null
        var scannedTakeHome = 0
        val detail = ArrayList<PayItem>()
        for ((key, amt) in pairs) {
            val label = norm(key.substringBeforeLast("|"))
            when {
                label == "발생금액" || label == "발생액" -> { earningTotalLabel = amt }
                label == "공제계" || label == "공제 계" -> { deductionTotalLabel = amt }
                label == "차인지급액" || label == "차인지급" || label.contains("차인지급") -> { scannedTakeHome = amt }
                label == "총입금" -> detail.add(PayItem(label, amt, "info"))
                else -> when (classify(label)) {
                    "earning" -> { earning += amt; detail.add(PayItem(label, amt, "earning")) }
                    "deduction" -> { deduction += amt; detail.add(PayItem(label, amt, "deduction")) }
                    "vat_relief" -> { vat += amt; detail.add(PayItem(label, amt, "vat_relief")) }
                    else -> detail.add(PayItem(label, amt, "unknown"))
                }
            }
        }
        val earnUsed = earningTotalLabel ?: earning
        val dedUsed = deductionTotalLabel ?: deduction
        val computed = earnUsed - dedUsed + vat
        val matched = scannedTakeHome != 0 && kotlin.math.abs(computed - scannedTakeHome) <= 1

        return Result(
            company = extractCompany(rawText),
            workType = extractWorkType(rawText),
            yearMonth = extractYearMonth(rawText),
            items = detail,
            takeHome = scannedTakeHome,
            earning = earnUsed,
            deduction = dedUsed,
            vatRelief = vat,
            computedTakeHome = computed,
            matched = matched,
            uploadItems = pairs.entries.map { Pair(it.key.substringBeforeLast("|"), it.value) },
            rawText = rawText
        )
    }

    private fun extractCompany(text: String): String {
        val re = Regex("[가-힣A-Za-z]{2,10}(상운|운수|운송|택시|교통|모빌리티|산업|기업)")
        return re.find(text)?.value ?: ""
    }

    private fun extractWorkType(text: String): String {
        val t = norm(text)
        return when {
            t.contains("일차") -> "일차"
            t.contains("격일") -> "격일"
            t.contains("주간") -> "주간"
            t.contains("야간") -> "야간"
            t.contains("오전") -> "오전"
            t.contains("오후") -> "오후"
            else -> ""
        }
    }

    /** 명세서 귀속 연월 → "YYYY-MM" */
    private fun extractYearMonth(text: String): String {
        val nowYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        fun ok(y: Int, m: Int): String? {
            if (m !in 1..12) return null
            val yy = if (y < 100) 2000 + y else y
            if (yy < 2020 || yy > nowYear + 1) return null
            return "%04d-%02d".format(yy, m)
        }
        Regex("(20\\d{2}|\\d{2})\\s*년\\s*(1[0-2]|0?[1-9])\\s*월").find(text)?.let {
            ok(it.groupValues[1].toInt(), it.groupValues[2].toInt())?.let { s -> return s }
        }
        Regex("(20\\d{2})[.\\-/](1[0-2]|0[1-9])(?![0-9])").find(text)?.let {
            ok(it.groupValues[1].toInt(), it.groupValues[2].toInt())?.let { s -> return s }
        }
        return ""
    }
}
