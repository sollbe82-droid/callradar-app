package com.callradar.app

// ===== ReceiptOcr v2 (2026-07-14) =====
// 티머니 미터기 전표 → ML Kit 한글 OCR → "총합계 : 58,300원" 금액 추출
// v2 개선: 연도(2026) 오인식 방지, 콤마 금액 우선, 총합계 못찾으면 최대 금액 fallback
// Play Services 방식 (com.google.android.gms:play-services-mlkit)

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

data class ReceiptResult(
    val total: Int?,           // 전표 총합계 (매출 전표용)
    val rawText: String,
    val collectRange: String?,
    val gasAmount: Int? = null,   // 가스/전기 영수증 금액
    val liters: Double? = null,   // 수량 (LPG 리터 or 전기 KWh)
    val unit: String = "L",       // 단위: "L"(LPG) or "KWh"(전기)
    val trips: List<TripLine> = emptyList()  // 전표에서 파싱한 개별 운행들
)

// 전표 개별 거래 = 운행 1건 (시간 + 플랫폼 + 금액)
data class TripLine(
    val time: String,      // "HH:mm" (예: "20:40")
    val date: String,      // "MM/dd" (예: "07/14")
    val platform: String,  // "카드"/"카카오T"/"티머니"
    val paymentType: String, // "card"/"auto" (플랫폼 자동결제)
    val fare: Int          // 금액
)

object ReceiptOcr {

    fun scan(context: Context, imageUri: Uri, onResult: (ReceiptResult) -> Unit) {
        try {
            val image = InputImage.fromFilePath(context, imageUri)
            val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text
                    onResult(ReceiptResult(extractTotal(text), text, extractCollectRange(text), trips = parseTransactions(text)))
                }
                .addOnFailureListener { e ->
                    onResult(ReceiptResult(null, "OCR 실패: ${e.message}", null))
                }
        } catch (e: Exception) {
            onResult(ReceiptResult(null, "OCR 오류: ${e.message}", null))
        }
    }

    // 전표에서 플랫폼별 소계를 뽑아 운행 1건씩 생성 (통합 기록용)
    // OCR이 개별 거래 표를 열별로 쪼개 재배열하므로, 안정적인 "플랫폼 : 금액" 소계 줄만 사용
    // 예: "카카오택시 : 16,000원" → 카카오T 16,000 운행 1건
    private fun parseTransactions(text: String): List<TripLine> {
        val result = mutableListOf<TripLine>()
        val lines = text.split("\n")
        // "플랫폼명 : 금액원" 패턴. 콤마금액 우선
        for (line in lines) {
            val clean = line.replace(" ", "")
            // 총합계 줄은 제외 (개별 플랫폼 아님)
            if (clean.contains("총합계") || clean.contains("합계금액")) continue
            // 콜론 뒤 콤마금액 추출
            val amountM = Regex("[:：]\\s*([0-9]{1,3}(?:,[0-9]{3})+)").find(line) ?: continue
            val fare = amountM.groupValues[1].replace(",", "").toIntOrNull() ?: continue
            if (fare <= 0) continue
            // 플랫폼 판별 (콜론 앞부분)
            val platform: String
            val payType: String
            when {
                clean.startsWith("신용카드") -> { platform = "카드"; payType = "card" }
                clean.startsWith("교통카드") -> { platform = "카드"; payType = "card" }
                clean.startsWith("카카오택시") || clean.startsWith("카카오") -> { platform = "카카오T"; payType = "auto" }
                clean.startsWith("티머니onda") || clean.startsWith("티머니") -> { platform = "티머니"; payType = "auto" }
                clean.startsWith("우버") -> { platform = "우버"; payType = "auto" }
                else -> continue  // 알 수 없는 줄은 스킵
            }
            result.add(TripLine("", "", platform, payType, fare))
        }
        return result
    }

    private fun extractTotal(text: String): Int? {
        val lines = text.split("\n")

        // ① "총합계" 들어간 줄에서 금액 찾기 (콤마 금액 우선)
        for (line in lines) {
            val clean = line.replace(" ", "")
            if (clean.contains("총합계") || clean.contains("총액") || clean.contains("합계금액")) {
                // 같은 줄에서
                val n = extractAmount(line)
                if (n != null) return n
            }
        }

        // ② "총합계" 줄 다음 줄에 금액이 있을 수도 (OCR이 줄 나눔)
        for (i in lines.indices) {
            val clean = lines[i].replace(" ", "")
            if (clean.contains("총합계") || clean.contains("총액")) {
                // 이 줄 + 다음 줄 합쳐서
                val combined = lines[i] + " " + (lines.getOrNull(i + 1) ?: "")
                val n = extractAmount(combined)
                if (n != null) return n
            }
        }

        // ③ fallback: "원"이 붙은 금액 중 최대값 (총합계가 보통 제일 큼)
        val amounts = mutableListOf<Int>()
        val wonRegex = Regex("([0-9]{1,3}(?:,[0-9]{3})+)\\s*원")
        for (m in wonRegex.findAll(text)) {
            m.groupValues[1].replace(",", "").toIntOrNull()?.let { amounts.add(it) }
        }
        if (amounts.isNotEmpty()) return amounts.max()

        // ④ 최후: 콤마 있는 금액 중 최대 (연도·콜ID 같은 콤마없는 숫자 제외됨)
        val commaOnly = Regex("([0-9]{1,3}(?:,[0-9]{3})+)")
        val all = commaOnly.findAll(text).mapNotNull { it.value.replace(",", "").toIntOrNull() }.toList()
        return all.maxOrNull()
    }

    // 문자열에서 금액 추출: 콤마 있는 숫자 우선, 없으면 연도 제외한 큰 숫자
    private fun extractAmount(s: String): Int? {
        // 콤마 있는 금액 (58,300)
        val comma = Regex("([0-9]{1,3}(?:,[0-9]{3})+)").find(s)
        if (comma != null) return comma.value.replace(",", "").toIntOrNull()
        // 콤마 없는 4자리+ 숫자 중 연도(2000~2099) 제외
        val plain = Regex("([0-9]{4,})").findAll(s).mapNotNull { it.value.toIntOrNull() }
            .filter { it < 2000 || it > 2099 }
        return plain.maxOrNull()
    }

    private fun extractCollectRange(text: String): String? {
        val regex = Regex("([0-9]{2}:[0-9]{2}\\s*~\\s*[0-9]{2}:[0-9]{2})")
        return regex.find(text)?.value?.replace(" ", "")
    }

    // ===== 가스(LPG) 영수증 스캔 =====
    fun scanGas(context: Context, imageUri: Uri, onResult: (ReceiptResult) -> Unit) {
        try {
            val image = InputImage.fromFilePath(context, imageUri)
            val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text
                    onResult(ReceiptResult(null, text, null, extractGasAmount(text), extractLiters(text), detectUnit(text)))
                }
                .addOnFailureListener { e ->
                    onResult(ReceiptResult(null, "OCR 실패: ${e.message}", null))
                }
        } catch (e: Exception) {
            onResult(ReceiptResult(null, "OCR 오류: ${e.message}", null))
        }
    }

    // 가스/전기 영수증 최종 "금액" 추출 (여러 기록이면 마지막=최신 것)
    private fun extractGasAmount(text: String): Int? {
        val lines = text.split("\n")
        val found = mutableListOf<Int>()
        for (line in lines) {
            val clean = line.replace(" ", "")
            if ((clean.contains("금액") || clean.contains("합계") || clean.contains("결제금액") || clean.contains("충전금액"))
                && !clean.contains("공급가액") && !clean.contains("세액")) {
                // 콤마 금액 우선 (LPG 57,011)
                val m = Regex("([0-9]{1,3}(?:,[0-9]{3})+)").find(line)
                if (m != null) {
                    m.value.replace(",", "").toIntOrNull()?.let { found.add(it) }
                } else {
                    // 콤마 없는 금액 "원" 앞 (전기 4060원), 연도 제외
                    val m2 = Regex("([0-9]{3,})\\s*원").find(line)
                    if (m2 != null) {
                        val v = m2.groupValues[1].toIntOrNull()
                        if (v != null && (v < 2000 || v > 2099)) found.add(v)
                    }
                }
            }
        }
        // 여러 기록이면 마지막(화면 아래=최신), 없으면 콤마 금액 최대 fallback
        if (found.isNotEmpty()) return found.last()
        val all = Regex("([0-9]{1,3}(?:,[0-9]{3})+)").findAll(text)
            .mapNotNull { it.value.replace(",", "").toIntOrNull() }.toList()
        return all.maxOrNull()
    }

    // 리터(L)/충전량(KWh) 추출 — L 오인식(l·I·1)·키워드분리·단가혼입 대비
    private fun extractLiters(text: String): Double? {
        val lines = text.split("\n")
        // ① "수량"·"충전량" 키워드 줄 + 다음 줄에서 소수 (100 미만 = 리터/KWh)
        for (i in lines.indices) {
            val clean = lines[i].replace(" ", "")
            if (clean.contains("수량") || clean.contains("충전량")) {
                val combined = lines[i] + " " + (lines.getOrNull(i + 1) ?: "")
                val m = Regex("([0-9]{1,3}\\.[0-9]+)").find(combined)
                if (m != null) {
                    val v = m.value.toDoubleOrNull()
                    if (v != null && v < 1000) return v
                }
            }
        }
        // ② L 또는 KWh 앞 소수 (L 오인식 l·I·i 포함)
        val m2 = Regex("([0-9]{1,3}\\.[0-9]+)\\s*(?:[LlIi]|[Kk][Ww][Hh])").find(text)
        if (m2 != null) {
            val v = m2.groupValues[1].toDoubleOrNull()
            if (v != null && v < 1000) return v
        }
        // ③ fallback: 100 미만 소수 중 "원" 안 붙은 것 (단가·금액 제외)
        for (m in Regex("([0-9]{1,3}\\.[0-9]+)").findAll(text)) {
            val v = m.value.toDoubleOrNull() ?: continue
            if (v < 100) {
                val after = text.substring(m.range.last + 1, minOf(m.range.last + 4, text.length))
                if (!after.contains("원")) return v
            }
        }
        return null
    }

    // 전기(KWh)인지 LPG(L)인지 판별
    private fun detectUnit(text: String): String {
        val up = text.uppercase()
        return if (up.contains("KWH") || text.contains("충전량") || text.contains("충전금액")) "KWh" else "L"
    }
}
