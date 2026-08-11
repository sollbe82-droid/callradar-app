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
        val userId = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getString("user_id", "") ?: ""
        val mode = intent.getStringExtra("mode") ?: "both"
        setContent { ImportScreen(userId, mode) { finish() } }
    }
}

// 미리보기 행: 일(day) + 수입 + 지출 (문자열로 편집) + [v54] LPG 리터(수량, 소수2자리)
private data class ImpRow(var day: Int, var income: String, var expense: String, var liters: Double = 0.0)

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
    var importMode by remember { mutableStateOf(initialMode) }
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
    fun runOcr(uri: Uri, accumulate: Boolean = false, onComplete: () -> Unit = {}) {
        busy = true; status = "글자를 읽는 중…"
        try {
            val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            val bitmap: android.graphics.Bitmap? = try {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    val src = android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
                    android.graphics.ImageDecoder.decodeBitmap(src) { d, _, _ -> d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE; d.setTargetSampleSize(2) }
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
            // 0/90/270/180 네 방향 OCR → 품질 점수 최고 방향 채택 (옆으로 찍힌 영수증 자동 인식)
            val rots = intArrayOf(0, 90, 270, 180)
            val texts = arrayOfNulls<String>(rots.size)
            fun tryRot(i: Int) {
                if (i >= rots.size) { handleOcrText(texts.filterNotNull().maxByOrNull { scoreText(it) } ?: "", accumulate, onComplete); return }
                status = "글자를 읽는 중… (${i + 1}/${rots.size})"
                recognizer.process(InputImage.fromBitmap(bitmap, rots[i]))
                    .addOnSuccessListener { texts[i] = it.text; tryRot(i + 1) }
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
                if (i >= uris.size) { busy = false; status = "${rows.size}건 인식 완료 — 확인 후 가져오기를 누르세요."; return }
                status = "여러 장 읽는 중… (${i + 1}/${uris.size})"
                runOcr(uris[i], accumulate = true) { i++; next() }
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
            Text(when (importMode) { "expense" -> "➖ 지출 가져오기"; "income" -> "➕ 수입 가져오기"; else -> "실적 가져오기" }, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("닫기", color = muted) }
        }
        Text("영수증·전표·화면을 📷카메라·🖼갤러리(여러장)·📄파일로 가져와요. 인식 후 표에서 확인·수정하고 넣습니다.", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        // [v25] 수입/지출 모드 — 컨텍스트로 확정(income/expense 추측 제거)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            listOf("income" to "➕ 수입", "expense" to "➖ 지출", "both" to "자동").forEach { (v, lbl) ->
                FilterChip(selected = importMode == v, onClick = {
                    importMode = v
                    // 이미 인식된 행을 새 모드로 즉시 재분류(다시 찍을 필요 없음)
                    rows = rows.map { r ->
                        val t = (r.income.toIntOrNull() ?: 0) + (r.expense.toIntOrNull() ?: 0)
                        when (v) {
                            "expense" -> r.copy(income = "", expense = if (t > 0) t.toString() else "")
                            "income" -> r.copy(income = if (t > 0) t.toString() else "", expense = "")
                            else -> r
                        }
                    }
                }, label = { Text(lbl, fontSize = 12.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
            }
        }

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

        if (status.isNotEmpty()) Text(status, fontSize = 12.sp, color = if (rows.isEmpty() && !busy) red else green, modifier = Modifier.padding(top = 10.dp))

        // [v19] 인식이 안 돼도 항상 표를 보여줘서 '직접 추가'로 넣을 수 있게 (기록 안됨 방지)
        run {
            Spacer(Modifier.height(12.dp))
            Text("표에서 날짜(일)·수입·지출을 확인·수정하세요. 위의 연·월이 이 표의 기준이에요.", fontSize = 11.sp, color = muted, modifier = Modifier.padding(bottom = 6.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("${month}월 일", fontSize = 12.sp, color = muted, modifier = Modifier.width(56.dp))
                if (importMode != "expense") Text("수입", fontSize = 12.sp, color = muted, modifier = Modifier.weight(1f))   // [v53] 지출 컨텍스트에선 수입칸 숨김
                Text("지출", fontSize = 12.sp, color = muted, modifier = Modifier.weight(1f))
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
                            val json = JSONObject().apply { put("user_id", userId); put("records", JSONArray(payload)) }
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
