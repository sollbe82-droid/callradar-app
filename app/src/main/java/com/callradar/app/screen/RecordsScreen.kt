package com.callradar.app.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

private const val SERVER_URL = "https://callradar-server.onrender.com"

data class TripRecord(val id: Int, val origin: String, val destination: String, val fare: Int, val platform: String, val time: String, val date: String)
data class DailyRecord(val date: String, val tripCount: Int, val totalFare: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(userId: String) {
    val bg = Color(0xFF0A0E1A); val card = Color(0xFF111827); val accent = Color(0xFFF59E0B); val green = Color(0xFF10B981); val red = Color(0xFFEF4444); val muted = Color(0xFF6B7280)
    var selectedTab by remember { mutableStateOf(0) }
    var trips by remember { mutableStateOf<List<TripRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var dateFilter by remember { mutableStateOf("오늘") }
    var customDate by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editingTrip by remember { mutableStateOf<TripRecord?>(null) }
    var editDest by remember { mutableStateOf("") }
    var editOrigin by remember { mutableStateOf("") }
    var editFare by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showQuickFare by remember { mutableStateOf(false) }
    var quickFareTrip by remember { mutableStateOf<TripRecord?>(null) }
    var quickFareInput by remember { mutableStateOf("") }
    var deletingTrip by remember { mutableStateOf<TripRecord?>(null) }
    var showManualDialog by remember { mutableStateOf(false) }
    var isReportMode by remember { mutableStateOf(false) }
    var manualOrigin by remember { mutableStateOf("") }
    var manualDest by remember { mutableStateOf("") }
    var manualFare by remember { mutableStateOf("") }
    var manualHour by remember { mutableStateOf("") } 
    var manualPlatform by remember { mutableStateOf("길빵/예약") }
    var manualMinute by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun getFilterDate(): String? {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA); sdf.timeZone = TimeZone.getTimeZone("Asia/Seoul")
        return when (dateFilter) { "오늘" -> sdf.format(Date()); "어제" -> { val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")); cal.add(Calendar.DAY_OF_MONTH, -1); sdf.format(cal.time) }; "날짜선택" -> customDate.ifEmpty { null }; else -> null }
    }

    fun loadData() {
        scope.launch {
            try {
                isLoading = true; val filterDate = getFilterDate()
                val url = if (filterDate != null) "$SERVER_URL/api/trips/$userId?date=$filterDate&limit=100" else "$SERVER_URL/api/trips/$userId?limit=100"
                val tripsResponse = withContext(Dispatchers.IO) { val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }
                val tripsJson = JSONArray(tripsResponse); val tripList = mutableListOf<TripRecord>()
                for (i in 0 until tripsJson.length()) {
                    val obj = tripsJson.getJSONObject(i); val rawTime = obj.optString("started_at", "")
                    val formattedTime = try { val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); sdf.timeZone = TimeZone.getTimeZone("UTC"); val date = sdf.parse(rawTime); val out = SimpleDateFormat("HH:mm", Locale.KOREA); out.timeZone = TimeZone.getTimeZone("Asia/Seoul"); out.format(date!!) } catch (e: Exception) { "" }
                    val formattedDate = try { val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); sdf.timeZone = TimeZone.getTimeZone("UTC"); val date = sdf.parse(rawTime); val out = SimpleDateFormat("MM/dd (E)", Locale.KOREA); out.timeZone = TimeZone.getTimeZone("Asia/Seoul"); out.format(date!!) } catch (e: Exception) { "" }
                    tripList.add(TripRecord(obj.getInt("id"), obj.optString("origin", ""), obj.optString("destination", "목적지 없음"), obj.optInt("fare", 0), obj.optString("platform", ""), formattedTime, formattedDate))
                }
                trips = tripList; isLoading = false
            } catch (e: Exception) { isLoading = false }
        }
    }

    LaunchedEffect(Unit) { loadData() }
    LaunchedEffect(dateFilter, customDate) { loadData() }

    // DatePicker
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(onDismissRequest = { showDatePicker = false },
            confirmButton = { Button(onClick = { datePickerState.selectedDateMillis?.let { millis -> val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA); sdf.timeZone = TimeZone.getTimeZone("Asia/Seoul"); customDate = sdf.format(Date(millis)); dateFilter = "날짜선택" }; showDatePicker = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("선택", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showDatePicker = false }) { Text("취소") } },
            colors = DatePickerDefaults.colors(containerColor = Color(0xFF111827))
        ) { DatePicker(state = datePickerState) }
    }

    // 수정 다이얼로그
    if (showEditDialog && editingTrip != null) {
        AlertDialog(onDismissRequest = { showEditDialog = false },
            title = { Text("운행 기록 수정", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = editOrigin, onValueChange = { editOrigin = it }, label = { Text("출발지", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                OutlinedTextField(value = editDest, onValueChange = { editDest = it }, label = { Text("목적지", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                OutlinedTextField(value = editFare, onValueChange = { editFare = it.filter { c -> c.isDigit() } }, label = { Text("금액 (원)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
            } },
            confirmButton = { Button(onClick = { scope.launch { try { withContext(Dispatchers.IO) { val json = JSONObject().apply { put("user_id", userId); if (editDest.isNotEmpty()) put("destination", editDest); if (editOrigin.isNotEmpty()) put("origin", editOrigin); if (editFare.isNotEmpty()) put("fare", editFare.toInt()) }; val conn = (URL("$SERVER_URL/api/trips/${editingTrip!!.id}").openConnection() as HttpURLConnection).apply { requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json"); doOutput = true }; conn.outputStream.write(json.toString().toByteArray()); conn.responseCode }; showEditDialog = false; loadData() } catch (e: Exception) { showEditDialog = false } } }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showEditDialog = false }) { Text("취소") } }, containerColor = Color(0xFF111827))
    }
// 빠른 금액 입력
if (showQuickFare && quickFareTrip != null) {
AlertDialog(onDismissRequest = { showQuickFare = false },
title = { Text("금액 입력", color = Color.White, fontWeight = FontWeight.Bold) },
text = { Column {
Text("${quickFareTrip!!.destination}", fontSize = 14.sp, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
OutlinedTextField(value = quickFareInput, onValueChange = { quickFareInput = it.filter { c -> c.isDigit() } }, label = { Text("금액 (원)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(5000, 10000, 15000, 30000, 50000).forEach { amount -> OutlinedButton(onClick = { quickFareInput = amount.toString() }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text("${amount/1000}천", fontSize = 11.sp) } } }
} },
confirmButton = { Button(onClick = { val f = quickFareInput.toIntOrNull(); if (f != null && f > 0) { scope.launch { try { withContext(Dispatchers.IO) { val json = JSONObject().apply { put("user_id", userId); put("fare", f) }; val conn = (URL("$SERVER_URL/api/trips/${quickFareTrip!!.id}").openConnection() as HttpURLConnection).apply { requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json"); doOutput = true }; conn.outputStream.write(json.toString().toByteArray()); conn.responseCode }; loadData() } catch (e: Exception) { } } }; showQuickFare = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
dismissButton = { OutlinedButton(onClick = { showQuickFare = false }) { Text("취소") } }, containerColor = Color(0xFF111827))
}
    // 삭제 확인
    if (showDeleteConfirm && deletingTrip != null) {
        AlertDialog(onDismissRequest = { showDeleteConfirm = false },
            title = { Text("삭제", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("이 운행 기록을 삭제합니다.", color = Color(0xFF9CA3AF), fontSize = 14.sp) },
            confirmButton = { Button(onClick = { val trip = deletingTrip!!; scope.launch { try { withContext(Dispatchers.IO) { val json = JSONObject().apply { put("user_id", userId) }; val conn = (URL("$SERVER_URL/api/trips/${trip.id}").openConnection() as HttpURLConnection).apply { requestMethod = "DELETE"; setRequestProperty("Content-Type", "application/json"); doOutput = true }; conn.outputStream.write(json.toString().toByteArray()); conn.responseCode }; loadData() } catch (e: Exception) { } }; showDeleteConfirm = false }, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("삭제", color = Color.White) } },
            dismissButton = { OutlinedButton(onClick = { showDeleteConfirm = false }) { Text("취소") } }, containerColor = Color(0xFF111827))
    }

    // 직접추가
    if (showManualDialog) {
        AlertDialog(onDismissRequest = { showManualDialog = false },
            title = { Text(if (isReportMode) "콜 제보" else "운행 추가", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("플랫폼", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("카카오T", "우버", "티머니고", "길빵/예약").forEach { p -> FilterChip(selected = manualPlatform == p, onClick = { manualPlatform = p }, label = { Text(p, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFF59E0B), selectedLabelColor = Color.Black, containerColor = Color(0xFF1F2937), labelColor = Color(0xFF6B7280))) } }
                Spacer(Modifier.height(8.dp))
                Text("시간", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(value = manualHour, onValueChange = { v -> val f = v.filter { it.isDigit() }.take(2); if (f.isEmpty() || f.toInt() <= 23) manualHour = f }, label = { Text("시", color = muted) }, modifier = Modifier.width(72.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                    Text(":", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = manualMinute, onValueChange = { v -> val f = v.filter { it.isDigit() }.take(2); if (f.isEmpty() || f.toInt() <= 59) manualMinute = f }, label = { Text("분", color = muted) }, modifier = Modifier.width(72.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                    OutlinedButton(onClick = { val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")); manualHour = cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0'); manualMinute = cal.get(Calendar.MINUTE).toString().padStart(2, '0') }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text("지금", fontSize = 12.sp) }
                }
                OutlinedTextField(value = manualOrigin, onValueChange = { manualOrigin = it }, label = { Text("출발지", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                OutlinedTextField(value = manualDest, onValueChange = { manualDest = it }, label = { Text("목적지", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                OutlinedTextField(value = manualFare, onValueChange = { manualFare = it.filter { c -> c.isDigit() } }, label = { Text("금액 (원)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
            } },
            confirmButton = { Button(onClick = {
                if (manualDest.isNotBlank()) { scope.launch { try { withContext(Dispatchers.IO) {
                    val json = JSONObject().apply { put("user_id", userId); put("platform", if (isReportMode) "콜제보" else manualPlatform); put("originName", manualOrigin); put("destName", manualDest); put("fare", if (manualFare.isNotEmpty()) manualFare.toInt() else 0)
                        if (manualHour.isNotEmpty() && manualMinute.isNotEmpty()) { val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA); sdf.timeZone = TimeZone.getTimeZone("Asia/Seoul"); val today = sdf.format(Date()); val h = manualHour.padStart(2, '0'); val m = manualMinute.padStart(2, '0'); val fullSdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()); fullSdf.timeZone = TimeZone.getTimeZone("Asia/Seoul"); val kstDate = fullSdf.parse("${today}T${h}:${m}:00"); val utcSdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); utcSdf.timeZone = TimeZone.getTimeZone("UTC"); put("started_at", utcSdf.format(kstDate!!)) }
                    }; val conn = (URL("$SERVER_URL/api/trips/manual").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true }; conn.outputStream.write(json.toString().toByteArray(Charsets.UTF_8)); conn.responseCode
                }; showManualDialog = false; manualOrigin = ""; manualDest = ""; manualFare = ""; manualHour = ""; manualMinute = ""; loadData() } catch (e: Exception) { showManualDialog = false } } }
            }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showManualDialog = false }) { Text("취소") } }, containerColor = Color(0xFF111827))
    }

    Column(modifier = Modifier.fillMaxSize().background(bg)) {
        // 헤더 (컴팩트)
        Row(modifier = Modifier.fillMaxWidth().background(card).padding(top = 48.dp, bottom = 10.dp, start = 14.dp, end = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("운행기록", "월별").forEachIndexed { index, title -> FilterChip(selected = selectedTab == index, onClick = { selectedTab = index }, label = { Text(title, fontSize = 12.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = Color(0xFF1F2937), labelColor = muted)) } }
            if (selectedTab == 0) { TextButton(onClick = { isReportMode = true; manualOrigin = ""; manualDest = ""; manualFare = ""; manualHour = ""; manualMinute = ""; showManualDialog = true }) { Text("제보", fontSize = 12.sp, color = Color(0xFF60A5FA)) }; TextButton(onClick = { isReportMode = false; manualOrigin = ""; manualDest = ""; manualFare = ""; manualHour = ""; manualMinute = ""; showManualDialog = true }) { Text("+ 추가", fontSize = 13.sp, color = accent, fontWeight = FontWeight.Bold) } }
        }
        when (selectedTab) {
            0 -> {
                // 날짜 필터 (컴팩트)
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("전체", "오늘", "어제").forEach { filter -> FilterChip(selected = dateFilter == filter, onClick = { dateFilter = filter }, label = { Text(filter, fontSize = 11.sp) }, modifier = Modifier.height(30.dp), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = Color(0xFF1F2937), labelColor = muted)) }
                    FilterChip(selected = dateFilter == "날짜선택", onClick = { showDatePicker = true }, label = { Text(if (dateFilter == "날짜선택" && customDate.isNotEmpty()) customDate.substring(5) else "📅", fontSize = 11.sp) }, modifier = Modifier.height(30.dp), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = Color(0xFF1F2937), labelColor = muted))
                }
                // 요약 (홈 스타일)
                if (dateFilter != "전체" && trips.isNotEmpty()) {
                    val totalFare = trips.sumOf { it.fare }
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937)), shape = RoundedCornerShape(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${trips.size}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White); Text("운행", fontSize = 10.sp, color = muted) }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${String.format("%,d", totalFare)}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = green); Text("매출", fontSize = 10.sp, color = muted) }
                            if (totalFare > 0) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${String.format("%,d", totalFare / trips.size)}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = accent); Text("평균", fontSize = 10.sp, color = muted) } }
                        }
                    }
                }
                if (isLoading) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) } }
                else if (trips.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("🚖", fontSize = 48.sp); Spacer(Modifier.height(12.dp)); Text("운행 기록이 없어요", fontSize = 14.sp, color = muted) } } }
                else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(trips) { _, trip ->
                            Card(modifier = Modifier.fillMaxWidth().clickable { editingTrip = trip; editDest = trip.destination; editOrigin = trip.origin; editFare = if (trip.fare > 0) trip.fare.toString() else ""; showEditDialog = true }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (trip.origin.isNotEmpty()) { Text(trip.origin.take(6), fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(" → ", fontSize = 12.sp, color = muted) }
                                            Text(trip.destination.take(12), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Text("${trip.platform} · ${trip.date} · ${trip.time}", fontSize = 11.sp, color = muted)
                                    }
                                    if (trip.fare > 0) { Text("${String.format("%,d", trip.fare)}원", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = green) }
                                    else { Text("금액입력", fontSize = 12.sp, color = accent, modifier = Modifier.clickable { quickFareTrip = trip; quickFareInput = ""; showQuickFare = true }) }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { deletingTrip = trip; showDeleteConfirm = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(28.dp)) { Text("🗑", fontSize = 13.sp) }
                                }
                            }
                        }
                    }
                }
            }
            1 -> CalendarView(userId = userId)
        }
    }
}

@Composable
private fun CalendarView(userId: String) {
    val bg = Color(0xFF0A0E1A); val card = Color(0xFF111827); val accent = Color(0xFFF59E0B); val green = Color(0xFF10B981); val muted = Color(0xFF6B7280)
    var yearMonth by remember { mutableStateOf(SimpleDateFormat("yyyy-MM", Locale.KOREA).format(Date())) }
    var dailyMap by remember { mutableStateOf<Map<String, DailyRecord>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedDate by remember { mutableStateOf<String?>(null) }
    var selectedTrips by remember { mutableStateOf<List<TripRecord>>(emptyList()) }
    var isLoadingTrips by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadMonth(ym: String) { scope.launch { isLoading = true; try { val response = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/stats/daily/$userId?month=$ym").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }; val arr = JSONArray(response); val map = mutableMapOf<String, DailyRecord>(); for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); val date = obj.optString("date", "").take(10); map[date] = DailyRecord(date, obj.optInt("trip_count", 0), obj.optInt("total_fare", 0)) }; dailyMap = map } catch (e: Exception) { dailyMap = emptyMap() }; isLoading = false } }
    fun loadDayTrips(date: String) { scope.launch { isLoadingTrips = true; try { val response = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/trips/$userId?date=$date&limit=50").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }; val arr = JSONArray(response); val list = mutableListOf<TripRecord>(); for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); val rawTime = obj.optString("started_at", ""); val formattedTime = try { val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); sdf.timeZone = TimeZone.getTimeZone("UTC"); val d = sdf.parse(rawTime); val out = SimpleDateFormat("HH:mm", Locale.KOREA); out.timeZone = TimeZone.getTimeZone("Asia/Seoul"); out.format(d!!) } catch (e: Exception) { "" }; list.add(TripRecord(obj.getInt("id"), obj.optString("origin", ""), obj.optString("destination", "목적지 없음"), obj.optInt("fare", 0), obj.optString("platform", ""), formattedTime, date)) }; selectedTrips = list } catch (e: Exception) { selectedTrips = emptyList() }; isLoadingTrips = false } }

    LaunchedEffect(yearMonth) { loadMonth(yearMonth); selectedDate = null }
    val totalFare = dailyMap.values.sumOf { it.totalFare }; val totalTrips = dailyMap.values.sumOf { it.tripCount }
    val parts = yearMonth.split("-"); val cal = Calendar.getInstance(); cal.set(parts[0].toInt(), parts[1].toInt() - 1, 1)
    val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1; val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())

    Column(modifier = Modifier.fillMaxSize().background(bg)) {
        // 월 헤더
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(36.dp).clickable { val c = Calendar.getInstance(); c.set(parts[0].toInt(), parts[1].toInt() - 1, 1); c.add(Calendar.MONTH, -1); yearMonth = SimpleDateFormat("yyyy-MM", Locale.KOREA).format(c.time) }, contentAlignment = Alignment.Center) { Text("◀", fontSize = 16.sp, color = accent) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(yearMonth.replace("-", "년 ") + "월", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("${String.format("%,d", totalFare)}원 · ${totalTrips}콜", fontSize = 12.sp, color = muted)
            }
            Box(modifier = Modifier.size(36.dp).clickable { val c = Calendar.getInstance(); c.set(parts[0].toInt(), parts[1].toInt() - 1, 1); c.add(Calendar.MONTH, 1); yearMonth = SimpleDateFormat("yyyy-MM", Locale.KOREA).format(c.time) }, contentAlignment = Alignment.Center) { Text("▶", fontSize = 16.sp, color = accent) }
        }
        if (isLoading) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) }; return@Column }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
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
                            Box(modifier = Modifier.weight(1f).aspectRatio(0.8f).padding(1.dp).background(if (isSelected) accent else if (dayData != null && dayData.totalFare > 0) Color(0xFF1F2937) else Color.Transparent, RoundedCornerShape(6.dp)).clickable { selectedDate = dateStr; loadDayTrips(dateStr) }, contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$day", fontSize = 12.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) Color.Black else if (isToday) accent else if (col == 0) Color(0xFFEF4444) else Color.White)
                                    if (dayData != null && dayData.totalFare > 0) { Text("${dayData.totalFare / 10000}만", fontSize = 9.sp, color = if (isSelected) Color.Black else green, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                    }
                }
            }
            if (selectedDate != null) {
                Spacer(Modifier.height(12.dp)); HorizontalDivider(color = Color(0xFF1F2937)); Spacer(Modifier.height(8.dp))
                Text("${selectedDate} 운행기록", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 6.dp))
                if (isLoadingTrips) { Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent, modifier = Modifier.size(20.dp)) } }
                else if (selectedTrips.isEmpty()) { Text("운행 기록 없음", fontSize = 12.sp, color = muted, modifier = Modifier.padding(vertical = 8.dp)) }
                else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        selectedTrips.forEach { trip ->
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (trip.origin.isNotEmpty()) { Text(trip.origin.take(6), fontSize = 11.sp, color = muted, maxLines = 1); Text(" → ", fontSize = 11.sp, color = muted) }
                                            Text(trip.destination.take(12), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Text("${trip.platform} · ${trip.time}", fontSize = 10.sp, color = accent)
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
