package com.callradar.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.callradar.app.screen.AppTheme
import com.callradar.app.screen.Config
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.io.File
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat

/**
 * [v18] 과거기록 이미지 임포트 — 타앱 월별 달력/장부 사진을 우리 앱으로 가져오기.
 *  사진 선택(갤러리, 무권한) → ML Kit OCR → 날짜별 수입/지출 자동 추출 → 편집 미리보기(확인형) → 서버 벌크 임포트.
 *  auto-추출이 조금 틀려도 표에서 고쳐서 넣으므로 데이터 신뢰 유지(헌장 원칙7).
 */
class ImageImportActivity : ComponentActivity() {
    companion object {
        fun start(context: Context, mode: String = "both") {
            context.startActivity(Intent(context, ImageImportActivity::class.java).apply { putExtra("mode", mode); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // [2026-09-02] 사용 계측. 이게 없어서 **인사이트가 60일 통계에 한 줄도 없었다** —
        //  안 쓰는 게 아니라 재고 있지 않았다. Activity 로 뜨는 화면은 MoreScreen 의
        //  open_feature 로그를 안 타므로 여기서 직접 찍어야 한다.
        try { com.callradar.app.Telemetry.log(this, "open_screen", "image_import") } catch (e: Exception) {}
        val userId = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getString("user_id", "") ?: ""
        val mode = intent.getStringExtra("mode") ?: "both"
        setContent { ImportScreen(userId, mode) { finish() } }
    }
}

// 미리보기 행: 일(day) + 수입 + 지출 (문자열로 편집) + [v54] LPG 리터(수량, 소수2자리)
private data class ImpRow(var day: Int, var income: String, var expense: String, var liters: Double = 0.0)

/**
 * [대표 지시] 영수증 항목을 **전부** 담는 편집 상태.
 *
 *  "이 영수증 항목을 다 파싱해서 입력 칸을 넣어라. 그리고 필요한 3항목만 표시되게 하면 되잖아?"
 *
 *  왜 이게 맞는가 — 저는 지금까지 '하나의 정답'(금액 한 개)을 맞히려 했고 두 번 빗나갔다.
 *  빗나가면 기사는 왜 틀렸는지 알 수 없고, 장부 파서가 승인번호 같은 숫자를 골라
 *  40,428 / 464,043 같은 값을 조용히 집어넣었다.
 *  읽은 것을 전부 보여주면, 하나가 틀려도 기사가 그 칸만 고치면 끝난다.
 *  게다가 수량×단가=금액, 공급가액+세액=금액 두 항등식이 화면에서 바로 검산된다.
 */
private data class RcptFields(
    var date: String = "",     // yyyy-MM-dd
    var qty: String = "",      // 수량 (L / kWh)
    var price: String = "",    // 단가
    var supply: String = "",   // 공급가액
    var tax: String = "",      // 세액
    var amount: String = ""    // 금액  ← 기록에 들어가는 값
)

/** 40.510 → "40.51", 40.0 → "40". 안내문에 군더더기 0 을 안 보이게 한다. */
private fun trimNum(d: Double): String {
    val s = String.format("%.3f", d).trimEnd('0').trimEnd('.')
    return if (s.isEmpty() || s == "-") "0" else s
}

/**
 * [2026-08-29 대표 지시] "저 보여지는 걸 클릭하면 넣을 수 있게."
 *
 *  왜 필요했나 — 이 카드의 회색 글씨는 **읽은 값이 아니라 내가 박아둔 예시**였다
 *  ("2026-08-16", "51.980", "60401"). 어떤 영수증을 넣어도 그 숫자가 떠서
 *  기사는 다 읽힌 줄 알았는데, 실제로는 금액 칸이 비어 있어 `enabled` 에 걸려
 *  '아래 표에 넣기' 가 죽어 있었다. 대표가 "금액 맞는데 왜 진행이 안 되냐"고 한 게 이것이다.
 *
 *  그래서 회색을 **진짜 계산값**으로 바꾼다. 부가세는 항등식이라 가게 양식과 무관하다.
 *      세액 = 공급가액 / 10 · 금액 = 공급가액 + 세액 · 금액 = 수량 × 단가
 *  하나만 읽혀도 연쇄로 풀린다. 실제 실패 사례(남서울가스, 단가·세액만 읽힘):
 *      세액 5,491 → 공급가액 54,910 → 금액 60,401 → 수량 60,401/1,162 = 51.98L
 *  영수증 실제 값과 전부 일치한다.
 *
 *  **채워 넣지 않고 제안만 한다.** 단독 복원은 오독한 숫자를 증폭시킬 수 있어서,
 *  기사가 눈으로 보고 탭했을 때만 들어간다. 빈 칸에만 나오고 입력한 값은 건드리지 않는다.
 */
private fun rcptSuggest(r: RcptFields): RcptFields {
    val amtMin = 1_000; val amtMax = 1_000_000
    val qtyMin = 0.5; val qtyMax = 300.0
    val priceMin = 100.0; val priceMax = 5_000.0

    var q = r.qty.toDoubleOrNull() ?: 0.0
    var p = r.price.toDoubleOrNull() ?: 0.0
    var sup = r.supply.toIntOrNull() ?: 0
    var tax = r.tax.toIntOrNull() ?: 0
    var amt = r.amount.toIntOrNull() ?: 0
    if (q !in qtyMin..qtyMax) q = 0.0
    if (p !in priceMin..priceMax) p = 0.0

    // 한 칸이 채워지면 다음 칸이 계산된다 — 연쇄를 위해 두 바퀴 돈다.
    repeat(2) {
        if (amt == 0 && sup > 0 && tax > 0) amt = sup + tax
        if (amt == 0 && sup > 0) amt = sup + Math.round(sup / 10.0).toInt()
        if (amt == 0 && tax > 0) amt = tax * 11
        if (amt == 0 && q > 0 && p > 0) amt = Math.round(q * p).toInt()
        if (amt !in amtMin..amtMax) { amt = 0; return@repeat }
        if (sup == 0) sup = Math.round(amt / 1.1).toInt()
        if (tax == 0) tax = amt - Math.round(amt / 1.1).toInt()
        if (q == 0.0 && p > 0) { val d = amt / p; if (d in qtyMin..qtyMax) q = Math.round(d * 1000.0) / 1000.0 }
        if (p == 0.0 && q > 0) { val d = amt / q; if (d in priceMin..priceMax) p = Math.round(d * 100.0) / 100.0 }
    }

    // 원래 비어 있던 칸에만 제안을 돌려준다. 날짜는 산수로 만들 수 없어 제안하지 않는다.
    return RcptFields(
        date = "",
        qty = if (r.qty.isBlank() && q > 0) trimNum(q) else "",
        price = if (r.price.isBlank() && p > 0) trimNum(p) else "",
        supply = if (r.supply.isBlank() && sup > 0) sup.toString() else "",
        tax = if (r.tax.isBlank() && tax > 0) tax.toString() else "",
        amount = if (r.amount.isBlank() && amt in amtMin..amtMax) amt.toString() else ""
    )
}

// [v19] 가져오기 파싱 규칙 — 서버(/api/import/rules)에서 받아 파서에 적용. 못 받으면 이 기본값 사용.
//  서버→앱 단방향(유저 데이터 수집 X). 새 양식은 서버 규칙만 고치면 앱 업데이트 없이 전 유저 반영.
private data class ImportRules(
    val receiptKeywords: List<String> = listOf("총합계", "총압게", "총압계", "총수입", "총매출", "순수익", "카카오", "신용카드", "교통카드", "거래금액", "거래시간", "마감"),
    val incomeKeywords: List<String> = listOf("총합계", "총압게", "총압계", "총수입", "총매출", "총액", "순수익", "합계"),
    val expenseKeywords: List<String> = listOf("총지출", "지출"),
    // [v24] 연료·충전 영수증(가스/LPG/전기차 충전 등) — 매치되면 '합계'가 있어도 지출로 분류
    val fuelExpenseKeywords: List<String> = listOf("LPG", "엘피지", "가스충전", "충전소", "주유", "리터", "kWh", "전기차", "급속충전", "완속충전", "오일뱅크", "칼텍스", "에너지", "알뜰", "충전요금"),
    val calendarRegex: String = "일.{0,3}월.{0,3}화.{0,3}수.{0,3}목.{0,3}금.{0,3}토"
)

private fun rulesFromJson(txt: String, fallback: ImportRules): ImportRules {
    return try {
        val j = JSONObject(txt)
        fun arr(k: String, def: List<String>): List<String> {
            val a = j.optJSONArray(k) ?: return def
            return (0 until a.length()).map { a.getString(it) }
        }
        ImportRules(arr("receiptKeywords", fallback.receiptKeywords), arr("incomeKeywords", fallback.incomeKeywords), arr("expenseKeywords", fallback.expenseKeywords), arr("fuelExpenseKeywords", fallback.fuelExpenseKeywords), j.optString("calendarRegex", fallback.calendarRegex))
    } catch (e: Exception) { fallback }
}

@Composable
private fun ImportScreen(userId: String, initialMode: String = "both", onClose: () -> Unit) {
    val ctx = LocalContext.current
    val accent = Color(0xFFF59E0B); val green = Color(0xFF10B981); val red = Color(0xFFEF4444); val muted = Color(0xFF6B7280)
    val scope = rememberCoroutineScope()
    val prefs = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    // [v25] 임포트 모드 — 지출: 전부 지출(-) / 수입: 전부 수입(+) / 둘다: 자동 분류(마감 장부). 추측 대신 컨텍스트로 확정.
    val importMode = "expense"   // [유저요청] 가져오기는 지출 전용으로 고정(수입 모드 폐지)
    // [v19] 파싱 규칙: 캐시(마지막 수신) → 없으면 내장 기본값. 화면 열릴 때 서버에서 최신 규칙 갱신.
    var rules by remember { mutableStateOf(prefs.getString("import_rules_json", null)?.let { rulesFromJson(it, ImportRules()) } ?: ImportRules()) }
    LaunchedEffect(Unit) {
        try {
            val txt = withContext(Dispatchers.IO) {
                val conn = (URL("${Config.SERVER_URL}/api/import/rules").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 5000 }
                conn.inputStream.bufferedReader().readText()
            }
            rules = rulesFromJson(txt, rules)
            prefs.edit().putString("import_rules_json", txt).apply()
        } catch (e: Exception) { }
    }

    val cal = remember { Calendar.getInstance() }
    var year by remember { mutableStateOf(cal.get(Calendar.YEAR)) }
    var month by remember { mutableStateOf(cal.get(Calendar.MONTH) + 1) } // 1~12
    var rows by remember { mutableStateOf<List<ImpRow>>(emptyList()) }
    var rawText by remember { mutableStateOf("") }
    var aiTotal by remember { mutableStateOf(0) }   // [v31] 이 OCR의 AI 파싱 합계(학습 feedback용)
    var showRaw by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // [대표 지시] 영수증 한 장을 읽으면 항목별 칸으로 보여준다. null 이면 이 카드는 안 보인다.
    var rcpt by remember { mutableStateOf<RcptFields?>(null) }
    // [유저제안] 단위는 기사 설정의 연료 종류를 따른다 — 차가 하나인데 매번 고를 이유가 없다.
    val myFuelIsEv = (prefs.getString("fuel_type", "lpg") ?: "lpg") == "ev"

    // 이미지 1장 OCR → 표 채우기 (갤러리·카메라 공용). [v19] 회전 사진 자동 보정.
    fun handleOcrText(best: String, accumulate: Boolean = false, onComplete: () -> Unit = {}) {
        rawText = best
        val flat = best.replace("\n", " ")
        // 달력형(카페 가계부·월별): 요일 행이 있으면 여러 날 표로 파싱 (규칙은 서버에서 갱신 가능)
        val looksCalendar = try { Regex(rules.calendarRegex).containsMatchIn(flat) } catch (e: Exception) { false }
        // 마감 전표/일간 요약 키워드 (서버 규칙)
        val looksReceipt = rules.receiptKeywords.any { best.contains(it) }
        var parsed: List<ImpRow> = emptyList()
        when {
            looksCalendar -> { parsed = parseCalendar(best); if (parsed.isEmpty()) parseReceipt(best, month, rules)?.let { parsed = listOf(it) } }
            looksReceipt -> { parseReceipt(best, month, rules)?.let { parsed = listOf(it) }; if (parsed.isEmpty()) parsed = parseCalendar(best) }
            else -> { parsed = parseCalendar(best); if (parsed.isEmpty()) parseReceipt(best, month, rules)?.let { parsed = listOf(it) } }
        }
        // [v25] 모드 적용 — 지출: 전부 지출, 수입: 전부 수입 (income/expense 추측 제거)
        parsed = when (importMode) {
            "expense" -> parsed.map { val t = (it.income.toIntOrNull() ?: 0) + (it.expense.toIntOrNull() ?: 0); it.copy(income = "", expense = if (t > 0) t.toString() else "") }
            "income" -> parsed.map { val t = (it.income.toIntOrNull() ?: 0) + (it.expense.toIntOrNull() ?: 0); it.copy(income = if (t > 0) t.toString() else "", expense = "") }
            else -> parsed
        }
        // [v31] 이 파싱의 AI 합계 기억 → 저장 때 유저 확정값과 함께 학습 서버로(라벨 소스)
        aiTotal = parsed.sumOf { (it.income.toIntOrNull() ?: 0) + (it.expense.toIntOrNull() ?: 0) }
        rows = if (accumulate) {
            // [v24] 여러 장 누적 — 같은 날은 합산, 없으면 추가
            val merged = rows.toMutableList()
            parsed.forEach { p ->
                val idx = if (p.day > 0) merged.indexOfFirst { it.day == p.day } else -1
                if (idx >= 0) {
                    val ex = merged[idx]
                    merged[idx] = ex.copy(
                        income = ((ex.income.toIntOrNull() ?: 0) + (p.income.toIntOrNull() ?: 0)).let { if (it > 0) it.toString() else "" },
                        expense = ((ex.expense.toIntOrNull() ?: 0) + (p.expense.toIntOrNull() ?: 0)).let { if (it > 0) it.toString() else "" }
                    )
                } else merged.add(p)
            }
            merged
        } else parsed
        busy = false
        status = when {
            rows.isEmpty() -> "자동 인식이 안 됐어요. 아래 '직접 추가'로 넣거나 더 밝고 반듯하게 다시 찍어 주세요."
            rows.size == 1 -> "인식됨: ${month}월 ${if (rows[0].day > 0) "${rows[0].day}일 " else ""}— 확인 후 가져오기."
            else -> "${rows.size}건 인식됨 — 숫자를 확인·수정한 뒤 가져오기를 누르세요."
        }
        onComplete()
    }
    // OCR 텍스트 품질 점수(회전 방향 자동 선택용): 그룹금액·키워드·한글 밀도가 높을수록 올바른 방향.
    fun scoreText(t: String): Int {
        val amts = Regex("[0-9]{1,3}(?:[.,][0-9]{3})+").findAll(t).count()
        val kw = listOf("총", "합계", "카카오", "카드", "매출", "거래", "원", "기본급", "공제", "시간").count { t.contains(it) }
        val hangul = t.count { it.code in 0xAC00..0xD7A3 }
        return amts * 4 + kw * 2 + hangul / 15
    }
    // ────────────────────────────────────────────────────────────
    // [v95][유저제보] AI 판독 — "OCR이 하나도 안 맞아서 사용이 안 된다"
    //
    //  ML Kit은 '줄 단위 텍스트'만 준다. 가계부처럼 칸이 격자로 나뉜 장부는
    //  어느 숫자가 어느 날짜 칸인지 복원할 수가 없어서 정규식을 아무리 고쳐도 안 맞는다.
    //  사진을 서버로 보내 비전 모델이 표를 직접 보게 한다.
    //
    //  실패하면 조용히 기존 ML Kit 결과를 그대로 쓴다(퇴행 없음).
    //  사진은 서버에 저장하지 않는다. 결과는 항상 표에서 확인·수정 후 저장한다.
    // ────────────────────────────────────────────────────────────
    // [v96 중지] 사진을 서버로 보내지 않는다. 좌표 기반 LedgerGrid 가 대신한다.
    //  사진을 밖으로 내보내는 순간 처리위탁·국외이전 고지 대상이 되는데,
    //  표 짝짓기는 애초에 폰 안에서 풀리는 문제였다(ML Kit 이 좌표를 준다).
    //  아래 코드는 지우지 않고 남겨 둔다 — 자사 서버 경유 구조로 다시 세울 때 참고용.
    @Suppress("unused")
    fun aiParseDisabled(bitmap: android.graphics.Bitmap, onDone: (List<ImpRow>?) -> Unit) {
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                try {
                    // 긴 변 1600px·JPEG 82 — 비전 모델 권장 해상도. 더 키워도 정확도가 안 오르고 비용만 는다.
                    val w = bitmap.width; val h = bitmap.height; val lng = maxOf(w, h)
                    val send = if (lng > 1600) {
                        val s = 1600f / lng
                        android.graphics.Bitmap.createScaledBitmap(bitmap, (w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1), true)
                    } else bitmap
                    val bos = java.io.ByteArrayOutputStream()
                    send.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, bos)
                    val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)

                    val body = JSONObject().apply {
                        put("image_b64", b64); put("year", year); put("month", month)
                        put("mode", importMode); put("media_type", "image/jpeg")
                    }
                    val conn = (URL("${Config.SERVER_URL}/api/ocr/ledger").openConnection().apply {
                        com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") }
                    } as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        doOutput = true; connectTimeout = 15000; readTimeout = 90000
                    }
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode != 200) { conn.disconnect(); null }
                    else {
                        val txt = conn.inputStream.bufferedReader().readText(); conn.disconnect()
                        val arr = JSONObject(txt).optJSONArray("rows") ?: JSONArray()
                        val list = mutableListOf<ImpRow>()
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val inc = o.optInt("income", 0); val exp = o.optInt("expense", 0)
                            list.add(ImpRow(
                                day = o.optInt("day", 0),
                                income = if (inc > 0) inc.toString() else "",
                                expense = if (exp > 0) exp.toString() else "",
                                liters = o.optDouble("liters", 0.0)))
                        }
                        if (list.isEmpty()) null else list
                    }
                } catch (e: Exception) { null }
            }
            onDone(out)
        }
    }

    fun runOcr(uri: Uri, accumulate: Boolean = false, onComplete: () -> Unit = {}) {
        busy = true; status = "글자를 읽는 중…"
        try {
            val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            val bitmap: android.graphics.Bitmap? = try {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    val src = android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
                    // [v95][OCR정확도] setTargetSampleSize(2) 제거 — 해상도를 절반으로 줄인 뒤 OCR을 돌리고 있었다.
                    //  영수증·가계부 글씨는 원래도 작아서 2배 축소하면 숫자 획이 뭉개진다("하나도 안 맞는다"의 큰 원인).
                    //  대신 지나치게 큰 사진만 긴 변 2600px로 제한해 메모리를 지킨다(그 이하는 원본 그대로).
                    android.graphics.ImageDecoder.decodeBitmap(src) { d, info, _ ->
                        d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        d.isMutableRequired = false
                        val w = info.size.width; val h = info.size.height
                        val long = maxOf(w, h)
                        if (long > 2600) {
                            val s = 2600f / long
                            d.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    android.provider.MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)
                }
            } catch (e: Exception) { null }
            if (bitmap == null) {
                recognizer.process(InputImage.fromFilePath(ctx, uri))
                    .addOnSuccessListener { handleOcrText(it.text, accumulate, onComplete) }
                    .addOnFailureListener { busy = false; status = "읽기 실패: ${it.message}"; onComplete() }
                return
            }
            // [v96] 전처리 — 감열지·흐린 장부를 흑백·고대비로 바꿔 인식률을 올린다(폰 안에서).
            val prepped = OcrPrep.prepare(bitmap)
            // 0/90/270/180 네 방향 OCR → 품질 점수 최고 방향 채택 (옆으로 찍힌 영수증 자동 인식)
            val rots = intArrayOf(0, 90, 270, 180)
            val texts = arrayOfNulls<String>(rots.size)
            // [v96] 좌표를 가진 결과도 같이 들고 있는다 — 표 복원에 쓴다.
            //  ※ finish() 보다 먼저 선언해야 한다. 지역 함수는 뒤에 선언된 지역 변수를 못 본다.
            val visions = arrayOfNulls<com.google.mlkit.vision.text.Text>(rots.size)
            fun finish() {
                val bestIdx = texts.indices.filter { texts[it] != null }
                    .maxByOrNull { scoreText(texts[it]!!) } ?: -1
                val best = if (bestIdx >= 0) texts[bestIdx]!! else ""
                // [v96] 좌표로 표를 복원한다 — 사진을 밖으로 보내지 않는다.
                //  달력형 장부는 '어느 숫자가 어느 날짜 칸인가'가 핵심인데,
                //  그건 글자 위치를 봐야 풀린다. ML Kit 이 주는 좌표로 행·열을 되살린다.
                val gridRows = if (bestIdx >= 0) visions[bestIdx]?.let { v ->
                    LedgerGrid.parse(v, month).map { g ->
                        // 가져오기 모드가 '지출'이면 지출 칸에, '수입'이면 수입 칸에 넣는다.
                        if (importMode == "income") ImpRow(g.day, g.amount.toString(), "", 0.0)
                        else ImpRow(g.day, "", g.amount.toString(), 0.0)
                    }
                } else null
                // ── [유저제보 2차] 영수증인데 장부 파서가 먼저 먹어버렸다 ──────────────
                //  1차 수정에서 "LedgerGrid 가 빈손일 때만 FuelReceipt" 로 짰다.
                //  그런데 LedgerGrid 는 영수증에서도 아무 숫자나 2건씩 뱉는다.
                //  그래서 제 영수증 판독기는 **한 번도 실행되지 않았다**(리터 칸이 계속 빈 이유).
                //  순서를 바꾼다: 검산이 통과한 영수증이면 장부 파서보다 우선한다.
                //  근거 — 장부 사진에서 '수량 × 단가 = 금액' 이 우연히 맞을 확률은 사실상 0이다.
                val vBest = if (bestIdx >= 0) visions[bestIdx] else null
                val fr = if (vBest != null) try { FuelReceipt.parse(vBest) } catch (e: Throwable) { null } else null

                // ── [진단] ML Kit 이 실제로 무엇을 읽었는지 파일로 남긴다 ──────────────
                //  같은 증상이 세 번 반복됐는데, 저는 매번 파서를 고쳤다.
                //  정작 'ML Kit 이 이 영수증을 어떻게 읽는가'를 한 번도 본 적이 없었기 때문이다.
                //  추측으로 고치면 또 빗나간다. 근거를 남긴다.
                //  저장 위치: /sdcard/Android/data/com.callradar.app/files/cr_ocr_dump.txt
                //  (앱 전용 폴더라 다른 앱은 못 읽고, 앱 지우면 같이 지워진다)
                try {
                    val sb = StringBuilder()
                    sb.append("=== 회전선택 idx=").append(bestIdx).append(" ===\n")
                    sb.append("=== FuelReceipt 결과 ===\n")
                    if (fr == null) sb.append("null (글자 4개 미만이거나 아무 항목도 못 읽음)\n")
                    else sb.append("일시=").append(fr.year).append("-").append(fr.month).append("-").append(fr.day)
                        .append(" 금액=").append(fr.amount).append(" 수량=").append(fr.liters)
                        .append(" 단가=").append(fr.unitPrice).append(" 공급가액=").append(fr.supply)
                        .append(" 세액=").append(fr.tax).append(" 검산=").append(fr.verified)
                        .append(" 경로=").append(fr.how).append("\n")
                    sb.append("=== accumulate=").append(accumulate).append(" ===\n")
                    if (vBest != null) {
                        sb.append("=== ML Kit 줄 단위(Line) ===\n")
                        OcrLayout.lineRows(vBest).forEachIndexed { li, row ->
                            sb.append(li).append(": [")
                            sb.append(row.joinToString(" | ") { "${it.text}@${it.box.left},${it.cy}" })
                            sb.append("]\n")
                        }
                    }
                    sb.append("=== 납작한 원문 ===\n").append(best).append("\n")
                    java.io.File(ctx.getExternalFilesDir(null), "cr_ocr_dump.txt")
                        .writeText(sb.toString())
                } catch (e: Throwable) { }
                // [대표 지시] 영수증에서 뭐라도 읽었으면 **항목 편집 화면**으로 간다.
                //  장부(달력) 파서에 넘기지 않는다 — 그게 승인번호를 금액으로 집어넣던 경로다.
                //  한 장씩 넣을 때(누적 아님)만 적용한다. 여러 장 장부 스캔은 기존대로.
                //  · 검산이 통과한 영수증이면 여러 장 스캔 중이어도 장부 파서보다 우선한다.
                //  · 한 장만 넣는 중이면 부분만 읽혔어도 카드를 띄워 기사가 채우게 한다.
                val receiptWins = fr != null && (fr.verified || (!accumulate && fr.anyField))

                run {
                    val aiRows = if (receiptWins) null else gridRows
                    if (aiRows != null && aiRows.isNotEmpty()) {
                        rawText = best
                        aiTotal = aiRows.sumOf { (it.income.toIntOrNull() ?: 0) + (it.expense.toIntOrNull() ?: 0) }
                        rows = if (accumulate) {
                            val merged = rows.toMutableList()
                            aiRows.forEach { p ->
                                val idx = if (p.day > 0) merged.indexOfFirst { it.day == p.day } else -1
                                if (idx >= 0) {
                                    val ex = merged[idx]
                                    merged[idx] = ex.copy(
                                        income = ((ex.income.toIntOrNull() ?: 0) + (p.income.toIntOrNull() ?: 0)).let { if (it > 0) it.toString() else "" },
                                        expense = ((ex.expense.toIntOrNull() ?: 0) + (p.expense.toIntOrNull() ?: 0)).let { if (it > 0) it.toString() else "" })
                                } else merged.add(p)
                            }
                            merged
                        } else aiRows
                        busy = false
                        // 장부 파서가 이겼을 때, 영수증 판독이 왜 졌는지 화면에 남긴다.
                        //  이 한 줄이 없어서 "영수증 경로가 아예 실행 안 됐다"는 걸 세 번 만에 알았다.
                        val why = if (fr == null) "영수증아님" else "영수증부분(${fr.how})"
                        status = "${rows.size}건 인식됨(장부표) — 숫자를 확인·수정한 뒤 가져오기를 누르세요. [$why]"
                        onComplete()
                    } else {
                        if (fr != null && fr.anyField) {
                            // [대표 지시] 읽은 항목을 **전부 칸에 채워** 보여준다. 표에 바로 밀어넣지 않는다.
                            //  못 읽은 칸은 비워 둔다 — 0 이나 엉뚱한 숫자로 채우면 기사가 못 알아챈다.
                            rawText = best
                            aiTotal = fr.amount ?: 0
                            if (fr.year != null && fr.month != null) { year = fr.year; month = fr.month }
                            if (accumulate) {
                                // 여러 장 스캔 중이면 카드를 띄울 자리가 없다(장마다 하나씩 뜰 수 없으므로).
                                //  검산이 통과한 것만 여기 오므로 표에 바로 넣는다.
                                val amt = fr.amount ?: 0
                                val lit = fr.liters ?: 0.0
                                val day = (fr.day ?: cal.get(Calendar.DAY_OF_MONTH)).coerceIn(1, 31)
                                val merged = rows.toMutableList()
                                val idx = merged.indexOfFirst { it.day == day }
                                if (idx >= 0) {
                                    val ex = merged[idx]
                                    merged[idx] = ex.copy(
                                        expense = ((ex.expense.toIntOrNull() ?: 0) + amt).toString(),
                                        liters = ex.liters + lit)
                                } else merged.add(ImpRow(day, "", amt.toString(), lit))
                                rows = merged
                                busy = false
                                onComplete()
                                return@run
                            }
                            rcpt = RcptFields(
                                date = if (fr.year != null && fr.month != null && fr.day != null)
                                    "%04d-%02d-%02d".format(fr.year, fr.month, fr.day) else "",
                                qty = fr.liters?.let { trimNum(it) } ?: "",
                                price = fr.unitPrice?.let { trimNum(it) } ?: "",
                                supply = fr.supply?.toString() ?: "",
                                tax = fr.tax?.toString() ?: "",
                                amount = fr.amount?.toString() ?: ""
                            )
                            busy = false
                            val missing = listOfNotNull(
                                if (fr.day == null) "일시" else null,
                                if (fr.liters == null) "수량" else null,
                                if (fr.amount == null) "금액" else null
                            )
                            status = if (missing.isEmpty()) "영수증을 읽었습니다 — 아래에서 확인하세요. [${fr.how}]"
                                     else "일부만 읽었습니다 (못 읽음: ${missing.joinToString("·")}) — 아래 칸에 직접 넣어 주세요. [${fr.how}]"
                            onComplete()
                        } else {
                            // 영수증도 아니고 표도 아니면 기존 정규식 경로로 (마지막 수단).
                            //  여기까지 왔다는 건 영수증 판독기가 금액조차 못 찾았다는 뜻이므로,
                            //  무엇을 못 찾았는지 화면에 남긴다 — 이게 없어서 원인 찾는 데 계속 헤맸다.
                            handleOcrText(best, accumulate) {
                                status = status + "  [영수증판독 실패: " +
                                    (if (fr == null) "글자 부족" else "금액 못찾음") + "]"
                                onComplete()
                            }
                        }
                    }
                }
            }
            fun tryRot(i: Int) {
                if (i >= rots.size) { finish(); return }
                status = "글자를 읽는 중… (${i + 1}/${rots.size})"
                recognizer.process(InputImage.fromBitmap(prepped, rots[i]))
                    .addOnSuccessListener { texts[i] = it.text; visions[i] = it; tryRot(i + 1) }
                    .addOnFailureListener { texts[i] = ""; tryRot(i + 1) }
            }
            tryRot(0)
        } catch (e: Exception) { busy = false; status = "오류: ${e.message}"; onComplete() }
    }
    // 🖼 갤러리 (1장)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> if (uri != null) runOcr(uri) }
    // [v24] 🖼 여러 장 한 번에 — 순차 OCR + 누적(같은 날 합산)
    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            rows = emptyList()
            var i = 0
            fun next() {
                if (i >= uris.size) {
                    busy = false
                    // [버그] 한 장만 골라도 여기서 상태문구를 덮어써서, 영수증 카드 안내가 사라졌다.
                    if (rcpt == null) status = "${rows.size}건 인식 완료 — 확인 후 가져오기를 누르세요."
                    return
                }
                if (uris.size > 1) status = "여러 장 읽는 중… (${i + 1}/${uris.size})"
                // [버그·핵심] 예전엔 한 장을 골라도 accumulate=true 로 넘겼다.
                //  그런데 영수증 판독 경로는 '한 장(accumulate=false)'일 때만 돌게 돼 있어서
                //  **영수증 카드가 한 번도 안 떴다.** 기사가 쓰는 버튼은 '갤러리(여러장)' 하나뿐인데.
                //  장수로 판단한다 — 한 장이면 영수증, 여러 장이면 장부 누적.
                runOcr(uris[i], accumulate = uris.size > 1) { i++; next() }
            }
            next()
        }
    }
    // 📷 카메라 (촬영 → 임시파일 → OCR)
    var camUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) camUri?.let { runOcr(it) } }
    fun launchCamera() {
        try {
            val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
            val f = File(dir, "cap_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            camUri = uri; cameraLauncher.launch(uri)
        } catch (e: Exception) { status = "카메라 실행 실패: ${e.message}" }
    }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) launchCamera() else status = "카메라 권한이 필요해요" }
    fun onCameraClick() {
        val ok = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (ok) launchCamera() else camPerm.launch(android.Manifest.permission.CAMERA)
    }
    // 📄 파일 (CSV/텍스트: 날짜,수입,지출)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                rawText = text
                rows = parseCsv(text)
                status = if (rows.isEmpty()) "파일에서 날짜·금액을 못 찾았어요. 형식 예: 2026-07-22,350000,20000" else "${rows.size}건 인식됨 — 확인·수정 후 가져오기."
            } catch (e: Exception) { status = "파일 읽기 실패: ${e.message}" }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.bg).padding(16.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("➖ 지출 가져오기", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("닫기", color = muted) }
        }
        Text("가스·정비·세차 영수증이나 지출 장부를 📷카메라·🖼갤러리(여러장)·📄파일로 가져와요. 인식 후 표에서 확인·수정하고 넣습니다. (수입은 운행기록에서 자동 집계돼요)", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        // [유저요청] 수입 개념 완전 제거 — 가져오기는 '지출 전용'. 수입은 운행기록에서만 잡힌다.
        //  (모드 칩·수입 입력칸이 남아 있어 혼란을 줬음 → 화면에서 통째로 삭제)

        // [대표지시] "사진 인식률이 낮습니다 수동으로 하세요 문구 넣고"
        //  감열지 영수증은 인쇄가 흐리고 구겨져서 기계가 100% 읽을 수 없다.
        //  못 읽는 걸 숨기지 않고 먼저 말한다 — 기사가 숫자를 안 보고 넣는 게 제일 위험하다.
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Color(0x33F59E0B), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text("⚠️", fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
            Text(
                "사진 인식률은 100%가 아닙니다. 감열지 영수증은 흐리거나 구겨지면 숫자를 잘못 읽을 수 있어요.\n" +
                    "가져오기 전에 표의 금액·수량을 꼭 확인하시고, 틀리면 직접 고쳐 넣으세요.",
                fontSize = 11.sp, color = Color(0xFFFBBF24), modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))

        // 연·월 선택 (달력 사진엔 연도가 없어 여기서 지정)
        Text("가져올 연·월", fontSize = 13.sp, color = muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)) {
            OutlinedButton(onClick = { year-- }) { Text("−", color = accent) }
            Text("${year}년", color = AppTheme.text, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { year++ }) { Text("+", color = accent) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { month = if (month <= 1) 12 else month - 1 }) { Text("−", color = accent) }
            Text("${month}월", color = AppTheme.text, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { month = if (month >= 12) 1 else month + 1 }) { Text("+", color = accent) }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onCameraClick() }, modifier = Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(12.dp)) { Text("📷 카메라", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            Button(onClick = { multiPicker.launch("image/*") }, modifier = Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(12.dp)) { Text("🖼 갤러리(여러장)", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            Button(onClick = { filePicker.launch("*/*") }, modifier = Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151)), shape = RoundedCornerShape(12.dp)) { Text("📄 파일", color = AppTheme.text, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        }

        // 검산 결과를 색으로도 알린다. ⚠️(검산 실패)는 초록으로 칠하면 안 된다 — 확인하라는 신호다.
        if (status.isNotEmpty()) Text(
            status, fontSize = 12.sp,
            color = when {
                status.startsWith("⚠️") -> accent
                rows.isEmpty() && !busy -> red
                else -> green
            },
            modifier = Modifier.padding(top = 10.dp)
        )

        // ── [대표 지시] 영수증 항목 카드 ────────────────────────────────
        //  "영수증 항목을 다 파싱해서 입력 칸을 넣어라. 필요한 3항목만 표시되게."
        //  일시·수량·금액만 기록에 들어가고, 단가·공급가액·세액은 **검산 근거**로 보여준다.
        //  기계가 틀려도 기사가 그 칸만 고치면 끝난다.
        rcpt?.let { r ->
            val q = r.qty.toDoubleOrNull() ?: 0.0
            val p = r.price.toDoubleOrNull() ?: 0.0
            val a = r.amount.toIntOrNull() ?: 0
            val sup = r.supply.toIntOrNull() ?: 0
            val tx = r.tax.toIntOrNull() ?: 0
            val calcQP = if (q > 0 && p > 0) Math.round(q * p).toInt() else 0
            val calcST = if (sup > 0 && tx > 0) sup + tx else 0
            val okQP = calcQP > 0 && a > 0 && kotlin.math.abs(calcQP - a) <= maxOf(2, a / 50)
            val okST = calcST > 0 && a > 0 && kotlin.math.abs(calcST - a) <= 2

            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(Color(0xFF111827), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Text("🧾 영수증에서 읽은 항목", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = accent)
                Text("빈 칸은 못 읽은 것이에요. 직접 넣으시면 됩니다.", fontSize = 11.sp, color = muted,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))

                @Composable
                fun field(label: String, value: String, unit: String, hint: String,
                          keyboard: KeyboardType, suggest: String = "", onChange: (String) -> Unit) {
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(label, fontSize = 13.sp, color = muted, modifier = Modifier.width(78.dp))
                            OutlinedTextField(
                                value = value, onValueChange = onChange,
                                placeholder = { Text(hint, fontSize = 12.sp, color = Color(0xFF4B5563)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = AppTheme.text, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f)
                            )
                            if (unit.isNotEmpty())
                                Text(unit, fontSize = 12.sp, color = muted, modifier = Modifier.padding(start = 6.dp).width(34.dp))
                            else Spacer(Modifier.width(40.dp))
                        }
                        // [대표 지시] 계산으로 나온 값을 보여주고, **누르면 그 칸에 들어간다.**
                        //  빈 칸에만 뜬다. 직접 적은 값은 절대 안 건드린다.
                        if (value.isBlank() && suggest.isNotBlank()) {
                            Row(modifier = Modifier
                                .padding(start = 78.dp, top = 3.dp)
                                .background(Color(0xFF1F2937), RoundedCornerShape(8.dp))
                                .clickable { onChange(suggest) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text("계산 $suggest$unit  ·  눌러서 넣기",
                                    fontSize = 12.sp, color = accent, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                val unitName = if (myFuelIsEv) "kWh" else "L"
                // [2026-08-29] 예전엔 여기 hint 자리에 이 영수증 숫자가 하드코딩돼 있었다
                //  ("2026-08-16"/"51.980"/"60401"). 어떤 영수증에도 그게 떠서 다 읽은 것처럼 보였다.
                //  이제 hint 는 형식 안내뿐이고, 값 제안은 sg(계산값)가 맡는다.
                val sg = rcptSuggest(r)
                field("일시", r.date, "", "YYYY-MM-DD", KeyboardType.Text) { rcpt = r.copy(date = it) }
                field("수량", r.qty, unitName, "직접 입력", KeyboardType.Decimal, sg.qty) { rcpt = r.copy(qty = it) }
                field("단가", r.price, "원", "직접 입력", KeyboardType.Decimal, sg.price) { rcpt = r.copy(price = it) }
                field("공급가액", r.supply, "원", "직접 입력", KeyboardType.Number, sg.supply) { rcpt = r.copy(supply = it) }
                field("세액", r.tax, "원", "직접 입력", KeyboardType.Number, sg.tax) { rcpt = r.copy(tax = it) }
                field("금액", r.amount, "원", "직접 입력", KeyboardType.Number, sg.amount) { rcpt = r.copy(amount = it) }

                // 계산값이 두 칸 이상이면 하나씩 누르는 게 번거롭다 — 한 번에.
                if (listOf(sg.qty, sg.price, sg.supply, sg.tax, sg.amount).count { it.isNotBlank() } >= 2) {
                    TextButton(onClick = {
                        rcpt = r.copy(
                            qty = if (r.qty.isBlank() && sg.qty.isNotBlank()) sg.qty else r.qty,
                            price = if (r.price.isBlank() && sg.price.isNotBlank()) sg.price else r.price,
                            supply = if (r.supply.isBlank() && sg.supply.isNotBlank()) sg.supply else r.supply,
                            tax = if (r.tax.isBlank() && sg.tax.isNotBlank()) sg.tax else r.tax,
                            amount = if (r.amount.isBlank() && sg.amount.isNotBlank()) sg.amount else r.amount
                        )
                    }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text("계산값 모두 넣기", fontSize = 13.sp, color = accent, fontWeight = FontWeight.Bold)
                    }
                }

                // 검산 — 기계가 맞았는지 기사가 눈으로 확인할 수 있게 근거를 보여준다.
                val checks = buildList {
                    if (calcQP > 0) add((if (okQP) "✅" else "⚠️") +
                        " 수량×단가 = ${String.format("%,d", calcQP)}원")
                    if (calcST > 0) add((if (okST) "✅" else "⚠️") +
                        " 공급가액+세액 = ${String.format("%,d", calcST)}원")
                }
                if (checks.isNotEmpty()) Text(
                    checks.joinToString("   ") + if (a > 0) "   (금액 ${String.format("%,d", a)}원)" else "",
                    fontSize = 12.sp, color = if (okQP || okST) green else accent,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val day = Regex("(\\d{1,2})\\s*$").find(r.date.trim())?.groupValues?.get(1)?.toIntOrNull()
                                ?: cal.get(Calendar.DAY_OF_MONTH)
                            Regex("(20\\d{2})[-./](\\d{1,2})").find(r.date)?.let { m ->
                                year = m.groupValues[1].toInt(); month = m.groupValues[2].toInt()
                            }
                            val amt = r.amount.toIntOrNull() ?: 0
                            val one = ImpRow(day.coerceIn(1, 31), "", if (amt > 0) amt.toString() else "",
                                r.qty.toDoubleOrNull() ?: 0.0)
                            val merged = rows.toMutableList()
                            val idx = merged.indexOfFirst { it.day == one.day }
                            if (idx >= 0) {
                                val ex = merged[idx]
                                merged[idx] = ex.copy(
                                    expense = ((ex.expense.toIntOrNull() ?: 0) + amt).toString(),
                                    liters = ex.liters + one.liters)
                            } else merged.add(one)
                            rows = merged
                            rcpt = null
                            status = "표에 넣었습니다 — 확인 후 '이 내용으로 가져오기'를 누르세요."
                        },
                        enabled = (r.amount.toIntOrNull() ?: 0) > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = green),
                        shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)
                    ) { Text("아래 표에 넣기", color = Color.Black, fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = { rcpt = null }, shape = RoundedCornerShape(10.dp)) {
                        Text("버리기", color = muted)
                    }
                }
            }
        }

        // [v19] 인식이 안 돼도 항상 표를 보여줘서 '직접 추가'로 넣을 수 있게 (기록 안됨 방지)
        run {
            Spacer(Modifier.height(12.dp))
            Text("표에서 날짜(일)·지출 금액을 확인·수정하세요. 위의 연·월이 이 표의 기준이에요.", fontSize = 11.sp, color = muted, modifier = Modifier.padding(bottom = 2.dp))
            // [유저제보] 충전량 칸이 아예 없었다. ImpRow 에 liters 는 있는데 화면에 안 그려서
            //  가스·전기 영수증을 넣어도 리터/kWh 가 사라졌다. 연비·전비가 계산될 수 없던 이유.
            // [유저제안] 단위는 **기사 설정의 연료 종류**를 따른다.
            //  기사는 차가 하나라 매번 고를 이유가 없다. 설정 한 번이면 앱 전체가 맞춰진다.
            //  (더보기 → 기사 설정 → 가스·연료 → LPG / ⚡전기차)
            val isEv = (prefs.getString("fuel_type", "lpg") ?: "lpg") == "ev"
            val fuelUnit = if (isEv) "kWh" else "L"
            Text("${if (isEv) "전기 충전" else "가스"} 영수증이면 충전량($fuelUnit)도 함께 넣어 주세요. " +
                 "${if (isEv) "전비" else "연비"} 계산에 쓰입니다.  ·  단위는 기사 설정을 따릅니다",
                fontSize = 11.sp, color = accent, modifier = Modifier.padding(bottom = 6.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("${month}월 일", fontSize = 12.sp, color = muted, modifier = Modifier.width(56.dp))
                if (importMode != "expense") Text("수입", fontSize = 12.sp, color = muted, modifier = Modifier.weight(1f))   // [v53] 지출 컨텍스트에선 수입칸 숨김
                Text("지출", fontSize = 12.sp, color = muted, modifier = Modifier.weight(1f))
                Text(fuelUnit, fontSize = 12.sp, color = muted, modifier = Modifier.width(74.dp))
                Spacer(Modifier.width(32.dp))
            }
            // 편집 표 (날짜·금액 모두 수정 가능 — 오인식 교정)
            Column {
                rows.forEachIndexed { idx, r ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        OutlinedTextField(value = if (r.day > 0) r.day.toString() else "", onValueChange = { v -> val d = v.filter { c -> c.isDigit() }.take(2).toIntOrNull() ?: 0; rows = rows.toMutableList().also { it[idx] = r.copy(day = d) } },
                            modifier = Modifier.width(56.dp), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = AppTheme.text),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Spacer(Modifier.width(6.dp))
                        if (importMode != "expense") {   // [v53] 지출 컨텍스트에선 수입 입력칸 숨김
                        OutlinedTextField(value = r.income, onValueChange = { v -> rows = rows.toMutableList().also { it[idx] = r.copy(income = v.filter { c -> c.isDigit() }) } },
                            modifier = Modifier.weight(1f), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = green),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Spacer(Modifier.width(6.dp))
                        }
                        OutlinedTextField(value = r.expense, onValueChange = { v -> rows = rows.toMutableList().also { it[idx] = r.copy(expense = v.filter { c -> c.isDigit() }) } },
                            modifier = Modifier.weight(1f), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = red),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Spacer(Modifier.width(6.dp))
                        // [유저제보] 충전량 — 가스는 리터, 전기는 kWh. 소수점 허용(38.412L 같은 값이 흔하다).
                        //  0 이면 빈칸으로 보여준다(0을 굳이 보여줄 이유가 없다).
                        OutlinedTextField(
                            value = if (r.liters > 0.0) (if (r.liters % 1.0 == 0.0) r.liters.toInt().toString() else r.liters.toString()) else "",
                            onValueChange = { v ->
                                val cleaned = v.filter { c -> c.isDigit() || c == '.' }
                                rows = rows.toMutableList().also { it[idx] = r.copy(liters = cleaned.toDoubleOrNull() ?: 0.0) }
                            },
                            modifier = Modifier.width(74.dp), singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = accent),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        TextButton(onClick = { rows = rows.toMutableList().also { it.removeAt(idx) } }) { Text("✕", color = muted) }
                    }
                }
            }
            OutlinedButton(onClick = { val nd = (rows.maxOfOrNull { it.day } ?: (cal.get(Calendar.DAY_OF_MONTH) - 1)) + 1; rows = rows + ImpRow(nd.coerceIn(1, 31), "", "") }, modifier = Modifier.padding(top = 6.dp)) { Text("+ 직접 추가", color = accent) }

            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                if (busy) return@Button
                busy = true; status = "가져오는 중…"
                val payload = rows.mapNotNull { r ->
                    val inc = r.income.toIntOrNull() ?: 0; val exp = r.expense.toIntOrNull() ?: 0
                    if (r.day in 1..31 && (inc > 0 || exp > 0)) {
                        val mm = month.toString().padStart(2, '0'); val dd = r.day.toString().padStart(2, '0')
                        JSONObject().apply { put("date", "$year-$mm-$dd"); put("income", inc); put("expense", exp); if (r.liters > 0) put("liters", r.liters) }
                    } else null
                }
                scope.launch {
                    try {
                        val resp = withContext(Dispatchers.IO) {
                            // [유저제안] 기사 설정의 연료 종류를 같이 보낸다 → 서버가 LPG/전기를 정확히 갈라 저장.
                            val json = JSONObject().apply { put("user_id", userId); put("records", JSONArray(payload)); put("fuel_type", prefs.getString("fuel_type", "lpg") ?: "lpg") }
                            val conn = (URL("${Config.SERVER_URL}/api/import/bulk").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 10000; readTimeout = 15000 }
                            conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                            conn.inputStream.bufferedReader().readText()
                        }
                        val j = JSONObject(resp)
                        busy = false; status = "✅ ${j.optInt("imported", 0)}일 가져왔어요! 기록·통계에서 확인하세요."
                        // [v31] 영수증 인식 학습 feedback — AI 파싱값(ai) vs 유저 확정 합계(user) + OCR 원문(raw)
                        //  → 이미 배포된 /api/feedback 엔진이 오답 패턴에서 키워드 자동발굴(카나리·가드레일 적용). Play에서 살아있는 유일 학습 target.
                        try {
                            val userTotal = rows.sumOf { (it.income.toIntOrNull() ?: 0) + (it.expense.toIntOrNull() ?: 0) }
                            if (rawText.isNotBlank() && userTotal > 0) {
                                val feat = if (importMode == "income") "import_income" else "import_expense"
                                com.callradar.app.Feedback.send(ctx, feat, "receipt", rawText, if (aiTotal > 0) aiTotal.toString() else null, userTotal.toString())
                            }
                        } catch (e: Exception) {}
                    } catch (e: Exception) { busy = false; status = "가져오기 실패: ${e.message}" }
                }
            }, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = green), shape = RoundedCornerShape(12.dp)) {
                Text("이 내용으로 가져오기", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        if (rawText.isNotEmpty()) {
            TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "OCR 원문 접기" else "OCR 원문 보기(개발자 확인용)", color = muted, fontSize = 12.sp) }
            if (showRaw) Text(rawText, fontSize = 11.sp, color = muted, modifier = Modifier.padding(bottom = 20.dp))
        }
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * 달력/장부 OCR 텍스트에서 (일, 수입, 지출) 후보를 뽑는다.
 * 규칙: '일자 토큰'(1~31, "7.1"의 .뒤 숫자 포함) 뒤에 오는 콤마 금액들을 그 날의 값으로.
 *   첫 금액=수입, 둘째 금액=지출(셋째=합계는 무시). 편집 미리보기에서 최종 확인.
 */
/**
 * CSV/텍스트에서 (일, 수입, 지출) 추출. 한 줄에 날짜 + 금액들.
 * 날짜: YYYY-MM-DD / YYYY.MM.DD / M/D 등에서 '일'을 뽑고, 날짜 부분은 금액 매칭에서 제외(연도 오인식 방지).
 * 금액: 콤마금액 우선, 없으면 3자리 이상 숫자. 첫 금액=수입, 둘째=지출.
 */
/**
 * [v19] '일 마감 전표'(티머니고/카카오 등) OCR → 그날 총매출 1건.
 * 총합계/합계/총액 라벨의 금액을 그날 수입으로, 07/22 같은 날짜에서 '일'을 뽑는다.
 * 여러 플랫폼 합산 전표라 개별 운행 대신 '하루 총액 1건'으로 가져오는 게 안전.
 */
private fun parseReceipt(raw: String, month: Int, rules: ImportRules = ImportRules()): ImpRow? {
    // 금액: 천단위 구분(콤마 또는 OCR이 마침표로 오인식한 경우 모두) — 카드번호/콜ID(구분자 없음)는 제외됨
    val amtRe = Regex("[0-9]{1,3}(?:[.,][0-9]{3})+")
    fun toInt(s: String) = s.replace(",", "").replace(".", "").toIntOrNull() ?: 0
    val lines = raw.split(Regex("\\r?\\n"))
    fun norm(s: String) = s.replace(" ", "")
    val amounts = amtRe.findAll(raw).map { toInt(it.value) }.filter { it in 1000..99999999 }.toList()
    if (amounts.isEmpty()) return null
    // 수입 = 총합계/총수입/총매출/순수익/총액 라인의 금액(여럿이면 최대), 없으면 전체 최댓값. (규칙은 서버에서 갱신)
    var income = 0
    for (line in lines) {
        val n = norm(line)
        if (rules.incomeKeywords.any { n.contains(norm(it)) }) amtRe.findAll(line).forEach { val v = toInt(it.value); if (v in 1000..99999999 && v > income) income = v }
    }
    if (income <= 0) income = amounts.max()
    // 지출 = 지출 라벨 라인의 금액('수입' 포함 라인 제외). 0원(구분자 없음)은 매칭 안 됨 → 빈칸.
    var expense = 0
    for (line in lines) {
        val n = norm(line)
        if (rules.expenseKeywords.any { n.contains(norm(it)) } && !n.contains("수입")) amtRe.findAll(line).forEach { val v = toInt(it.value); if (v in 1000..99999999 && v > expense) expense = v }
    }
    // [v24] 연료·충전 영수증이면 '합계'가 있어도 지출로 분류(수입 아님)
    val isFuel = rules.fuelExpenseKeywords.any { kw -> raw.contains(kw, ignoreCase = true) }
    if (isFuel && expense == 0 && income > 0) { expense = income; income = 0 }
    // 날짜: 전체날짜(YYYY-MM-DD / YYYY.MM.DD / 'YYYY년 M월 D일') 우선 → 월 일치 일자, 없으면 M/D(월 일치)
    var day = 0
    val full = Regex("(\\d{4})[./년\\-]\\s*(\\d{1,2})[./월\\-]\\s*(\\d{1,2})")
    for (m in full.findAll(raw)) { val mm = m.groupValues[2].toIntOrNull(); val dd = m.groupValues[3].toIntOrNull(); if (mm == month && dd != null && dd in 1..31) { day = dd; break } }
    if (day == 0) for (m in full.findAll(raw)) { val dd = m.groupValues[3].toIntOrNull(); if (dd != null && dd in 1..31) { day = dd; break } }
    if (day == 0) { val md = Regex("(?<![0-9])(\\d{1,2})[./\\-](\\d{1,2})(?![0-9])"); for (m in md.findAll(raw)) { val mm = m.groupValues[1].toIntOrNull(); val dd = m.groupValues[2].toIntOrNull(); if (mm == month && dd != null && dd in 1..31) { day = dd; break } } }
    // [v54] LPG 가스영수증 리터(수량) — 소수 2자리. '수량'/L 라벨 또는 소수점값 중 3~200L(단가 1000+·금액은 소수없어 제외).
    var liters = 0.0
    if (isFuel) {
        // 리터(수량)=소수점 '정확히 3자리'(25.394/49.160). 단가·판매시각(01:26.14)은 2자리, 금액은 콤마 천단위(30,447)라 제외됨.
        //  콤마 뒤도 제외(1,199.00의 199.00 방지). 3~200L 범위. 반올림 안 함(원값).
        val litRe = Regex("(?<![0-9,])(\\d{1,3}\\.\\d{3})(?![0-9])")
        for (ln in lines) {
            if (norm(ln).contains("수량") || Regex("\\d\\.\\d{3}\\s*[Ll]").containsMatchIn(ln)) {
                litRe.findAll(ln).forEach { val v = it.groupValues[1].toDoubleOrNull() ?: 0.0; if (v in 3.0..200.0 && v > liters) liters = v }
            }
        }
        if (liters == 0.0) litRe.findAll(raw).forEach { val v = it.groupValues[1].toDoubleOrNull() ?: 0.0; if (v in 3.0..200.0 && v > liters) liters = v }
    }
    return ImpRow(day, income.toString(), if (expense > 0) expense.toString() else "", liters)
}

private fun parseCsv(raw: String): List<ImpRow> {
    val out = mutableListOf<ImpRow>()
    val seen = HashSet<Int>()
    for (line in raw.split(Regex("\\r?\\n"))) {
        if (line.isBlank()) continue
        var rest = line
        var day: Int? = null
        val full = Regex("(\\d{4})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})").find(line)
        if (full != null) { day = full.groupValues[3].toIntOrNull(); rest = line.replaceRange(full.range, "  ") }
        else {
            val md = Regex("(\\d{1,2})[./](\\d{1,2})").find(line)
            if (md != null) { day = md.groupValues[2].toIntOrNull(); rest = line.replaceRange(md.range, "  ") }
        }
        if (day == null || day !in 1..31) continue
        val amts = Regex("[0-9]{1,3}(?:,[0-9]{3})+|\\d{3,}").findAll(rest)
            .mapNotNull { it.value.replace(",", "").toIntOrNull() }.filter { it in 1..99999999 }.toList()
        if (amts.isEmpty()) continue
        if (!seen.add(day)) continue
        out.add(ImpRow(day, amts[0].toString(), amts.getOrNull(1)?.toString() ?: ""))
    }
    return out.sortedBy { it.day }
}

private fun parseCalendar(raw: String): List<ImpRow> {
    val tokens = raw.replace("\n", " ").split(Regex("\\s+")).filter { it.isNotBlank() }
    val amountRe = Regex("^[0-9]{1,3}(?:,[0-9]{3})+$")
    val dayRe = Regex("^(?:[0-9]{1,2}\\.)?([0-9]{1,2})$")
    fun asDay(s: String): Int? { val m = dayRe.find(s) ?: return null; val d = m.groupValues[1].toIntOrNull() ?: return null; return if (d in 1..31) d else null }
    val out = mutableListOf<ImpRow>()
    val seen = HashSet<Int>()
    var i = 0
    while (i < tokens.size) {
        val d = asDay(tokens[i])
        if (d != null && !amountRe.matches(tokens[i])) {
            val amts = mutableListOf<Int>()
            var j = i + 1
            while (j < tokens.size && amountRe.matches(tokens[j])) { tokens[j].replace(",", "").toIntOrNull()?.let { amts.add(it) }; j++ }
            if (amts.isNotEmpty() && seen.add(d)) {
                out.add(ImpRow(d, (amts.getOrNull(0) ?: 0).toString(), (amts.getOrNull(1) ?: 0).let { if (it == 0) "" else it.toString() }))
            }
            i = if (j > i + 1) j else i + 1
        } else i++
    }
    return out.sortedBy { it.day }
}
