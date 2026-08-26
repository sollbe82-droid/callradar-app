package com.callradar.app.screen

// ===== DailySettlementScreen v2 (2026-07-14) — 5b: OCR + 교차검증 + 회사전송 =====
// 전표 사진 → Cloudinary 업로드 + ML Kit OCR로 총합계 추출 → 수동입력과 교차검증
// 일치하면 회사 전송(daily-settlement). 가스 영수증은 선택.

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.callradar.app.CloudinaryUploader
import com.callradar.app.ReceiptOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DailySettlementScreen(userId: String, onClose: () -> Unit) {
    // 휴대폰 뒤로가기 → 앱 종료 대신 이전 화면으로
    BackHandler { onClose() }
    val bg = AppTheme.bg; val card = AppTheme.card; val accent = Color(0xFFF59E0B)
    val green = Color(0xFF10B981); val red = Color(0xFFEF4444); val muted = Color(0xFF6B7280)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val SERVER_URL = Config.SERVER_URL

    var meterUrl by remember { mutableStateOf<String?>(null) }
    var gasUrl by remember { mutableStateOf<String?>(null) }
    var gasAmount by remember { mutableStateOf<Int?>(null) }   // 가스 영수증 OCR 금액
    var gasLiters by remember { mutableStateOf<Double?>(null) } // 수량
    var gasUnit by remember { mutableStateOf("L") } // 단위 L/KWh
    var meterUploading by remember { mutableStateOf(false) }
    var gasUploading by remember { mutableStateOf(false) }
    var ocrTotal by remember { mutableStateOf<Int?>(null) }   // OCR로 읽은 총합계
    var ocrRange by remember { mutableStateOf<String?>(null) }
    var parsedTrips by remember { mutableStateOf<List<com.callradar.app.TripLine>>(emptyList()) } // 전표에서 파싱한 개별 운행
    var tripsAdded by remember { mutableStateOf(false) }       // 기록 추가 완료 여부
    var addingTrips by remember { mutableStateOf(false) }      // 추가 진행중
    var manualRevenue by remember { mutableStateOf("") }       // 수동입력 총매출
    var statusMsg by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sentDone by remember { mutableStateOf(false) }
    // 마감 날짜 (기본 오늘, 어제/그제 소급 제출 가능)
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") } }
    fun dateStr(offsetDays: Int): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))
        c.add(Calendar.DAY_OF_MONTH, offsetDays)
        return dateFmt.format(c.time)
    }
    var selectedDate by remember { mutableStateOf(dateStr(0)) }  // YYYY-MM-DD
    // ⑩ 전표→GPS 매칭 (금액 빈 GPS 운행에 전표 금액 채우기)
    var gpsUnpriced by remember { mutableStateOf<List<Triple<Int, String, String>>>(emptyList()) } // id, 시간, 출발→도착
    var matchInputs by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var matchLoading by remember { mutableStateOf(false) }
    var matchBusy by remember { mutableStateOf(false) }
    var matchDone by remember { mutableStateOf(false) }

    /* ── [v93] 이미 저장한 마감을 먼저 불러온다 ──────────────────────────────
     *
     *  이 화면은 열 때마다 빈 칸으로 시작했다. 그래서 어제 날짜를 다시 열어
     *  매출만 고쳐 저장하면, 빈 채로 있던 사진·가스비가 그대로 서버에 올라가
     *  어제 올려둔 전표 사진과 LPG 지출을 지웠다. 기사는 매출만 고쳤다고 생각한다.
     *
     *  근본 해결은 '고치러 왔으면 원래 값이 보이는 것'이다. 서버 방어(빈 값으로 안 지움)는
     *  구버전 앱을 위한 그물이지, 이걸 대신하지 않는다.
     *
     *  날짜를 바꿀 때마다 다시 불러온다. 새 사진을 이미 찍어둔 상태면 덮지 않는다.
     */
    var gasEdited by remember { mutableStateOf(false) }   // 기사가 가스 칸을 실제로 만졌나(0을 '고른 값'으로 볼 근거)
    var loadedDate by remember { mutableStateOf("") }
    LaunchedEffect(selectedDate, userId) {
        if (userId.isBlank()) return@LaunchedEffect
        try {
            val month = selectedDate.substring(0, 7)
            val raw = withContext(Dispatchers.IO) {
                val conn = (URL("$SERVER_URL/api/daily-settlement/$userId?month=$month").openConnection().apply {
                    com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") }
                } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 20000 }
                conn.inputStream.bufferedReader().readText()
            }
            val arr = org.json.JSONArray(raw)
            var found: JSONObject? = null
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("work_date", "").startsWith(selectedDate)) { found = o; break }
            }
            if (found != null) {
                // 화면에 이미 입력·촬영한 게 있으면 그게 우선이다. 빈 칸만 채운다.
                if (meterUrl.isNullOrBlank()) found.optString("meter_photo_url", "").takeIf { it.isNotBlank() }?.let { meterUrl = it }
                if (gasUrl.isNullOrBlank())   found.optString("gas_photo_url", "").takeIf { it.isNotBlank() }?.let { gasUrl = it }
                if (manualRevenue.isBlank())  found.optInt("total_revenue", 0).takeIf { it > 0 }?.let { manualRevenue = it.toString() }
                if (gasAmount == null)        found.optInt("gas_cost", 0).takeIf { it > 0 }?.let { gasAmount = it }
                if (gasLiters == null)        found.optDouble("gas_liters", 0.0).takeIf { it > 0 }?.let { gasLiters = it }
                loadedDate = selectedDate
                if (statusMsg.isBlank()) statusMsg = "이 날짜에 저장된 마감을 불러왔습니다"
            } else loadedDate = selectedDate
        } catch (e: Exception) {
            // 못 불러왔으면 조용히 넘어간다. 다만 저장 시 서버가 빈 값으로 안 덮도록 막아둔 게 있다.
        }
    }

    // 전표: 업로드 + OCR 동시
    val meterPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            meterUploading = true; statusMsg = "전표 분석 중..."
            // OCR
            ReceiptOcr.scan(context, uri) { result ->
                ocrTotal = result.total
                ocrRange = result.collectRange
                parsedTrips = result.trips
                tripsAdded = false
                if (result.total != null) {
                    statusMsg = "전표에서 총합계 ${String.format("%,d", result.total)}원 읽음"
                    if (manualRevenue.isEmpty()) manualRevenue = result.total.toString()
                } else {
                    statusMsg = "총합계를 못 읽었어요 — 직접 입력해주세요"
                }
            }
            // [보안 2026-08-27] Cloudinary 업로드 제거.
            //  전표 사진을 미국 업체(Cloudinary)에 올려 보관하고 있었는데,
            //  개인정보처리방침 수탁자 표에 없었고 삭제 경로도 없었으며
            //  unsigned 업로드라 URL만 알면 누구나 열리는 상태였다.
            //  **글자 읽기는 폰 안에서 하는 일이라 그대로 살아 있다.** 보관만 없앤다.
            meterUploading = false
        }
    }
    val gasPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            gasUploading = true
            // 가스 영수증 OCR (금액 + 리터)
            ReceiptOcr.scanGas(context, uri) { result ->
                gasAmount = result.gasAmount
                gasLiters = result.liters
                gasUnit = result.unit
                gasEdited = true   // [v93] 기사가 영수증을 올렸다 = 가스 값을 직접 정했다
            }
            // [보안 2026-08-27] Cloudinary 업로드 제거 (위 전표와 같은 이유).
            gasUploading = false
        }
    }

    // 교차검증 결과
    val manualInt = manualRevenue.filter { it.isDigit() }.toIntOrNull()
    val verified = ocrTotal != null && manualInt != null && ocrTotal == manualInt
    val mismatch = ocrTotal != null && manualInt != null && ocrTotal != manualInt
    // 회사 소속 여부 (법인이면 회사 제출, 아니면 개인 저장)
    val prefs = context.getSharedPreferences("callradar_prefs", android.content.Context.MODE_PRIVATE)
    val isCompany = prefs.getString("driver_type", "personal") == "corporate"
    val submitLabel = if (isCompany) "회사 제출" else "저장"
    // 회사 정산단가(lpg_price) 반영한 실부담 가스비. 단가 있고 리터 있으면 리터×단가, 없으면 영수증 원가
    val settlePrice = prefs.getInt("lpg_price", 0)
    val gasRealCost: Int? = when {
        gasAmount == null -> null
        settlePrice > 0 && gasLiters != null && gasLiters!! > 0 -> Math.round(gasLiters!! * settlePrice).toInt()
        else -> gasAmount
    }

    // 전표에서 파싱한 개별 운행들을 기록에 추가 (경우1: 새 운행 생성)
    // ⑩ 그날 금액 빈 GPS 운행 불러오기
    fun loadGpsUnpriced() {
        matchLoading = true; matchDone = false
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/trips/$userId?date=$selectedDate&limit=100").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }; conn.inputStream.bufferedReader().readText() }
                val arr = org.json.JSONArray(raw)
                val list = mutableListOf<Triple<Int, String, String>>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val src = o.optString("source", "")
                    val fareNull = o.isNull("fare") || o.optInt("fare", 0) <= 0
                    if (src == "gps" && fareNull) {
                        val rawTime = o.optString("started_at", "")
                        val timeLabel = try { val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); sdf.timeZone = TimeZone.getTimeZone("UTC"); val d = sdf.parse(rawTime); val out = SimpleDateFormat("HH:mm", Locale.KOREA); out.timeZone = TimeZone.getTimeZone("Asia/Seoul"); out.format(d!!) } catch (e: Exception) { "--:--" }
                        val route = (o.optString("origin", "").ifEmpty { "출발?" }) + "→" + (o.optString("destination", "").ifEmpty { "도착?" })
                        list.add(Triple(o.optInt("id"), timeLabel, route))
                    }
                }
                gpsUnpriced = list.sortedBy { it.second }
                matchInputs = emptyMap()
                statusMsg = if (list.isEmpty()) "이 날짜에 금액 빈 GPS 운행이 없어요" else "금액 빈 GPS 운행 ${list.size}건 발견"
            } catch (e: Exception) { statusMsg = "GPS 운행 불러오기 실패" }
            matchLoading = false
        }
    }
    // ⑩ 입력한 금액을 각 GPS 운행에 채우기
    fun fillGpsFares() {
        if (matchBusy) return
        matchBusy = true; statusMsg = "금액 채우는 중..."
        scope.launch {
            var ok = 0
            withContext(Dispatchers.IO) {
                for ((id, amtStr) in matchInputs) {
                    val amt = amtStr.filter { it.isDigit() }.toIntOrNull() ?: 0
                    if (amt <= 0) continue
                    try {
                        val json = org.json.JSONObject().apply { put("fare", amt) }
                        val conn = (URL("$SERVER_URL/api/trips/$id/fare").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 10000; readTimeout = 10000 }
                        conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                        if (conn.responseCode in 200..299) ok++
                    } catch (e: Exception) { }
                }
            }
            matchBusy = false; matchDone = true; statusMsg = "$ok 건 금액 채움 완료 ✅"
            loadGpsUnpriced()
        }
    }
    fun addTripsFromReceipt() {
        if (parsedTrips.isEmpty() || addingTrips) return
        addingTrips = true; statusMsg = "운행 ${parsedTrips.size}건 기록에 추가 중..."
        scope.launch {
            val year = selectedDate.substring(0, 4)  // YYYY (마감 날짜 기준 연도)
            var success = 0
            withContext(Dispatchers.IO) {
                for (t in parsedTrips) {
                    try {
                        // 플랫폼 소계는 개별 시간이 없음 → 마감 날짜 정오로 기록 (통합 기록용)
                        val startedAt = if (t.time.isNotEmpty() && t.date.isNotEmpty()) {
                            val mmdd = t.date.replace("/", "-")
                            "$year-$mmdd" + "T" + t.time + ":00+09:00"
                        } else {
                            selectedDate + "T12:00:00+09:00"
                        }
                        val json = JSONObject().apply {
                            put("user_id", userId)
                            put("originName", "")
                            put("destName", "미정")          // 서버 destName 필수 → "미정"
                            put("platform", t.platform)
                            put("fare", t.fare)
                            put("payment_type", t.paymentType)
                            put("started_at", startedAt)
                            put("source", "전표")
                        }
                        val conn = (URL("$SERVER_URL/api/trips/manual").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                            requestMethod = "POST"
                            setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            doOutput = true; connectTimeout = 10000; readTimeout = 10000
                        }
                        conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.flush() }
                        if (conn.responseCode in 200..299) success++
                    } catch (e: Exception) { /* 개별 실패는 건너뜀 */ }
                }
            }
            addingTrips = false
            if (success == parsedTrips.size) { tripsAdded = true; statusMsg = "운행 ${success}건 기록에 추가됨 ✅" }
            else statusMsg = "운행 ${success}/${parsedTrips.size}건 추가됨 (일부 실패)"
        }
    }

    // 회사 전송
    fun sendToCompany() {
        // 매출 또는 가스비 중 하나라도 있으면 저장 (가스만 기록도 허용)
        val rev = manualInt ?: 0
        if (rev == 0 && (gasAmount ?: 0) == 0) return
        sending = true; statusMsg = if (isCompany) "회사로 전송 중..." else "저장 중..."
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val json = JSONObject().apply {
                        put("user_id", userId)
                        put("work_date", selectedDate)
                        put("total_revenue", rev)
                        put("meter_photo_url", meterUrl ?: "")
                        put("gas_photo_url", gasUrl ?: "")
                        put("gas_cost", gasRealCost ?: 0)
                        put("gas_liters", gasLiters ?: 0.0)
                        // [v93] 이번에 가스 영수증을 실제로 올렸나. 서버가 '0원'을 고른 값으로 볼지
                        //  화면이 비어 있던 것으로 볼지 가르는 유일한 근거다(서버 주석 참고).
                        put("gas_edited", gasEdited)
                        put("ocr_revenue", ocrTotal ?: 0)
                        put("verified", verified)
                        put("sent_to_company", true)
                    }
                    val conn = (URL("$SERVER_URL/api/daily-settlement").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        doOutput = true; connectTimeout = 10000; readTimeout = 10000
                    }
                    conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.flush() }
                    val settlementOk = conn.responseCode in 200..299
                    // [v19] 회사제출과 동시에 '그 날짜 기록'에도 저장 (멱등 imp_{유저}_{날짜}).
                    // OCR이 개별 운행을 못 뽑아도, 이 한 줄로 기록 탭·월별 달력에 그날 매출이 뜬다.
                    if (rev > 0) {
                        try {
                            val recJson = JSONObject().apply {
                                put("user_id", userId)
                                put("records", org.json.JSONArray().apply {
                                    put(JSONObject().apply { put("date", selectedDate); put("income", rev); put("memo", "일일마감 매출") })
                                })
                            }
                            val c2 = (URL("$SERVER_URL/api/import/bulk").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                                requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8")
                                doOutput = true; connectTimeout = 10000; readTimeout = 10000
                            }
                            c2.outputStream.use { it.write(recJson.toString().toByteArray(Charsets.UTF_8)); it.flush() }
                            c2.responseCode
                        } catch (e: Exception) { }
                    }
                    settlementOk
                } catch (e: Exception) { false }
            }
            sending = false
            if (ok) { sentDone = true; statusMsg = (if (isCompany) "회사 전송 완료 ✅" else "저장 완료 ✅") + "  ·  $selectedDate 기록에 반영됨" }
            else statusMsg = if (isCompany) "전송 실패 ❌ 다시 시도해주세요" else "저장 실패 ❌ 다시 시도해주세요"
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("일일 마감", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
            TextButton(onClick = onClose) { Text("닫기", color = muted) }
        }
        Text("전표 사진을 올리면 총매출을 읽어 검증하고 회사에 제출해요", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))

        // ⓪ 마감 날짜 선택 (오늘/어제/그제 소급)
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("마감 날짜", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                Text("못 보낸 날은 날짜를 바꿔 제출하세요", fontSize = 11.sp, color = muted, modifier = Modifier.padding(bottom = 10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("오늘" to 0, "어제" to -1, "그제" to -2).forEach { (label, offset) ->
                        val d = dateStr(offset)
                        val isSel = selectedDate == d
                        Button(
                            onClick = { selectedDate = d },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isSel) accent else Color(0xFF374151)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text(label, color = if (isSel) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text("선택: $selectedDate", fontSize = 12.sp, color = accent, modifier = Modifier.padding(top = 10.dp))
            }
        }

        // ① 전표
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("① 미터기 일일전표 (필수)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                Text("총합계가 자동으로 읽혀요", fontSize = 11.sp, color = muted, modifier = Modifier.padding(bottom = 10.dp))
                Button(onClick = { meterPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = if (meterUrl != null) green else accent), shape = RoundedCornerShape(10.dp)) {
                    Text(if (meterUploading) "분석 중..." else if (meterUrl != null) "전표 완료 ✅" else "전표 사진 선택", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                ocrRange?.let { Text("집계 시간: $it", fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 8.dp)) }
            }
        }

        // ② 총매출 (OCR 자동 + 수동 확인)
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("② 총매출 확인", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                ocrTotal?.let { Text("전표 인식값: ${String.format("%,d", it)}원", fontSize = 12.sp, color = green, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) }
                // 검증 결과에 따라 테두리 색 (초록=일치, 빨강=불일치, 기본=검증전)
                val borderColor = when { verified -> green; mismatch -> red; else -> accent }
                val unfocusedBorder = when { verified -> green; mismatch -> red; else -> Color(0xFF374151) }
                OutlinedTextField(
                    value = manualRevenue,
                    onValueChange = { v -> manualRevenue = v.filter { it.isDigit() } },
                    label = { Text("총매출 (원)", color = muted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = borderColor, unfocusedBorderColor = unfocusedBorder, focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text)
                )
                // 교차검증 표시
                if (verified) {
                    Text("✅ 전표와 일치 — 검증 완료", fontSize = 12.sp, color = green, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                } else if (mismatch) {
                    Text("⚠️ 전표(${String.format("%,d", ocrTotal!!)})와 입력값(${String.format("%,d", manualInt!!)})이 달라요", fontSize = 12.sp, color = red, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        // ②-b 전표에서 발견한 개별 운행 → 기록에 추가 (경우1: 새 운행 생성)
        if (parsedTrips.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("전표에서 운행 ${parsedTrips.size}건 발견", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                    Text("플랫폼별 합산 금액으로 기록돼요 (출발·목적지는 '미정')", fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                    parsedTrips.forEach { t ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(t.platform, fontSize = 12.sp, color = muted)
                            Text("${String.format("%,d", t.fare)}원", fontSize = 12.sp, color = AppTheme.text, fontWeight = FontWeight.Bold)
                        }
                    }
                    Button(
                        onClick = { addTripsFromReceipt() },
                        enabled = !addingTrips && !tripsAdded,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (tripsAdded) green else accent),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (tripsAdded) "기록에 추가됨 ✅" else if (addingTrips) "추가 중..." else "기록에 ${parsedTrips.size}건 추가",
                            color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ②-c ⑩ GPS 운행에 전표 금액 채우기 (경우2: 기존 GPS 운행에 금액 매칭)
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("GPS 운행에 전표 금액 채우기", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                Text("위치·시간만 있고 금액이 빈 GPS 운행에 전표 금액을 채워요 (시간·경로로 어느 운행인지 확인)", fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                Button(onClick = { loadGpsUnpriced() }, enabled = !matchLoading, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151)), shape = RoundedCornerShape(10.dp)) {
                    Text(if (matchLoading) "불러오는 중..." else "이 날짜 GPS 운행 불러오기", color = AppTheme.text, fontWeight = FontWeight.Bold)
                }
                if (gpsUnpriced.isNotEmpty()) {
                    val target = (ocrTotal ?: manualInt) ?: 0
                    val entered = matchInputs.values.sumOf { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
                    val remaining = target - entered
                    Spacer(Modifier.height(10.dp))
                    if (target > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("전표 총액 ${String.format("%,d", target)}원", fontSize = 12.sp, color = muted)
                            Text(if (remaining == 0) "딱 맞음 ✅" else if (remaining > 0) "남은 ${String.format("%,d", remaining)}원" else "초과 ${String.format("%,d", -remaining)}원", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (remaining == 0) green else if (remaining > 0) accent else red)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    gpsUnpriced.forEach { row ->
                        val id = row.first; val timeLabel = row.second; val route = row.third
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(timeLabel, fontSize = 13.sp, color = accent, fontWeight = FontWeight.Bold)
                                Text(route, fontSize = 10.sp, color = muted, maxLines = 1)
                            }
                            OutlinedTextField(value = matchInputs[id] ?: "", onValueChange = { v -> matchInputs = matchInputs.toMutableMap().apply { put(id, v.filter { c -> c.isDigit() }) } }, label = { Text("금액", color = muted, fontSize = 10.sp) }, modifier = Modifier.width(120.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                        }
                    }
                    if (gpsUnpriced.size == 1 && target > 0) {
                        Button(onClick = { matchInputs = mapOf(gpsUnpriced[0].first to target.toString()) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), colors = ButtonDefaults.buttonColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(10.dp)) {
                            Text("운행 1건 → 전표 총액 ${String.format("%,d", target)}원 자동 채우기", color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                    Button(onClick = { fillGpsFares() }, enabled = !matchBusy && matchInputs.values.any { (it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) > 0 }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = if (matchDone) green else accent), shape = RoundedCornerShape(10.dp)) {
                        Text(if (matchDone) "채움 완료 ✅" else if (matchBusy) "저장 중..." else "입력한 금액 저장", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ③ 가스 영수증 (선택)
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("③ 가스/전기 영수증 (선택)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                Text("직접 주유·충전한 경우만 (LPG 영수증·전기 충전 문자)", fontSize = 11.sp, color = muted, modifier = Modifier.padding(bottom = 10.dp))
                Button(onClick = { gasPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = if (gasUrl != null) green else Color(0xFF374151)), shape = RoundedCornerShape(10.dp)) {
                    Text(if (gasUploading) "업로드 중..." else if (gasUrl != null) "완료 ✅" else "가스/전기 영수증 선택 (선택사항)", color = if (gasUrl != null) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                }
                // OCR로 읽은 가스 금액·리터 표시
                gasAmount?.let { raw ->
                    Text("영수증 금액: ${String.format("%,d", raw)}원" + (gasLiters?.let { l -> "  ($l $gasUnit)" } ?: ""),
                        fontSize = 13.sp, color = AppTheme.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                    if (settlePrice > 0 && gasLiters != null && gasLiters!! > 0) {
                        Text("실부담 연료비: ${String.format("%,d", gasRealCost ?: raw)}원  (정산단가 ${settlePrice}원 적용)",
                            fontSize = 13.sp, color = green, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        Text("지출로 자동 기록돼요", fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
                    } else {
                        Text("지출로 자동 기록돼요 (정산단가 설정 시 실부담 계산)", fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }

        // 상태 메시지
        if (statusMsg.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(10.dp)) {
                Text(statusMsg, fontSize = 13.sp, color = if (statusMsg.contains("✅")) green else if (statusMsg.contains("❌")) red else muted, modifier = Modifier.padding(14.dp))
            }
        }

        // ④ 회사 전송 버튼
        val canSend = (manualInt != null || (gasAmount ?: 0) > 0) && !sending && !sentDone
        Button(
            onClick = { sendToCompany() },
            enabled = canSend,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (verified) green else accent, disabledContainerColor = Color(0xFF374151)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                if (sentDone) "완료 ✅" else if (sending) "저장 중..." else if (verified) "검증 완료 · $submitLabel" else submitLabel,
                color = if (canSend || sentDone) Color.Black else muted, fontWeight = FontWeight.Bold, fontSize = 15.sp
            )
        }
        if (!verified && mismatch) {
            Text("전표와 입력값이 다르지만 제출은 가능해요 (검증 안 됨 상태로 전송)", fontSize = 10.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
