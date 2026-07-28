// ===== RecordsScreen v5 (2026-07-08) =====
// v7: 현금 별도집계(현금=기사 실수입, 카드/플랫폼과 분리 표시) + 운행추가 source=manual + 리셋버그(카드→card) 수정
// v5: 월별탭 정산 카드 추가 - 월매출/LPG지출(리터)/부가세환급 예상(환급률 설정시)
// v4: LPG 지출 리터(L) 입력 → 단가 자동곱 금액계산, 단가 prefs 저장
// v3: 자동 새로고침 - 화면복귀시 즉시갱신 + 운행기록탭 15초 백업
// v2: 제보탭 개편 - 왜 제보하나 설명 (실시간콜지도/AI핫존추천/정보원배지+포인트)
package com.callradar.app.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

private const val SERVER_URL = Config.SERVER_URL

// [v22] 다이얼로그 키보드 가림 완전수정: AlertDialog는 별도 윈도우라 imePadding()이 IME inset을 못 받아 무시됨.
// 다이얼로그 윈도우에 decorFitsSystemWindows=false를 걸면 imePadding()이 실제 작동 → 저장 버튼이 키보드 위로 올라옴.
@Composable
private fun DialogImeFix() {
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(view) {
        var p: android.view.ViewParent? = view.parent
        var win: android.view.Window? = null
        while (p != null) {
            if (p is androidx.compose.ui.window.DialogWindowProvider) { win = p.window; break }
            p = p.parent
        }
        win?.let {
            it.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, false)
        }
    }
}

// 운행/콜 공유 — 브랜딩 문구 + (등록된 오픈방이 있으면) 복사+방 열기 1터치, 없으면 공유시트
private fun shareTrip(context: android.content.Context, origin: String, dest: String, hour: String, minute: String, fare: String) {
    val prefs = context.getSharedPreferences("callradar_prefs", android.content.Context.MODE_PRIVATE)
    val room = (prefs.getString("share_room_url", "") ?: "").trim()
    val promo = (prefs.getString("share_promo", "") ?: "").trim()
    val time = if (hour.isNotEmpty()) hour.filter { it.isDigit() }.padStart(2, '0') + ":" + (minute.ifEmpty { "00" }).filter { it.isDigit() }.padStart(2, '0') + " " else ""
    val f = fare.filter { it.isDigit() }.toIntOrNull()
    val fareStr = if (f != null && f > 0) " · " + String.format("%,d", f) + "원" else ""
    // ★모든 공유문구에 브랜드 노출 (바이럴): 던지는 곳마다 콜레이더가 보임
    val brand = "\n\n📻 콜레이더 — 택시기사 수입관리·실시간 콜·공항정보" + (if (promo.isNotEmpty()) "\n$promo" else "")
    val text = "🚕 " + time + (origin.ifEmpty { "출발" }) + " → " + (dest.ifEmpty { "도착" }) + fareStr + brand
    if (room.isNotEmpty()) {
        // 1터치: 문구 자동복사 + 등록한 오픈방 바로 열기 (방에서 꾹→붙여넣기)
        try {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("콜레이더 공유", text))
        } catch (e: Exception) {}
        android.widget.Toast.makeText(context, "공유 문구 복사됨 — 방에서 꾹 눌러 붙여넣기 하세요", android.widget.Toast.LENGTH_LONG).show()
        try {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(room)).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "오픈방 주소가 올바르지 않아요 (설정에서 확인)", android.widget.Toast.LENGTH_SHORT).show()
        }
    } else {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "공유하기").apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
    }
}

data class TripRecord(val id: Int, val origin: String, val destination: String, val fare: Int, val platform: String, val time: String, val date: String, val paymentType: String = "auto", val endTime: String = "", val rawDate: String = "")
data class DailyRecord(val date: String, val tripCount: Int, val totalFare: Int, val cardFare: Int = 0, val cashFare: Int = 0, val expense: Int = 0)
data class ExpenseRecord(val id: Int, val category: String, val amount: Int, val expenseType: String, val memo: String, val date: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(userId: String, onOpenDailySettlement: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    val bg = AppTheme.bg; val card = AppTheme.card; val accent = Color(0xFFF59E0B); val green = Color(0xFF10B981); val red = Color(0xFFEF4444); val muted = Color(0xFF6B7280)
    var selectedTab by remember { mutableStateOf(0) }
    var trips by remember { mutableStateOf<List<TripRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var dateFilter by remember { mutableStateOf("오늘") }
    var customDate by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showEditDatePicker by remember { mutableStateOf(false) }  // [v18] 편집 날짜 지정
    var editingTrip by remember { mutableStateOf<TripRecord?>(null) }
    var editDest by remember { mutableStateOf("") }
    var editOrigin by remember { mutableStateOf("") }
    var editFare by remember { mutableStateOf("") }
    var editHour by remember { mutableStateOf("") }
    var editDate by remember { mutableStateOf("") }  // [v6] 수정 시 원래 트립 날짜 유지
    var editMinute by remember { mutableStateOf("") }
    var editPaymentType by remember { mutableStateOf("auto") }
    var editPlatform by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showQuickFare by remember { mutableStateOf(false) }
    var quickFareTrip by remember { mutableStateOf<TripRecord?>(null) }
    var quickFareInput by remember { mutableStateOf("") }
    var quickFarePlatform by remember { mutableStateOf("") }  // [v18] 금액 입력 시 플랫폼 지정
    var deletingTrip by remember { mutableStateOf<TripRecord?>(null) }
    var showManualDialog by remember { mutableStateOf(false) }
    var isReportMode by remember { mutableStateOf(false) }
    // [v6] 추가/지출 다이얼로그에서 쓸 날짜 (기본=오늘, 지난 날 기록 입력 가능)
    val todayStr = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date())
    }
    var manualDate by remember { mutableStateOf(todayStr) }
    var expenseDate by remember { mutableStateOf(todayStr) }
    var showManualDatePicker by remember { mutableStateOf(false) }
    var showExpenseDatePicker by remember { mutableStateOf(false) }
    var manualOrigin by remember { mutableStateOf("") }
    var manualDest by remember { mutableStateOf("") }
    var manualFare by remember { mutableStateOf("") }
    var manualTip by remember { mutableStateOf("") }
    var manualHour by remember { mutableStateOf("") }
    var manualPlatform by remember { mutableStateOf("길빵/예약") }
    var manualMinute by remember { mutableStateOf("") }
    var manualPaymentType by remember { mutableStateOf("카드") }
    var expenses by remember { mutableStateOf<List<ExpenseRecord>>(emptyList()) }
    var showExpenseDialog by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val expensePrefs = ctx.getSharedPreferences("callradar_prefs", android.content.Context.MODE_PRIVATE)
    val dayStartHour = expensePrefs.getInt("day_start_hour", 0)   // [v19] 영업일 시작(야간·일차 기사)
    var expenseCategory by remember { mutableStateOf("LPG") }
    var expenseAmount by remember { mutableStateOf("") }
    var expenseLiters by remember { mutableStateOf("") }
    var lpgPrice by remember { mutableStateOf(expensePrefs.getInt("lpg_price", 1050).toString()) }
    var lpgDiscount by remember { mutableStateOf(expensePrefs.getInt("lpg_discount", 0).toString()) }   // [v19] 가스비 리터당 할인(원/L)
    var expenseType by remember { mutableStateOf("business") }
    var expenseMemo by remember { mutableStateOf("") }
    var showDeleteExpense by remember { mutableStateOf(false) }
    var deletingExpense by remember { mutableStateOf<ExpenseRecord?>(null) }
    val scope = rememberCoroutineScope()

    val focusManager = LocalFocusManager.current
    fun getFilterDate(): String? {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA); sdf.timeZone = TimeZone.getTimeZone("Asia/Seoul")
        return when (dateFilter) { "오늘" -> sdf.format(Date()); "어제" -> { val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")); cal.add(Calendar.DAY_OF_MONTH, -1); sdf.format(cal.time) }; "날짜선택" -> customDate.ifEmpty { null }; else -> null }
    }

    fun loadData() {
        scope.launch {
            try {
                isLoading = true; val filterDate = getFilterDate()
                val url = if (filterDate != null) "$SERVER_URL/api/trips/$userId?date=$filterDate&limit=100&dayStart=$dayStartHour" else "$SERVER_URL/api/trips/$userId?limit=100"
                val tripsResponse = withContext(Dispatchers.IO) { val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }
                val tripsJson = JSONArray(tripsResponse); val tripList = mutableListOf<TripRecord>()
                for (i in 0 until tripsJson.length()) {
                    val obj = tripsJson.getJSONObject(i); val rawTime = obj.optString("started_at", "")
                    val formattedTime = try { val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); sdf.timeZone = TimeZone.getTimeZone("UTC"); val date = sdf.parse(rawTime); val out = SimpleDateFormat("HH:mm", Locale.KOREA); out.timeZone = TimeZone.getTimeZone("Asia/Seoul"); out.format(date!!) } catch (e: Exception) { "" }
                    val formattedDate = try { val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); sdf.timeZone = TimeZone.getTimeZone("UTC"); val date = sdf.parse(rawTime); val out = SimpleDateFormat("MM/dd (E)", Locale.KOREA); out.timeZone = TimeZone.getTimeZone("Asia/Seoul"); out.format(date!!) } catch (e: Exception) { "" }
                    val rawDateStr = try { val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); sdf.timeZone = TimeZone.getTimeZone("UTC"); val date = sdf.parse(rawTime); val out = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA); out.timeZone = TimeZone.getTimeZone("Asia/Seoul"); out.format(date!!) } catch (e: Exception) { "" }
                    val rawEndTime = obj.optString("ended_at", "")
                    val formattedEndTime = try { val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); sdf.timeZone = TimeZone.getTimeZone("UTC"); val date = sdf.parse(rawEndTime); val out = SimpleDateFormat("HH:mm", Locale.KOREA); out.timeZone = TimeZone.getTimeZone("Asia/Seoul"); out.format(date!!) } catch (e: Exception) { "" }
                    tripList.add(TripRecord(obj.optInt("id", 0), obj.optString("origin", ""), obj.optString("destination", "목적지 없음"), obj.optInt("fare", 0), obj.optString("platform", ""), formattedTime, formattedDate, obj.optString("payment_type", "auto"), formattedEndTime, rawDateStr))
                }
                trips = tripList; isLoading = false
            } catch (e: Exception) { isLoading = false }
        }
    }

    LaunchedEffect(Unit) { loadData() }
    LaunchedEffect(dateFilter, customDate) { loadData() }

    // [v6] 입력 중에는 자동 갱신 금지
    // 기존: ON_RESUME + 15초 주기로 무조건 loadData() → 기록 추가/수정 중 화면이 초기화돼 입력이 날아감
    // 변경: 다이얼로그가 하나라도 열려 있으면 갱신을 건너뛰고, 15초 폴링은 제거
    //       (실시간 갱신이 필요한 건 공항 탭이지 기록 탭이 아님)
    val isAnyDialogOpen = showManualDialog || showExpenseDialog || showEditDialog ||
        showQuickFare || showDeleteConfirm || showDatePicker

    val recLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(recLifecycleOwner, isAnyDialogOpen) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && !isAnyDialogOpen) { loadData() }
        }
        recLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { recLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // DatePicker
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(onDismissRequest = { showDatePicker = false },
            confirmButton = { Button(onClick = { datePickerState.selectedDateMillis?.let { millis -> val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA); sdf.timeZone = TimeZone.getTimeZone("Asia/Seoul"); customDate = sdf.format(Date(millis)); dateFilter = "날짜선택" }; showDatePicker = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("선택", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showDatePicker = false }) { Text("취소") } },
            colors = DatePickerDefaults.colors(containerColor = AppTheme.card)
        ) { DatePicker(state = datePickerState) }
    }

    // [v6] 운행 추가용 날짜 선택
    if (showManualDatePicker) {
        val st = rememberDatePickerState()
        DatePickerDialog(onDismissRequest = { showManualDatePicker = false },
            confirmButton = { Button(onClick = {
                st.selectedDateMillis?.let { millis ->
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
                    sdf.timeZone = TimeZone.getTimeZone("Asia/Seoul")
                    manualDate = sdf.format(Date(millis))
                }; showManualDatePicker = false
            }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("선택", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showManualDatePicker = false }) { Text("취소") } },
            colors = DatePickerDefaults.colors(containerColor = AppTheme.card)
        ) { DatePicker(state = st) }
    }

    // [v18] 운행 수정용 날짜 선택
    if (showEditDatePicker) {
        val st = rememberDatePickerState()
        DatePickerDialog(onDismissRequest = { showEditDatePicker = false },
            confirmButton = { Button(onClick = {
                st.selectedDateMillis?.let { millis ->
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
                    sdf.timeZone = TimeZone.getTimeZone("Asia/Seoul")
                    editDate = sdf.format(Date(millis))
                }; showEditDatePicker = false
            }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("선택", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showEditDatePicker = false }) { Text("취소") } },
            colors = DatePickerDefaults.colors(containerColor = AppTheme.card)
        ) { DatePicker(state = st) }
    }

    // [v6] 지출 추가용 날짜 선택
    if (showExpenseDatePicker) {
        val st = rememberDatePickerState()
        DatePickerDialog(onDismissRequest = { showExpenseDatePicker = false },
            confirmButton = { Button(onClick = {
                st.selectedDateMillis?.let { millis ->
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
                    sdf.timeZone = TimeZone.getTimeZone("Asia/Seoul")
                    expenseDate = sdf.format(Date(millis))
                }; showExpenseDatePicker = false
            }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("선택", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showExpenseDatePicker = false }) { Text("취소") } },
            colors = DatePickerDefaults.colors(containerColor = AppTheme.card)
        ) { DatePicker(state = st) }
    }

    // 수정 다이얼로그
    if (showEditDialog && editingTrip != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showEditDialog = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            DialogImeFix()
            BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              val scrollMaxH = (maxHeight - 150.dp).coerceIn(140.dp, 440.dp)
              Surface(shape = RoundedCornerShape(20.dp), color = AppTheme.card, modifier = Modifier.fillMaxWidth(0.94f)) {
              Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("운행 기록 수정", color = AppTheme.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Column(modifier = Modifier.heightIn(max = scrollMaxH).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("시간 수정", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                // [v18] 날짜 지정
                Text("날짜", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                OutlinedButton(onClick = { showEditDatePicker = true }, modifier = Modifier.fillMaxWidth(), border = androidx.compose.foundation.BorderStroke(1.dp, if (editDate != todayStr) accent else Color(0xFF374151))) {
                    Text(if (editDate == todayStr) "$editDate (오늘)" else editDate.ifEmpty { "날짜 선택" }, color = if (editDate != todayStr) accent else AppTheme.text, fontSize = 14.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(value = editHour, onValueChange = { v -> val f = v.filter { it.isDigit() }.take(2); if (f.isEmpty() || f.toInt() <= 23) editHour = f }, label = { Text("시", color = muted) }, modifier = Modifier.width(72.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                    Text(":", color = AppTheme.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = editMinute, onValueChange = { v -> val f = v.filter { it.isDigit() }.take(2); if (f.isEmpty() || f.toInt() <= 59) editMinute = f }, label = { Text("분", color = muted) }, modifier = Modifier.width(72.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                }
                OutlinedTextField(value = editOrigin, onValueChange = { editOrigin = it }, label = { Text("출발지", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                OutlinedTextField(value = editDest, onValueChange = { editDest = it }, label = { Text("목적지", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                OutlinedTextField(value = editFare, onValueChange = { editFare = it.filter { c -> c.isDigit() } }, label = { Text("금액 (원)", color = muted) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                Text("결제 방식", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("카드" to "card", "현금" to "cash", "자동결제" to "auto").forEach { (label, value) -> FilterChip(selected = editPaymentType == value, onClick = { editPaymentType = value }, label = { Text(label, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted)) } }
                Text("플랫폼", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("카카오T", "우버", "티머니고", "길빵/예약").forEach { p -> FilterChip(selected = editPlatform == p, onClick = { editPlatform = p }, label = { Text(p, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted)) } }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { shareTrip(ctx, editOrigin, editDest, editHour, editMinute, editFare) }, contentPadding = PaddingValues(horizontal = 10.dp), shape = RoundedCornerShape(10.dp)) { Text("🔗 공유", color = accent, fontSize = 13.sp) }
                    OutlinedButton(onClick = { showEditDialog = false }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("취소") }
                    Button(onClick = { scope.launch { try { withContext(Dispatchers.IO) { val json = JSONObject().apply { put("user_id", userId); if (editDest.isNotEmpty()) put("destination", editDest); if (editOrigin.isNotEmpty()) put("origin", editOrigin); if (editFare.isNotEmpty()) put("fare", (editFare.filter { it.isDigit() }.toIntOrNull() ?: 0)); put("payment_type", editPaymentType); if (editPlatform.isNotEmpty()) put("platform", editPlatform); if (editHour.isNotEmpty() && editMinute.isNotEmpty()) { try { val today = editDate; val h = editHour.filter { it.isDigit() }.padStart(2, '0'); val m = editMinute.filter { it.isDigit() }.padStart(2, '0'); val fullSdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()); fullSdf.timeZone = TimeZone.getTimeZone("Asia/Seoul"); val kstDate = fullSdf.parse("${today}T${h}:${m}:00"); val utcSdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); utcSdf.timeZone = TimeZone.getTimeZone("UTC"); put("started_at", utcSdf.format(kstDate!!)) } catch (e: Exception) {} } }; val conn = (URL("$SERVER_URL/api/trips/${editingTrip!!.id}").openConnection() as HttpURLConnection).apply { requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 8000; readTimeout = 8000 }; conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.flush() }; conn.responseCode }; showEditDialog = false; kotlinx.coroutines.delay(500); loadData() } catch (e: Exception) { showEditDialog = false } } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black, fontWeight = FontWeight.Bold) }
                }
              }
              }
            }
        }
    }

    // 빠른 금액 입력
    if (showQuickFare && quickFareTrip != null) {
        AlertDialog(onDismissRequest = { showQuickFare = false },
            title = { Text("금액 입력", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column {
                Text("${quickFareTrip!!.destination}", fontSize = 14.sp, color = AppTheme.text, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(value = quickFareInput, onValueChange = { quickFareInput = it.filter { c -> c.isDigit() } }, label = { Text("금액 (원)", color = muted) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) { listOf(5000, 10000, 15000, 30000, 50000).forEach { amount -> OutlinedButton(onClick = { quickFareInput = amount.toString() }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text("${amount/1000}천", fontSize = 11.sp) } } }
                // [v18] 플랫폼 지정 (금액 입력 시 함께)
                Spacer(Modifier.height(8.dp))
                Text("플랫폼", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("카카오T", "우버", "티머니고", "길빵/예약").forEach { p -> FilterChip(selected = quickFarePlatform == p, onClick = { quickFarePlatform = p }, label = { Text(p, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = Color(0xFF9CA3AF))) } }
            } },
            confirmButton = { Button(onClick = { val f = quickFareInput.toIntOrNull(); if (f != null && f > 0) { val platSel = quickFarePlatform; scope.launch { try { withContext(Dispatchers.IO) { val json = JSONObject().apply { put("user_id", userId); put("fare", f); if (platSel.isNotEmpty()) put("platform", platSel) }; val conn = (URL("$SERVER_URL/api/trips/${quickFareTrip!!.id}").openConnection() as HttpURLConnection).apply { requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true }; conn.outputStream.write(json.toString().toByteArray(Charsets.UTF_8)); conn.responseCode }; loadData() } catch (e: Exception) { } } }; quickFarePlatform = ""; showQuickFare = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { quickFarePlatform = ""; showQuickFare = false }) { Text("취소") } }, containerColor = AppTheme.card)
    }

    // 삭제 확인
    if (showDeleteConfirm && deletingTrip != null) {
        AlertDialog(onDismissRequest = { showDeleteConfirm = false },
            title = { Text("삭제", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Text("이 운행 기록을 삭제합니다.", color = Color(0xFF9CA3AF), fontSize = 14.sp) },
            confirmButton = { Button(onClick = { val trip = deletingTrip!!; scope.launch { try { withContext(Dispatchers.IO) { val json = JSONObject().apply { put("user_id", userId) }; val conn = (URL("$SERVER_URL/api/trips/${trip.id}").openConnection() as HttpURLConnection).apply { requestMethod = "DELETE"; setRequestProperty("Content-Type", "application/json"); doOutput = true }; conn.outputStream.write(json.toString().toByteArray()); conn.responseCode }; loadData() } catch (e: Exception) { } }; showDeleteConfirm = false }, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("삭제", color = AppTheme.text) } },
            dismissButton = { OutlinedButton(onClick = { showDeleteConfirm = false }) { Text("취소") } }, containerColor = AppTheme.card)
    }

    // 직접추가
    if (showManualDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showManualDialog = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            DialogImeFix()
            BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              val scrollMaxH = (maxHeight - 150.dp).coerceIn(140.dp, 440.dp)
              Surface(shape = RoundedCornerShape(20.dp), color = AppTheme.card, modifier = Modifier.fillMaxWidth(0.94f)) {
              Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isReportMode) "콜 제보" else "운행 추가", color = AppTheme.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Column(modifier = Modifier.heightIn(max = scrollMaxH).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // [v6] 날짜 선택 - 지난 날 운행도 입력 가능 (기본=오늘)
                if (!isReportMode) {
                    Text("날짜", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                    OutlinedButton(onClick = { showManualDatePicker = true }, modifier = Modifier.fillMaxWidth(),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (manualDate != todayStr) accent else Color(0xFF374151))) {
                        Text(if (manualDate == todayStr) "$manualDate (오늘)" else manualDate,
                            color = if (manualDate != todayStr) accent else AppTheme.text, fontSize = 14.sp)
                    }
                }
                Text("플랫폼", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("카카오T", "우버", "티머니고", "길빵/예약").forEach { p -> FilterChip(selected = manualPlatform == p, onClick = { manualPlatform = p }, label = { Text(p, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted)) } }
                if (!isReportMode) {
                    Text("결제 방식", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("카드" to "card", "현금" to "cash", "자동결제" to "auto").forEach { (label, value) -> FilterChip(selected = manualPaymentType == value, onClick = { manualPaymentType = value }, label = { Text(label, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted)) } }
                }
                Spacer(Modifier.height(8.dp))
                Text("시간", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(value = manualHour, onValueChange = { v -> val f = v.filter { it.isDigit() }.take(2); if (f.isEmpty() || f.toInt() <= 23) manualHour = f }, label = { Text("시", color = muted) }, modifier = Modifier.width(72.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                    Text(":", color = AppTheme.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = manualMinute, onValueChange = { v -> val f = v.filter { it.isDigit() }.take(2); if (f.isEmpty() || f.toInt() <= 59) manualMinute = f }, label = { Text("분", color = muted) }, modifier = Modifier.width(72.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                    OutlinedButton(onClick = { val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")); manualHour = cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0'); manualMinute = cal.get(Calendar.MINUTE).toString().padStart(2, '0') }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text("지금", fontSize = 12.sp) }
                }
                OutlinedTextField(value = manualOrigin, onValueChange = { manualOrigin = it }, label = { Text("출발지", color = muted) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                OutlinedTextField(value = manualDest, onValueChange = { manualDest = it }, label = { Text("목적지", color = muted) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                OutlinedTextField(value = manualFare, onValueChange = { manualFare = it.filter { c -> c.isDigit() } }, label = { Text("금액 (원)", color = muted) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                // [v10] 팁(⑭) — 선택 입력
                OutlinedTextField(value = manualTip, onValueChange = { manualTip = it.filter { c -> c.isDigit() } }, label = { Text("팁 (선택, 원)", color = muted) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFFBBF24), unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                // [v10] 현금 입력 간편화 — 원터치 금액 누적
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(1000, 5000, 10000, 50000).forEach { amt ->
                        OutlinedButton(onClick = { val cur = manualFare.toIntOrNull() ?: 0; manualFare = (cur + amt).toString() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) {
                            Text(if (amt >= 10000) "+${amt / 10000}만" else "+${amt / 1000}천", fontSize = 12.sp)
                        }
                    }
                    OutlinedButton(onClick = { manualFare = "" }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = muted)) { Text("C", fontSize = 12.sp) }
                }
              }
                // [v20] 저장/취소: 필드 스크롤 영역 밖 + imePadding 안 → 키보드가 떠도, 스크롤 안 해도 항상 보임
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { showManualDialog = false }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("취소") }
                    Button(onClick = {
                        if (manualDest.isNotBlank() || (manualFare.toIntOrNull() ?: 0) > 0) { scope.launch { try { withContext(Dispatchers.IO) {
                            val json = JSONObject().apply { put("user_id", userId); put("platform", if (isReportMode) "콜제보" else manualPlatform); put("originName", manualOrigin); put("destName", manualDest.ifBlank { "미정" }); put("fare", if (manualFare.isNotEmpty()) manualFare.toInt() else 0); put("tip", if (manualTip.isNotEmpty()) manualTip.toInt() else 0); put("payment_type", if (isReportMode) "report" else manualPaymentType); put("source", if (isReportMode) "report" else "manual")
                                run { val nowCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")); val h = (if (manualHour.isNotEmpty()) manualHour else nowCal.get(Calendar.HOUR_OF_DAY).toString()).padStart(2, '0'); val m = (if (manualMinute.isNotEmpty()) manualMinute else nowCal.get(Calendar.MINUTE).toString()).padStart(2, '0'); val fullSdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()); fullSdf.timeZone = TimeZone.getTimeZone("Asia/Seoul"); val kstDate = fullSdf.parse("${manualDate}T${h}:${m}:00"); val utcSdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); utcSdf.timeZone = TimeZone.getTimeZone("UTC"); put("started_at", utcSdf.format(kstDate!!)) }
                            }; val conn = (URL("$SERVER_URL/api/trips/manual").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 8000; readTimeout = 8000 }; conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.flush() }; conn.responseCode
                        }; showManualDialog = false; val savedDate = manualDate; val savedHour = manualHour; manualOrigin = ""; manualDest = ""; manualFare = ""; manualTip = ""; manualHour = ""; manualMinute = ""; manualPaymentType = "card"; if (!isReportMode) { val effH = if (savedHour.isNotEmpty()) (savedHour.toIntOrNull() ?: 0) else Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")).get(Calendar.HOUR_OF_DAY); val sdfB = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA); sdfB.timeZone = TimeZone.getTimeZone("Asia/Seoul"); val bizCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")); bizCal.time = sdfB.parse(savedDate)!!; if (effH < dayStartHour) bizCal.add(Calendar.DAY_OF_MONTH, -1); val bizDate = sdfB.format(bizCal.time); if (bizDate == todayStr) { dateFilter = "오늘"; customDate = "" } else { customDate = bizDate; dateFilter = "날짜선택" } }; loadData() } catch (e: Exception) { showManualDialog = false } } }
                    }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black, fontWeight = FontWeight.Bold) }
                }
              }
              }
            }
        }
    }

    fun loadExpenses() {
        scope.launch {
            try {
                val ym = SimpleDateFormat("yyyy-MM", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date())
                val response = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/expenses/$userId?month=$ym").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }
                val arr = JSONArray(response); val list = mutableListOf<ExpenseRecord>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val rawTime = obj.optString("created_at", "")
                    val formattedDate = try { val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); sdf.timeZone = TimeZone.getTimeZone("UTC"); val date = sdf.parse(rawTime); val out = SimpleDateFormat("MM/dd (E)", Locale.KOREA); out.timeZone = TimeZone.getTimeZone("Asia/Seoul"); out.format(date!!) } catch (e: Exception) { "" }
                    list.add(ExpenseRecord(obj.getInt("id"), obj.optString("category", ""), obj.optInt("amount", 0), obj.optString("expense_type", "business"), obj.optString("memo", ""), formattedDate))
                }
                expenses = list
            } catch (e: Exception) { }
        }
    }
    // [v21] #4 지출·운행 통합: 내역 요약에 지출·순수익 표시 위해 초기 로드
    LaunchedEffect(Unit) { loadExpenses() }

    // 지출 추가 다이얼로그
    if (showExpenseDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showExpenseDialog = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            DialogImeFix()
            BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              val scrollMaxH = (maxHeight - 150.dp).coerceIn(140.dp, 440.dp)
              Surface(shape = RoundedCornerShape(20.dp), color = AppTheme.card, modifier = Modifier.fillMaxWidth(0.94f)) {
              Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("지출 추가", color = AppTheme.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Column(modifier = Modifier.heightIn(max = scrollMaxH).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // [v6] 지출 날짜 선택 (기본=오늘)
                Text("날짜", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                OutlinedButton(onClick = { showExpenseDatePicker = true }, modifier = Modifier.fillMaxWidth(),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (expenseDate != todayStr) accent else Color(0xFF374151))) {
                    Text(if (expenseDate == todayStr) "$expenseDate (오늘)" else expenseDate,
                        color = if (expenseDate != todayStr) accent else AppTheme.text, fontSize = 14.sp)
                }
                Text("카테고리", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("LPG", "식비", "세차", "주차", "기타").forEach { c -> FilterChip(selected = expenseCategory == c, onClick = { expenseCategory = c }, label = { Text(c, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted)) } }
                Text("구분", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("사업지출" to "business", "개인지출" to "personal", "잡지출" to "misc").forEach { (label, value) -> FilterChip(selected = expenseType == value, onClick = { expenseType = value }, label = { Text(label, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = if (value == "business") red else if (value == "personal") Color(0xFFF97316) else Color(0xFF8B5CF6), selectedLabelColor = Color.White, containerColor = AppTheme.surface2, labelColor = muted)) } }
                if (expenseCategory == "LPG") {
                    // LPG: 리터 + 단가 → 금액 자동
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = expenseLiters, onValueChange = { expenseLiters = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("리터 (L)", color = muted) }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                        OutlinedTextField(value = lpgPrice, onValueChange = { lpgPrice = it.filter { c -> c.isDigit() } }, label = { Text("단가 (원/L)", color = muted) }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                    }
                    // [v19] 가스비 할인(리터당) — 주유소 할인 반영. 실부담 = 리터 × (단가 − 할인)
                    OutlinedTextField(value = lpgDiscount, onValueChange = { lpgDiscount = it.filter { c -> c.isDigit() } }, label = { Text("리터당 할인 (원/L, 선택)", color = muted) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = green, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                    val netPrice = ((lpgPrice.toIntOrNull() ?: 0) - (lpgDiscount.toIntOrNull() ?: 0)).coerceAtLeast(0)
                    val calcAmt = ((expenseLiters.toDoubleOrNull() ?: 0.0) * netPrice).toInt()
                    val discTotal = ((expenseLiters.toDoubleOrNull() ?: 0.0) * (lpgDiscount.toIntOrNull() ?: 0)).toInt()
                    Text("금액: ${String.format("%,d", calcAmt)}원" + if (discTotal > 0) "  (할인 -${String.format("%,d", discTotal)}원)" else "", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = green, modifier = Modifier.padding(vertical = 2.dp))
                } else {
                    OutlinedTextField(value = expenseAmount, onValueChange = { expenseAmount = it.filter { c -> c.isDigit() } }, label = { Text("금액 (원)", color = muted) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                }
                OutlinedTextField(value = expenseMemo, onValueChange = { expenseMemo = it }, label = { Text("메모 (선택)", color = muted) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
              }
                // [v20] 저장/취소: 필드 스크롤 밖 + imePadding 안 → 스크롤 안 해도 항상 보임
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { showExpenseDialog = false }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("취소") }
                    Button(onClick = {
                        val isLpg = expenseCategory == "LPG"
                        val liters = expenseLiters.toDoubleOrNull() ?: 0.0
                        val price = lpgPrice.toIntOrNull() ?: 0
                        val disc = lpgDiscount.toIntOrNull() ?: 0
                        val netPrice = (price - disc).coerceAtLeast(0)   // [v19] 할인 반영 실단가
                        val amt = if (isLpg) (liters * netPrice).toInt() else (expenseAmount.toIntOrNull() ?: 0)
                        if (amt > 0) {
                            if (isLpg) expensePrefs.edit().putInt("lpg_price", price).putInt("lpg_discount", disc).apply()
                            scope.launch { try { withContext(Dispatchers.IO) {
                                val json = JSONObject().apply { put("user_id", userId); put("category", expenseCategory); put("amount", amt); put("expense_type", expenseType); put("memo", expenseMemo + (if (isLpg && disc > 0) " (할인 ${disc}원/L)" else "")); put("expense_date", expenseDate); if (isLpg) { put("liters", liters); put("price_per_liter", netPrice) } }
                                val conn = (URL("$SERVER_URL/api/expenses").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 8000 }
                                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.flush() }; conn.responseCode
                            }; showExpenseDialog = false; expenseAmount = ""; expenseLiters = ""; expenseMemo = ""; loadExpenses() } catch (e: Exception) { showExpenseDialog = false } } }
                    }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black, fontWeight = FontWeight.Bold) }
                }
              }
              }
            }
        }
    }

    // 지출 삭제 확인
    if (showDeleteExpense && deletingExpense != null) {
        AlertDialog(onDismissRequest = { showDeleteExpense = false },
            title = { Text("삭제", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Text("이 지출 기록을 삭제합니다.", color = Color(0xFF9CA3AF), fontSize = 14.sp) },
            confirmButton = { Button(onClick = { val exp = deletingExpense!!; scope.launch { try { withContext(Dispatchers.IO) { val json = JSONObject().apply { put("user_id", userId) }; val conn = (URL("$SERVER_URL/api/expenses/${exp.id}").openConnection() as HttpURLConnection).apply { requestMethod = "DELETE"; setRequestProperty("Content-Type", "application/json"); doOutput = true }; conn.outputStream.write(json.toString().toByteArray()); conn.responseCode }; loadExpenses() } catch (e: Exception) { } }; showDeleteExpense = false }, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("삭제", color = AppTheme.text) } },
            dismissButton = { OutlinedButton(onClick = { showDeleteExpense = false }) { Text("취소") } }, containerColor = AppTheme.card)
    }

    Column(modifier = Modifier.fillMaxSize().background(bg)) {
        // 헤더 (컴팩트)
        Row(modifier = Modifier.fillMaxWidth().background(card).padding(top = 48.dp, bottom = 10.dp, start = 14.dp, end = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("내역", "달력", "지출", "월급").forEachIndexed { index, title -> FilterChip(selected = selectedTab == index, onClick = { selectedTab = index; if (index == 2) loadExpenses() }, label = { Text(title, fontSize = 12.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted)) } }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                // [v19] 실적 가져오기 (카메라/갤러리/파일 → 확인표) — 모든 탭에서 진입
                TextButton(onClick = { com.callradar.app.ImageImportActivity.start(ctx) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("📥 가져오기", fontSize = 13.sp, color = accent, fontWeight = FontWeight.Bold) }
                if (selectedTab == 0) { TextButton(onClick = { isReportMode = false; manualDate = todayStr; manualOrigin = ""; manualDest = ""; manualFare = ""; manualTip = ""; manualHour = ""; manualMinute = ""; manualPaymentType = "card"; showManualDialog = true }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("+ 추가", fontSize = 13.sp, color = accent, fontWeight = FontWeight.Bold) } }
                if (selectedTab == 2) { TextButton(onClick = { expenseCategory = "LPG"; expenseDate = todayStr; expenseAmount = ""; expenseMemo = ""; expenseType = "business"; showExpenseDialog = true }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("+ 지출", fontSize = 13.sp, color = accent, fontWeight = FontWeight.Bold) } }
            }
        }
        when (selectedTab) {
            0 -> {
                // 날짜 필터 (컴팩트)
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("전체", "오늘", "어제").forEach { filter -> FilterChip(selected = dateFilter == filter, onClick = { dateFilter = filter }, label = { Text(filter, fontSize = 11.sp) }, modifier = Modifier.height(30.dp), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted)) }
                    FilterChip(selected = dateFilter == "날짜선택", onClick = { showDatePicker = true }, label = { Text(if (dateFilter == "날짜선택" && customDate.isNotEmpty()) customDate.substring(5) else "\uD83D\uDCC5", fontSize = 11.sp) }, modifier = Modifier.height(30.dp), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
                    Spacer(Modifier.weight(1f))
                    Surface(onClick = onOpenDailySettlement, shape = RoundedCornerShape(15.dp), color = Color(0xFF1E3A2E), modifier = Modifier.height(30.dp)) {
                        Row(modifier = Modifier.padding(horizontal = 12.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) { Text("\uD83D\uDCCB 마감", fontSize = 11.sp, color = Color(0xFF6EE7B7), fontWeight = FontWeight.Bold) }
                    }
                }
                // 요약 (홈 스타일)
                if (dateFilter != "전체" && trips.isNotEmpty()) {
                    val totalFare = trips.sumOf { it.fare }
                    // [v7] 현금 별도 집계 — 현금은 회사 미납부(기사 실수입), 카드/플랫폼과 분리해 보여줌
                    val cashFare = trips.filter { it.paymentType == "cash" }.sumOf { it.fare }
                    val cardFare = totalFare - cashFare
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(10.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${trips.size}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTheme.text); Text("운행", fontSize = 10.sp, color = muted) }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${String.format("%,d", totalFare)}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = green); Text("총매출", fontSize = 10.sp, color = muted) }
                                if (totalFare > 0) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${String.format("%,d", totalFare / trips.size)}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = accent); Text("평균", fontSize = 10.sp, color = muted) } }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("\uD83D\uDCB3 ${String.format("%,d", cardFare)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF60A5FA)); Text("카드/플랫폼", fontSize = 9.sp, color = muted) }
                                if (cashFare > 0) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("\uD83D\uDCB5 ${String.format("%,d", cashFare)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFBBF24)); Text("현금(내 몫)", fontSize = 9.sp, color = muted) } }
                            }
                            // [v21] #4 지출·운행 통합: 같은 기간 지출·순수익
                            run {
                                val fd0 = getFilterDate()
                                val mmdd = fd0?.let { if (it.length >= 10) it.substring(5).replace("-", "/") else null }
                                val periodExpense = if (mmdd != null) expenses.filter { it.date.startsWith(mmdd) }.sumOf { it.amount } else 0
                                if (periodExpense > 0) {
                                    HorizontalDivider(color = Color(0xFF374151), modifier = Modifier.padding(vertical = 6.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("-${String.format("%,d", periodExpense)}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444)); Text("지출", fontSize = 10.sp, color = muted) }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${String.format("%,d", totalFare - periodExpense)}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = green); Text("순수익", fontSize = 10.sp, color = muted) }
                                    }
                                }
                            }
                        }
                    }
                }
                if (isLoading) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) } }
                else if (trips.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("\uD83D\uDE96", fontSize = 48.sp); Spacer(Modifier.height(12.dp)); Text("운행 기록이 없어요", fontSize = 14.sp, color = muted) } } }
                else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(trips) { _, trip ->
                            Card(modifier = Modifier.fillMaxWidth().clickable { editingTrip = trip; editDest = trip.destination; editOrigin = trip.origin; editFare = if (trip.fare > 0) trip.fare.toString() else ""; editPaymentType = trip.paymentType; editPlatform = trip.platform; editHour = trip.time.split(":").getOrElse(0) { "" }; editMinute = trip.time.split(":").getOrElse(1) { "" }; editDate = trip.rawDate.ifEmpty { todayStr }; showEditDialog = true }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val payTintX = when (trip.paymentType) { "card" -> accent; "cash" -> green; else -> muted }
                                    val payLabelX = when (trip.paymentType) { "card" -> "💳"; "cash" -> "💵"; else -> "🚕" }
                                    Box(modifier = Modifier.size(34.dp).background(payTintX.copy(alpha = 0.15f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) { Text(payLabelX, fontSize = 15.sp) }
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (trip.origin.isNotEmpty()) { Text(trip.origin.take(6), fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(" \u2192 ", fontSize = 12.sp, color = muted) }
                                            Text(trip.destination.take(12), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppTheme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        val payLabel = when (trip.paymentType) { "card" -> "\uD83D\uDCB3"; "cash" -> "\uD83D\uDCB5"; else -> "" }
                                        val timeDisplay = if (trip.endTime.isNotEmpty()) "${trip.time}~${trip.endTime}" else trip.time
                                        Text("${trip.platform} $payLabel \u00B7 ${trip.date} \u00B7 $timeDisplay", fontSize = 11.sp, color = muted)
                                    }
                                    if (trip.fare > 0) { Text("${String.format("%,d", trip.fare)}원", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = green) }
                                    else { Text("금액입력", fontSize = 12.sp, color = accent, modifier = Modifier.clickable { quickFareTrip = trip; quickFareInput = ""; quickFarePlatform = ""; showQuickFare = true }) }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { shareTrip(ctx, trip.origin, trip.destination, trip.time.split(":").getOrElse(0) { "" }, trip.time.split(":").getOrElse(1) { "" }, if (trip.fare > 0) trip.fare.toString() else "") }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(28.dp)) { Text("🔗", fontSize = 14.sp) }
                                    TextButton(onClick = { deletingTrip = trip; showDeleteConfirm = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(28.dp)) { Text("\uD83D\uDDD1", fontSize = 13.sp) }
                                }
                            }
                        }
                    }
                }
            }
            1 -> CalendarView(userId = userId)
            2 -> {
                // 지출 탭
                val businessTotal = expenses.filter { it.expenseType == "business" }.sumOf { it.amount }
                val personalTotal = expenses.filter { it.expenseType == "personal" }.sumOf { it.amount }
                if (businessTotal > 0 || personalTotal > 0) {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(10.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            if (businessTotal > 0) { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("사업지출", fontSize = 13.sp, color = muted); Text("-${String.format("%,d", businessTotal)}원", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = red) } }
                            if (personalTotal > 0) { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("개인지출", fontSize = 13.sp, color = muted); Text("-${String.format("%,d", personalTotal)}원", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF97316)) } }
                            HorizontalDivider(color = Color(0xFF374151), modifier = Modifier.padding(vertical = 4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("총 지출", fontSize = 13.sp, color = AppTheme.text); Text("-${String.format("%,d", businessTotal + personalTotal)}원", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = red) }
                        }
                    }
                }
                if (expenses.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("💰", fontSize = 48.sp); Spacer(Modifier.height(12.dp)); Text("지출 기록이 없어요", fontSize = 14.sp, color = muted); Spacer(Modifier.height(8.dp)); TextButton(onClick = { expenseCategory = "LPG"; expenseAmount = ""; expenseMemo = ""; expenseType = "business"; showExpenseDialog = true }) { Text("+ 지출 추가하기", color = accent) } } }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(expenses) { _, exp ->
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(when(exp.category) { "LPG" -> "⛽"; "식비" -> "🍚"; "세차" -> "🚿"; "주차" -> "🅿️"; else -> "📝" }, fontSize = 16.sp)
                                            Text(exp.category, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                                            Card(colors = CardDefaults.cardColors(containerColor = if (exp.expenseType == "business") Color(0xFF7F1D1D) else Color(0xFF78350F)), shape = RoundedCornerShape(4.dp)) { Text(if (exp.expenseType == "business") "사업" else "개인", fontSize = 9.sp, color = AppTheme.text, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)) }
                                        }
                                        Text("${exp.date}${if (exp.memo.isNotEmpty()) " · ${exp.memo}" else ""}", fontSize = 11.sp, color = muted)
                                    }
                                    Text("-${String.format("%,d", exp.amount)}원", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (exp.expenseType == "business") red else Color(0xFFF97316))
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { deletingExpense = exp; showDeleteExpense = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(28.dp)) { Text("🗑", fontSize = 13.sp) }
                                }
                            }
                        }
                    }
                }
            }
            3 -> {
                // [v21 재설계] 월급 · 정산 탭 — 홈에서 계산된 실수령을 동일하게 표시(캐시)
                val mprefs = ctx.getSharedPreferences("callradar_prefs", android.content.Context.MODE_PRIVATE)
                val cachedTake = mprefs.getInt("cached_takehome", 0)
                val cachedNet = mprefs.getInt("cached_net_income", 0)
                val cachedFare = mprefs.getInt("cached_month_fare", 0)
                val cachedMonth = mprefs.getInt("cached_takehome_month", 0)
                val cachedCorp = mprefs.getBoolean("cached_is_corporate", false)
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text("💰 월급 · 정산", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                    Spacer(Modifier.height(10.dp))
                    if (cachedTake != 0 || cachedFare != 0) {
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${cachedMonth}월 " + (if (cachedCorp) "예상 실수령(월급)" else "예상 순수익"), fontSize = 13.sp, color = muted)
                                Text(String.format("%,d", cachedTake) + "원", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = accent)
                                HorizontalDivider(color = AppTheme.surface2)
                                Text("월 매출: " + String.format("%,d", cachedFare) + "원", fontSize = 13.sp, color = AppTheme.text)
                                if (cachedCorp) Text("사납금·가스·지출 차감 후: " + String.format("%,d", cachedNet) + "원", fontSize = 13.sp, color = AppTheme.text)
                                Text("사납금·4대보험·조합비·기타공제가 모두 반영된 값입니다(홈과 동일).", fontSize = 11.sp, color = muted)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (cachedTake == 0 && cachedFare == 0) Text("홈 화면을 한 번 열면 이번 달 실수령이 여기 표시됩니다.", fontSize = 13.sp, color = AppTheme.text)
                            Text("회사마다 명세서가 다르니, 명세서 사진/수동으로 공제(사납금·4대보험·조합비 등)를 입력하면 실수령이 정확해져요.", fontSize = 12.sp, color = muted)
                            Button(onClick = { onOpenSettings() }, modifier = Modifier.fillMaxWidth().height(46.dp), colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(10.dp)) {
                                Text("📋 회사 명세서 입력 · 역산 열기", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Text("• 이번 달 매출·카드·현금·지출 상세: '달력' 탭", fontSize = 13.sp, color = accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarView(userId: String) {
    val bg = AppTheme.bg; val card = AppTheme.card; val accent = Color(0xFFF59E0B); val green = Color(0xFF10B981); val muted = Color(0xFF6B7280); val red = Color(0xFFEF4444)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var yearMonth by remember { mutableStateOf(SimpleDateFormat("yyyy-MM", Locale.KOREA).format(Date())) }
    var dailyMap by remember { mutableStateOf<Map<String, DailyRecord>>(emptyMap()) }
    var monthLpgAmount by remember { mutableStateOf(0) }
    var monthLpgLiters by remember { mutableStateOf(0.0) }
    var monthBusinessExp by remember { mutableStateOf(0) }
    val monthPrefs = ctx.getSharedPreferences("callradar_prefs", android.content.Context.MODE_PRIVATE)
    val lpgRefundRate = monthPrefs.getInt("lpg_refund_rate", 0)
    val monthDriverType = monthPrefs.getString("driver_type", "personal") ?: "personal"
    val monthDayStart = monthPrefs.getInt("day_start_hour", 0)   // [v19] 영업일 시작(야간·일차 기사)

    fun loadMonthExpense(ym: String) {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/expenses/summary/$userId?month=$ym").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }
                val j = JSONObject(resp)
                monthLpgAmount = j.optInt("lpgAmount", 0)
                monthLpgLiters = j.optDouble("lpgLiters", 0.0)
                monthBusinessExp = j.optInt("business", 0)
            } catch (e: Exception) { }
        }
    }
    var isLoading by remember { mutableStateOf(true) }
    var selectedDate by remember { mutableStateOf<String?>(null) }
    var selectedTrips by remember { mutableStateOf<List<TripRecord>>(emptyList()) }
    var isLoadingTrips by remember { mutableStateOf(false) }
    var dayExpense by remember { mutableStateOf(0) }   // [v19] 선택 날짜 지출 합계 (그날 순수익 계산용)

    fun loadMonth(ym: String) { scope.launch { isLoading = true; try { val response = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/stats/daily/$userId?month=$ym&dayStart=$monthDayStart").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }; val arr = JSONArray(response); val map = mutableMapOf<String, DailyRecord>(); for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); val date = obj.optString("date", "").take(10); map[date] = DailyRecord(date, obj.optInt("trip_count", 0), obj.optInt("total_fare", 0), obj.optInt("card_fare", 0), obj.optInt("cash_fare", 0), obj.optInt("expense", 0)) }; dailyMap = map } catch (e: Exception) { dailyMap = emptyMap() }; isLoading = false } }
    fun loadDayTrips(date: String) { scope.launch { isLoadingTrips = true; try { val response = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/trips/$userId?date=$date&limit=50&dayStart=$monthDayStart").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }; val arr = JSONArray(response); val list = mutableListOf<TripRecord>(); for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); val rawTime = obj.optString("started_at", ""); val formattedTime = try { val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); sdf.timeZone = TimeZone.getTimeZone("UTC"); val d = sdf.parse(rawTime); val out = SimpleDateFormat("HH:mm", Locale.KOREA); out.timeZone = TimeZone.getTimeZone("Asia/Seoul"); out.format(d!!) } catch (e: Exception) { "" }; list.add(TripRecord(obj.getInt("id"), obj.optString("origin", ""), obj.optString("destination", "목적지 없음"), obj.optInt("fare", 0), obj.optString("platform", ""), formattedTime, date, obj.optString("payment_type", "auto"), "", date)) }; selectedTrips = list; try { val er = withContext(Dispatchers.IO) { val c = (URL("$SERVER_URL/api/expenses/$userId?date=$date&dayStart=$monthDayStart").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; c.inputStream.bufferedReader().readText() }; val ea = JSONArray(er); var s = 0; for (i in 0 until ea.length()) s += ea.getJSONObject(i).optInt("amount", 0); dayExpense = s } catch (e: Exception) { dayExpense = 0 } } catch (e: Exception) { selectedTrips = emptyList(); dayExpense = 0 }; isLoadingTrips = false } }

    LaunchedEffect(yearMonth) { loadMonth(yearMonth); loadMonthExpense(yearMonth); selectedDate = null }
    val totalFare = dailyMap.values.sumOf { it.totalFare }; val totalTrips = dailyMap.values.sumOf { it.tripCount }
    val totalCard = dailyMap.values.sumOf { it.cardFare }; val totalCash = dailyMap.values.sumOf { it.cashFare }; val totalDayExpense = dailyMap.values.sumOf { it.expense }
    val parts = yearMonth.split("-"); val cal = Calendar.getInstance(); cal.set(parts[0].toInt(), parts[1].toInt() - 1, 1)
    val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1; val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())

    Column(modifier = Modifier.fillMaxSize().background(bg)) {
        // 월 헤더
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(36.dp).clickable { val c = Calendar.getInstance(); c.set(parts[0].toInt(), parts[1].toInt() - 1, 1); c.add(Calendar.MONTH, -1); yearMonth = SimpleDateFormat("yyyy-MM", Locale.KOREA).format(c.time) }, contentAlignment = Alignment.Center) { Text("\u25C0", fontSize = 16.sp, color = accent) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(yearMonth.replace("-", "년 ") + "월", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                Text("${String.format("%,d", totalFare)}원 \u00B7 ${totalTrips}콜", fontSize = 12.sp, color = muted)
            }
            Box(modifier = Modifier.size(36.dp).clickable { val c = Calendar.getInstance(); c.set(parts[0].toInt(), parts[1].toInt() - 1, 1); c.add(Calendar.MONTH, 1); yearMonth = SimpleDateFormat("yyyy-MM", Locale.KOREA).format(c.time) }, contentAlignment = Alignment.Center) { Text("\u25B6", fontSize = 16.sp, color = accent) }
        }
        if (isLoading) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) }; return@Column }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
            // 월 정산 요약 카드
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${yearMonth.replace("-", "년 ")}월 정산", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                    Spacer(Modifier.height(2.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("총매출 (${totalTrips}콜)", fontSize = 12.sp, color = muted)
                        Text("${String.format("%,d", totalFare)}원", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = green)
                    }
                    // [v19] 카드/현금/지출 분해 (일별처럼)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("💳 카드/플랫폼", fontSize = 12.sp, color = muted)
                        Text("${String.format("%,d", totalCard)}원", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF60A5FA))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("💵 현금", fontSize = 12.sp, color = muted)
                        Text("${String.format("%,d", totalCash)}원", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFBBF24))
                    }
                    if (totalDayExpense > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("🧾 지출", fontSize = 12.sp, color = muted)
                            Text("-${String.format("%,d", totalDayExpense)}원", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = red)
                        }
                    }
                    if (monthLpgAmount > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("LPG (${String.format("%.1f", monthLpgLiters)}L)", fontSize = 12.sp, color = muted)
                            Text("-${String.format("%,d", monthLpgAmount)}원", fontSize = 13.sp, color = red)
                        }
                        // 부가세 환급 참고 (환급률 설정 시)
                        if (lpgRefundRate > 0) {
                            val refund = monthLpgAmount * lpgRefundRate / 100
                            HorizontalDivider(color = AppTheme.surface2, modifier = Modifier.padding(vertical = 2.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("💡 LPG 부가세 환급 예상", fontSize = 12.sp, color = green, fontWeight = FontWeight.Bold)
                                    Text("환급률 ${lpgRefundRate}% · 회사 지급 참고용", fontSize = 10.sp, color = muted)
                                }
                                Text("+${String.format("%,d", refund)}원", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = green)
                            }
                        } else {
                            Text("설정에서 LPG 환급률 입력 시 환급 예상액이 표시됩니다", fontSize = 10.sp, color = muted)
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) { listOf("일","월","화","수","목","금","토").forEachIndexed { i, d -> Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(d, fontSize = 11.sp, color = if (i == 0) Color(0xFFEF4444) else muted, fontWeight = FontWeight.Bold) } } }
            Spacer(Modifier.height(4.dp))
            val totalCells = firstDayOfWeek + daysInMonth; val rows = (totalCells + 6) / 7
            for (row in 0 until rows) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (col in 0 until 7) {
                        val cellIndex = row * 7 + col; val day = cellIndex - firstDayOfWeek + 1
                        if (day < 1 || day > daysInMonth) { Box(modifier = Modifier.weight(1f).aspectRatio(0.8f)) }
                        else {
                            val dateStr = "$yearMonth-${day.toString().padStart(2, '0')}"; val dayData = dailyMap[dateStr]; val isSelected = selectedDate == dateStr; val isToday = dateStr == today
                            val hasData = dayData != null && (dayData.totalFare > 0 || dayData.expense > 0)
                            Box(modifier = Modifier.weight(1f).aspectRatio(0.8f).padding(1.dp).background(if (isSelected) accent else if (hasData) AppTheme.surface2 else Color.Transparent, RoundedCornerShape(6.dp)).border(0.7.dp, AppTheme.surface2, RoundedCornerShape(6.dp)).clickable { selectedDate = dateStr; loadDayTrips(dateStr) }, contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$day", fontSize = 12.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) Color.Black else if (isToday) accent else if (col == 0) Color(0xFFEF4444) else AppTheme.text)
                                    if (dayData != null && dayData.totalFare > 0) { Text("${dayData.totalFare / 10000}만", fontSize = 9.sp, color = if (isSelected) Color.Black else green, fontWeight = FontWeight.Bold) }
                                    if (dayData != null && dayData.expense > 0) { Text("-${dayData.expense / 10000}만", fontSize = 8.sp, color = if (isSelected) Color(0xFF7F1D1D) else red, fontWeight = FontWeight.Medium) }
                                }
                            }
                        }
                    }
                }
            }
            if (selectedDate != null) {
                Spacer(Modifier.height(12.dp)); HorizontalDivider(color = AppTheme.surface2); Spacer(Modifier.height(8.dp))
                Text("${selectedDate} 운행기록", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppTheme.text, modifier = Modifier.padding(bottom = 6.dp))
                // [v19] 그날 수입·지출·순수익 요약 (마감 관리용)
                run {
                    val dayIncome = selectedTrips.sumOf { it.fare }
                    val dayCash = selectedTrips.filter { it.paymentType == "cash" }.sumOf { it.fare }
                    val dayCard = dayIncome - dayCash
                    val dayNet = dayIncome - dayExpense
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(10.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("수입", fontSize = 10.sp, color = muted); Text("${String.format("%,d", dayIncome)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = green) }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("지출", fontSize = 10.sp, color = muted); Text("${String.format("%,d", dayExpense)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = red) }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("순수익", fontSize = 10.sp, color = muted); Text("${String.format("%,d", dayNet)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (dayNet >= 0) accent else red) }
                            }
                            Spacer(Modifier.height(8.dp)); HorizontalDivider(color = card); Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("💳 카드/플랫폼", fontSize = 10.sp, color = muted); Text("${String.format("%,d", dayCard)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF60A5FA)) }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("💵 현금", fontSize = 10.sp, color = muted); Text("${String.format("%,d", dayCash)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFBBF24)) }
                            }
                        }
                    }
                }
                if (isLoadingTrips) { Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent, modifier = Modifier.size(20.dp)) } }
                else if (selectedTrips.isEmpty()) { Text("운행 기록 없음", fontSize = 12.sp, color = muted, modifier = Modifier.padding(vertical = 8.dp)) }
                else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        selectedTrips.forEach { trip ->
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (trip.origin.isNotEmpty()) { Text(trip.origin.take(6), fontSize = 11.sp, color = muted, maxLines = 1); Text(" \u2192 ", fontSize = 11.sp, color = muted) }
                                            Text(trip.destination.take(12), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppTheme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        val payLabel = when (trip.paymentType) { "card" -> "\uD83D\uDCB3"; "cash" -> "\uD83D\uDCB5"; else -> "" }
                                        Text("${trip.platform} $payLabel \u00B7 ${trip.time}", fontSize = 10.sp, color = accent)
                                    }
                                    Text(if (trip.fare > 0) "${String.format("%,d", trip.fare)}원" else "-", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (trip.fare > 0) green else muted)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
