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
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                Log.d(TAG, "OCR 텍스트:\n$text")
                val result = parseReceipt(text)
                onResult(result)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR 실패: ${e.message}")
                onResult(null)
            }
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
            text.contains("LPG") || text.contains("lpg") || text.contains("충전") && (text.contains("리터") || text.contains("L")) -> ReceiptType.LPG
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
        return ReceiptResult(
            type = ReceiptType.LPG,
            typeName = "충전비",
            amount = extractAmount(text),
            time = extractTime(text),
            date = extractDate(text),
            memo = extractStationName(text),
            rawText = text,
            liters = extractLiters(text),
            pricePerLiter = extractPricePerLiter(text)
        )
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
        val patterns = listOf(
            Regex("(202[0-9])[./\\-](0?[1-9]|1[0-2])[./\\-](0?[1-9]|[12][0-9]|3[01])"),
            Regex("(0?[1-9]|1[0-2])[./](0?[1-9]|[12][0-9]|3[01])")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) return match.value
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
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
        val keywords = listOf("GS칼텍스", "SK에너지", "S-OIL", "현대오일뱅크", "알뜰주유소")
        for (keyword in keywords) {
            if (text.contains(keyword)) return keyword
        }
        return text.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(20) ?: ""
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