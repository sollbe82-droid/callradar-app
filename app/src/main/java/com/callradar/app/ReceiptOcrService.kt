package com.callradar.app

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class ReceiptOcrService {

    companion object {
        private const val TAG = "CallRadar"
    }

    private val recognizer = TextRecognition.getClient(
        KoreanTextRecognizerOptions.Builder().build()
    )

    enum class ReceiptType {
        KAKAO_TAXI,  // 카카오택시 정산 영수증 (킬러 기능)
        TMONEYGO,
        LPG,
        HIPASS,
        FOOD,
        REPAIR,
        WASH,
        PARKING,
        UNKNOWN
    }

    data class ReceiptResult(
        val type: ReceiptType,
        val typeName: String,
        val amount: Int,
        val time: String,
        val date: String,
        val memo: String,
        val rawText: String,
        val liters: Float = 0f,
        val pricePerLiter: Int = 0,
        val distance: Float = 0f,
        val duration: Int = 0,
        // 카카오택시 정산 전용
        val callCount: Int = 0,
        val callDetails: List<CallDetail> = emptyList()
    )

    data class CallDetail(
        val time: String,
        val callId: String,
        val amount: Int
    )

    fun processReceipt(bitmap: Bitmap, onResult: (ReceiptResult?) -> Unit) {
        // [v96] 전처리 후 인식 — 감열지 영수증은 대비가 낮아 원본 그대로는 획이 뭉갠다.
        val prepped = OcrPrep.prepare(bitmap)
        val inputImage = InputImage.fromBitmap(prepped, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                Log.d(TAG, "OCR 텍스트:\n$text")
                var result = parseReceipt(text)
                // [v96] 좌표 기반 보정 — 정규식이 놓친 금액을 '라벨 옆 숫자'로 다시 찾는다.
                //  기존 파서는 납작한 문자열만 봐서 표에서 라벨과 값이 떨어지면 못 잡았다.
                //  ML Kit 이 주는 좌표를 쓰면 "합계 오른쪽 칸"을 그대로 집을 수 있다.
                result = refineWithLayout(result, visionText)
                onResult(result)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR 실패: ${e.message}")
                onResult(null)
            }
    }

    /**
     * [v96] 좌표 기반 보정.
     *
     * 원칙: **정규식이 이미 제대로 잡았으면 건드리지 않는다.** 값이 비었거나(0)
     * 명백히 이상할 때만 좌표로 다시 찾는다. 잘 되던 영수증이 퇴행하지 않게 하는 게 우선이다.
     *
     * 왜 되나: 영수증은 표라서 '합계'와 금액이 문자열로는 멀리 떨어져도
     * 종이 위에서는 같은 줄 오른쪽에 있다. 그 배치를 그대로 읽는다.
     */
    private fun refineWithLayout(r: ReceiptResult?, v: com.google.mlkit.vision.text.Text): ReceiptResult? {
        return try {
            val ws = OcrLayout.words(v)
            if (ws.isEmpty()) return r
            // 금액: 결제 총액을 가리키는 라벨을 넓게 잡는다(영수증마다 표기가 제각각).
            val byLabel = OcrLayout.money(
                ws, "합계", "총액", "총합계", "결제금액", "승인금액", "받을금액", "청구금액", "판매금액", "금액"
            )
            val amount = when {
                r == null || r.amount <= 0 -> byLabel ?: OcrLayout.biggestMoney(ws)
                byLabel != null && byLabel != r.amount && r.amount < 100 -> byLabel  // 한두 자리는 오독일 확률이 높다
                else -> r.amount
            } ?: 0
            // 리터: LPG 영수증에서 '수량/충전량' 옆의 소수.
            val liters = if (r != null && r.liters > 0f) r.liters
                         else (OcrLayout.decimal(ws, "수량", "충전량", "리터")?.toFloat() ?: 0f)
            val ppl = if (r != null && r.pricePerLiter > 0) r.pricePerLiter
                      else (OcrLayout.money(ws, "단가", "리터당") ?: 0)

            if (r == null) {
                if (amount <= 0) null
                else ReceiptResult(
                    type = ReceiptType.UNKNOWN, typeName = "영수증", amount = amount,
                    time = "", date = "", memo = "", rawText = v.text,
                    liters = liters, pricePerLiter = ppl
                )
            } else r.copy(amount = amount, liters = liters, pricePerLiter = ppl)
        } catch (e: Exception) { r }
    }

    fun parseReceipt(text: String): ReceiptResult? {
        if (text.isEmpty()) return null
        val type = detectReceiptType(text)
        return when (type) {
            ReceiptType.KAKAO_TAXI -> parseKakaoTaxiReceipt(text)
            ReceiptType.TMONEYGO  -> parseTmoneyReceipt(text)
            ReceiptType.LPG       -> parseLpgReceipt(text)
            ReceiptType.HIPASS    -> parseHipassReceipt(text)
            ReceiptType.FOOD      -> parseFoodReceipt(text)
            ReceiptType.REPAIR    -> parseRepairReceipt(text)
            ReceiptType.WASH      -> parseWashReceipt(text)
            ReceiptType.PARKING   -> parseParkingReceipt(text)
            ReceiptType.UNKNOWN   -> parseUnknownReceipt(text)
        }
    }

    private fun detectReceiptType(text: String): ReceiptType {
        return when {
            text.contains("카카오택시") || text.contains("카카오T") && text.contains("콜ID") -> ReceiptType.KAKAO_TAXI
            text.contains("티머니") || text.contains("승차") || text.contains("하차") && text.contains("운행") -> ReceiptType.TMONEYGO
            text.contains("LPG") || text.contains("lpg") || text.contains("부탄") || text.contains("프로판") || text.contains("남서울가스") || text.contains("가스(주)") ||
                ((text.contains("수량") || text.contains("수 량")) && (text.contains("단가") || text.contains("단 가"))) ||
                (text.contains("충전") && (text.contains("리터") || text.contains("L"))) -> ReceiptType.LPG
            text.contains("하이패스") || text.contains("통행료") || text.contains("고속도로") -> ReceiptType.HIPASS
            text.contains("수리") || text.contains("정비") || text.contains("공업사") -> ReceiptType.REPAIR
            text.contains("세차") || text.contains("WASH") -> ReceiptType.WASH
            text.contains("주차") || text.contains("PARKING") -> ReceiptType.PARKING
            text.contains("식당") || text.contains("편의점") || text.contains("GS25") || text.contains("CU") -> ReceiptType.FOOD
            else -> ReceiptType.UNKNOWN
        }
    }

    /**
     * 카카오택시 정산 영수증 파싱
     * 총합계, 카카오택시 수입, 콜 건수, 콜별 금액 추출
     */
    private fun parseKakaoTaxiReceipt(text: String): ReceiptResult {
        val lines = text.lines()

        // 총합계 추출
        val totalAmount = extractKakaoTotal(text)

        // 카카오택시 수입 추출
        val kakaoAmount = extractKakaoIncome(text)

        // 날짜 추출
        val date = extractDate(text)
        val time = extractTime(text)

        // 콜 건수 및 상세 추출
        val callDetails = extractCallDetails(text)

        return ReceiptResult(
            type = ReceiptType.KAKAO_TAXI,
            typeName = "카카오택시 정산",
            amount = totalAmount,
            time = time,
            date = date,
            memo = "카카오택시 ${callDetails.size}콜 | 카카오수입 ${String.format("%,d", kakaoAmount)}원",
            rawText = text,
            callCount = callDetails.size,
            callDetails = callDetails
        )
    }

    private fun extractKakaoTotal(text: String): Int {
        val patterns = listOf(
            Regex("총합계\\s*[：:]?\\s*([0-9,]+)"),
            Regex("합\\s*계\\s*[：:]?\\s*([0-9,]+)"),
        )
        for (pattern in patterns) {
            val match = pattern.find(text)?.groupValues?.get(1)
            if (!match.isNullOrEmpty()) return match.replace(",", "").toIntOrNull() ?: 0
        }
        return extractAmount(text)
    }

    private fun extractKakaoIncome(text: String): Int {
        val pattern = Regex("카카오택시\\s*[：:]?\\s*([0-9,]+)")
        return pattern.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0
    }

    private fun extractCallDetails(text: String): List<CallDetail> {
        val details = mutableListOf<CallDetail>()
        // 패턴: 06/11 08:53  0071146554  13,400 원
        val pattern = Regex("(\\d{2}/\\d{2}\\s+\\d{2}:\\d{2})\\s+([0-9]{10,})\\s+([0-9,]+)\\s*원")
        pattern.findAll(text).forEach { match ->
            val time = match.groupValues[1].trim()
            val callId = match.groupValues[2].trim()
            val amount = match.groupValues[3].replace(",", "").toIntOrNull() ?: 0
            if (amount > 0) details.add(CallDetail(time, callId, amount))
        }
        return details
    }

    private fun parseTmoneyReceipt(text: String): ReceiptResult {
        return ReceiptResult(
            type = ReceiptType.TMONEYGO,
            typeName = "운행정산",
            amount = extractAmount(text),
            time = extractTime(text),
            date = extractDate(text),
            memo = "티머니고 운행",
            rawText = text
        )
    }

    private fun parseLpgReceipt(text: String): ReceiptResult {
        // [v41] 남서울가스 등 LPG 매출전표 학습: 리터(소수3)·단가(소수2) 자릿수로 구분,
        //  금액은 '리터×단가' 교차검증으로 확정(각도/측면 사진에서 '원'이 뭉개져도 정확).
        val liters = extractLpgLiters(text)
        val unit = extractLpgUnitPrice(text)
        val amount = extractLpgAmount(text, liters, unit)
        return ReceiptResult(
            type = ReceiptType.LPG,
            typeName = "충전비",
            amount = amount,
            time = extractTime(text),
            date = extractDate(text),
            memo = extractStationName(text),
            rawText = text,
            liters = liters,
            pricePerLiter = unit
        )
    }

    // [v41] LPG 충전량(L) — 수량 라벨 우선, 소수 3자리(NN.NNN)로 단가(소수2)와 구분, 5~200L 범위.
    private fun extractLpgLiters(text: String): Float {
        Regex("수\\s*량[^0-9]{0,8}([0-9]{1,3}\\.[0-9]{1,3})").find(text)?.groupValues?.get(1)?.toFloatOrNull()?.let {
            if (it in 1f..300f) return it
        }
        // 소수3자리 + L(오인식 l·ℓ 포함), 범위
        for (m in Regex("(?<![0-9])([0-9]{1,3}\\.[0-9]{3})\\s*[LlℓΙ|]?").findAll(text)) {
            val v = m.groupValues[1].toFloatOrNull() ?: continue
            if (v in 5f..200f) return v
        }
        // 일반 소수(단가 제외 위해 200 미만)
        for (m in Regex("(?<![0-9])([0-9]{1,3}\\.[0-9]{1,3})(?![0-9])").findAll(text)) {
            val v = m.groupValues[1].toFloatOrNull() ?: continue
            if (v in 5f..200f) return v
        }
        return 0f
    }

    // [v42] LPG 단가(원/L) — 라벨 우선. 콤마 있는 "1,170.00"·콤마 없는 "1162.00" 모두 지원(역종별 양식), 300~5000원.
    private fun extractLpgUnitPrice(text: String): Int {
        Regex("단\\s*가[^0-9]{0,8}([0-9]{1,2},[0-9]{3}\\.[0-9]{2}|[0-9]{3,4}\\.[0-9]{2}|[0-9]{3,4})").find(text)?.groupValues?.get(1)?.replace(",", "")?.toFloatOrNull()?.let {
            if (it in 300f..5000f) return it.toInt()
        }
        // 콤마 단가 "1,170.00"
        for (m in Regex("(?<![0-9])([0-9]{1,2},[0-9]{3}\\.[0-9]{2})(?![0-9])").findAll(text)) {
            val v = m.groupValues[1].replace(",", "").toFloatOrNull() ?: continue
            if (v in 300f..5000f) return v.toInt()
        }
        // 콤마 없는 단가 "1162.00"
        for (m in Regex("(?<![0-9])([0-9]{3,4}\\.[0-9]{2})(?![0-9])").findAll(text)) {
            val v = m.groupValues[1].toFloatOrNull() ?: continue
            if (v in 300f..5000f) return v.toInt()
        }
        return 0
    }

    // [v43] LPG 금액(총액) — 공급가액/세액 줄 제외 + '리터×단가' 교차검증으로 확정.
    private fun extractLpgAmount(text: String, liters: Float, unitPrice: Int): Int {
        val expected = if (liters > 0f && unitPrice > 0) Math.round(liters * unitPrice) else 0
        // [v43 버그수정] 공급가액(부가세 전)만 잡히던 문제 → 공급가액 + 세액 = 총 금액(부가세 포함, 큰 금액)으로 확정.
        val supply = Regex("공\\s*급\\s*가\\s*액[^0-9]{0,8}([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{4,7})").find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        val tax = Regex("(?:부\\s*가\\s*세\\s*액|부\\s*가\\s*세|세\\s*액)[^0-9]{0,8}([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{3,7})").find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        if (supply != null && supply > 0 && tax != null && tax > 0) {
            val sum = supply + tax
            if (expected <= 0 || Math.abs(sum - expected) <= expected * 0.1) return sum   // 리터×단가와 10% 이내면 확정(총액)
        }
        val amounts = mutableListOf<Int>()
        for (line in text.lines()) {
            val clean = line.replace(" ", "")
            if (clean.contains("공급") || clean.contains("세액") || clean.contains("부가")) continue
            for (m in Regex("([0-9]{1,3}(?:,[0-9]{3})+)").findAll(line)) {
                m.groupValues[1].replace(",", "").toIntOrNull()?.let { if (it in 1000..2_000_000) amounts.add(it) }
            }
        }
        if (expected > 0) {
            val best = amounts.minByOrNull { Math.abs(it - expected) }
            if (best != null && Math.abs(best - expected) <= expected * 0.05) return best
            return expected   // 금액 줄 OCR 실패 시 계산값으로 확정
        }
        Regex("금\\s*액[^0-9]{0,8}([0-9]{1,3}(?:,[0-9]{3})+)").find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()?.let { return it }
        return amounts.maxOrNull() ?: extractAmount(text)
    }

    private fun parseHipassReceipt(text: String): ReceiptResult {
        return ReceiptResult(
            type = ReceiptType.HIPASS,
            typeName = "통행료",
            amount = extractAmount(text),
            time = extractTime(text),
            date = extractDate(text),
            memo = extractHighwayRoute(text),
            rawText = text
        )
    }

    private fun parseFoodReceipt(text: String): ReceiptResult {
        return ReceiptResult(
            type = ReceiptType.FOOD,
            typeName = "식비",
            amount = extractAmount(text),
            time = extractTime(text),
            date = extractDate(text),
            memo = extractStoreName(text),
            rawText = text
        )
    }

    private fun parseRepairReceipt(text: String): ReceiptResult {
        return ReceiptResult(
            type = ReceiptType.REPAIR,
            typeName = "차량정비",
            amount = extractAmount(text),
            time = extractTime(text),
            date = extractDate(text),
            memo = extractStoreName(text),
            rawText = text
        )
    }

    private fun parseWashReceipt(text: String): ReceiptResult {
        return ReceiptResult(
            type = ReceiptType.WASH,
            typeName = "세차비",
            amount = extractAmount(text),
            time = extractTime(text),
            date = extractDate(text),
            memo = "세차",
            rawText = text
        )
    }

    private fun parseParkingReceipt(text: String): ReceiptResult {
        return ReceiptResult(
            type = ReceiptType.PARKING,
            typeName = "주차비",
            amount = extractAmount(text),
            time = extractTime(text),
            date = extractDate(text),
            memo = "주차",
            rawText = text
        )
    }

    private fun parseUnknownReceipt(text: String): ReceiptResult? {
        val amount = extractAmount(text)
        if (amount == 0) return null
        return ReceiptResult(
            type = ReceiptType.UNKNOWN,
            typeName = "기타",
            amount = amount,
            time = extractTime(text),
            date = extractDate(text),
            memo = "확인필요",
            rawText = text
        )
    }

    private fun extractAmount(text: String): Int {
        val patterns = listOf(
            Regex("합\\s*계\\s*[：:]?\\s*[￦₩]?\\s*([0-9,]+)\\s*원"),
            Regex("결\\s*제\\s*금\\s*액\\s*[：:]?\\s*[￦₩]?\\s*([0-9,]+)\\s*원"),
            Regex("총\\s*금\\s*액\\s*[：:]?\\s*[￦₩]?\\s*([0-9,]+)\\s*원"),
            Regex("([0-9,]{4,})\\s*원"),
        )
        val amounts = mutableListOf<Int>()
        for (pattern in patterns) {
            pattern.findAll(text).forEach { match ->
                match.groupValues.lastOrNull()?.replace(",", "")?.toIntOrNull()?.let { amounts.add(it) }
            }
        }
        return amounts.maxOrNull() ?: 0
    }

    private fun extractTime(text: String): String {
        val pattern = Regex("([0-1]?[0-9]|2[0-3])\\s*[：:]\\s*([0-5][0-9])")
        return pattern.find(text)?.value?.replace("\\s".toRegex(), "") ?: ""
    }

    private fun extractDate(text: String): String {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        val nowYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        fun fmt(y: Int, m: Int, d: Int): String? {
            if (m !in 1..12 || d !in 1..31) return null
            val yy = if (y < 100) 2000 + y else y
            if (yy < 2020 || yy > nowYear + 1) return null
            return "%04d-%02d-%02d".format(yy, m, d)
        }
        // 0) [v41] '일시/승인일시/거래일시' 라벨 뒤 날짜 우선 — YYYY/MM/DD (전화·카드번호를 날짜로 오인 방지)
        Regex("(?:일\\s*시|승인일시|거래일시)[^0-9]{0,4}(20\\d{2})[./\\-](1[0-2]|0?[1-9])[./\\-](3[01]|[12]\\d|0?[1-9])").find(text)?.let {
            fmt(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())?.let { s -> return s }
        }
        // 1) 한글: 2025년 8월 1일 / 25년 8월 1일
        Regex("(20\\d{2}|\\d{2})\\s*년\\s*(1[0-2]|0?[1-9])\\s*월\\s*(3[01]|[12]\\d|0?[1-9])\\s*일").find(text)?.let {
            fmt(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())?.let { s -> return s }
        }
        // 2) 구분자: 2025.08.01 / 2025-8-1 / 25/08/01
        Regex("(20\\d{2}|\\d{2})[./\\-](1[0-2]|0?[1-9])[./\\-](3[01]|[12]\\d|0?[1-9])").find(text)?.let {
            fmt(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())?.let { s -> return s }
        }
        // 3) 8자리: 20250801
        Regex("(20\\d{2})(1[0-2]|0[1-9])(3[01]|[12]\\d|0[1-9])").find(text)?.let {
            fmt(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())?.let { s -> return s }
        }
        // 4) 한글 짧게: 8월 1일 (올해)
        Regex("(1[0-2]|0?[1-9])\\s*월\\s*(3[01]|[12]\\d|0?[1-9])\\s*일").find(text)?.let {
            fmt(nowYear, it.groupValues[1].toInt(), it.groupValues[2].toInt())?.let { s -> return s }
        }
        // 5) MM/DD, MM-DD, MM.DD (올해) — 앞뒤 숫자/점 없을 때만
        Regex("(?<![\\d.])(1[0-2]|0?[1-9])[./\\-](3[01]|[12]\\d|0?[1-9])(?![\\d.])").find(text)?.let {
            fmt(nowYear, it.groupValues[1].toInt(), it.groupValues[2].toInt())?.let { s -> return s }
        }
        return today
    }

    private fun extractLiters(text: String): Float {
        val patterns = listOf(
            Regex("([0-9]+\\.?[0-9]*)\\s*[Ll]"),
            Regex("([0-9]+\\.?[0-9]*)\\s*리터")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)?.groupValues?.get(1)?.toFloatOrNull()
            if (match != null && match > 0) return match
        }
        return 0f
    }

    private fun extractPricePerLiter(text: String): Int {
        val pattern = Regex("단\\s*가\\s*[：:]?\\s*[￦₩]?\\s*([0-9,]+)")
        return pattern.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0
    }

    private fun extractStationName(text: String): String {
        val keywords = listOf("남서울가스", "GS칼텍스", "SK에너지", "S-OIL", "현대오일뱅크", "알뜰주유소")
        for (keyword in keywords) {
            if (text.contains(keyword)) return keyword
        }
        // "○○가스(주)" 형태 상호 추출
        Regex("([가-힣]{2,10}가스)\\s*\\(?주\\)?").find(text)?.groupValues?.get(1)?.let { return it }
        // 첫 의미있는 줄(헤더 노이즈 제외)
        return text.lines().firstOrNull { it.isNotBlank() && !it.contains("전표") && it.length in 2..20 }?.trim()?.take(20) ?: "LPG 충전"
    }

    private fun extractHighwayRoute(text: String): String {
        val pattern = Regex("[가-힣]{2,6}(IC|JC|나들목|분기점)")
        val matches = pattern.findAll(text).map { it.value }.toList()
        return if (matches.size >= 2) "${matches.first()} → ${matches.last()}" else matches.firstOrNull() ?: "통행료"
    }

    private fun extractStoreName(text: String): String {
        return text.lines().firstOrNull { it.isNotBlank() && it.length in 2..20 }?.trim() ?: ""
    }

    fun resultToJson(result: ReceiptResult): JSONObject {
        return JSONObject().apply {
            put("type", result.type.name)
            put("typeName", result.typeName)
            put("amount", result.amount)
            put("time", result.time)
            put("date", result.date)
            put("memo", result.memo)
            put("callCount", result.callCount)
            if (result.liters > 0) put("liters", result.liters)
            if (result.pricePerLiter > 0) put("pricePerLiter", result.pricePerLiter)
        }
    }
}