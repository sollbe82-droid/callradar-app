package com.callradar.app.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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

private const val SERVER_URL = "https://callradar-server.onrender.com"

data class SpotItem(val id: Int, val name: String)
data class HeatmapCell(val dayOfWeek: Int, val hour: Int, val count: Int)
data class ComparisonData(val thisWeekTrips: Int, val thisWeekFare: Int, val lastWeekTrips: Int, val lastWeekFare: Int, val thisMonthTrips: Int, val thisMonthFare: Int, val lastMonthTrips: Int, val lastMonthFare: Int)
data class DayOfWeekItem(val day: String, val avgFare: Int, val tripCount: Int)
data class InsightsData(val dayOfWeek: List<DayOfWeekItem>, val weekdayAvgFare: Int, val weekendAvgFare: Int, val streak: Int)
data class HourlyItem(val hour: Int, val count: Int)
data class DestItem(val destination: String, val count: Int)

@Composable
fun StatsScreen(userId: String) {
    val bg = Color(0xFF0A0E1A); val card = Color(0xFF111827); val accent = Color(0xFFF59E0B); val green = Color(0xFF10B981); val muted = Color(0xFF6B7280)
    var hourlyPattern by remember { mutableStateOf<List<HourlyItem>>(emptyList()) }
    var topDests by remember { mutableStateOf<List<DestItem>>(emptyList()) }
    var spots by remember { mutableStateOf<List<SpotItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var expandedSpotId by remember { mutableStateOf<Int?>(null) }
    var heatmapCells by remember { mutableStateOf<List<HeatmapCell>>(emptyList()) }
    var isLoadingHeatmap by remember { mutableStateOf(false) }
    var categoryTotals by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var spotDestinations by remember { mutableStateOf<List<DestItem>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf("normal") }
    var showAddDialog by remember { mutableStateOf(false) }
    var addSpotName by remember { mutableStateOf("") }
    var showOtherStats by remember { mutableStateOf(false) }
    var showSpotsSection by remember { mutableStateOf(false) }
    var comparison by remember { mutableStateOf<ComparisonData?>(null) }
    var insights by remember { mutableStateOf<InsightsData?>(null) }
    val scope = rememberCoroutineScope()

    fun loadSpots() { scope.launch { try { val response = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/spots/$userId").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }; val arr = JSONArray(response); val list = mutableListOf<SpotItem>(); for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); list.add(SpotItem(obj.getInt("id"), obj.getString("name"))) }; spots = list } catch (e: Exception) { } } }
    fun loadCategoryTotals(spotId: Int) { scope.launch { try { val response = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/spots/$spotId/heatmap?user_id=$userId").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }; val json = JSONObject(response); val totalsJson = json.optJSONObject("categoryTotals"); val map = mutableMapOf<String, Int>(); if (totalsJson != null) { map["airport"] = totalsJson.optInt("airport", 0); map["long"] = totalsJson.optInt("long", 0); map["normal"] = totalsJson.optInt("normal", 0) }; categoryTotals = map } catch (e: Exception) { categoryTotals = emptyMap() } } }
    fun loadHeatmap(spotId: Int, category: String) { scope.launch { isLoadingHeatmap = true; try { val response = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/spots/$spotId/heatmap?user_id=$userId&category=$category").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }; val json = JSONObject(response); val cellsArray = json.getJSONArray("cells"); val list = mutableListOf<HeatmapCell>(); for (i in 0 until cellsArray.length()) { val obj = cellsArray.getJSONObject(i); list.add(HeatmapCell(obj.getInt("day_of_week"), obj.getInt("hour"), obj.optInt("count", 0))) }; heatmapCells = list; val destArray = json.optJSONArray("destinations"); val destList = mutableListOf<DestItem>(); if (destArray != null) for (i in 0 until destArray.length()) { val obj = destArray.getJSONObject(i); destList.add(DestItem(obj.getString("destination"), obj.getInt("count"))) }; spotDestinations = destList } catch (e: Exception) { heatmapCells = emptyList(); spotDestinations = emptyList() }; isLoadingHeatmap = false } }
    fun addSpot(name: String) { if (name.isBlank()) return; scope.launch { try { withContext(Dispatchers.IO) { val json = JSONObject().apply { put("user_id", userId); put("name", name.trim()) }; val conn = (URL("$SERVER_URL/api/spots").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true }; conn.outputStream.write(json.toString().toByteArray(Charsets.UTF_8)); conn.responseCode }; loadSpots() } catch (e: Exception) { } } }
    fun deleteSpot(spotId: Int) { scope.launch { try { withContext(Dispatchers.IO) { val json = JSONObject().apply { put("user_id", userId) }; val conn = (URL("$SERVER_URL/api/spots/$spotId").openConnection() as HttpURLConnection).apply { requestMethod = "DELETE"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true }; conn.outputStream.write(json.toString().toByteArray(Charsets.UTF_8)); conn.responseCode }; if (expandedSpotId == spotId) expandedSpotId = null; loadSpots() } catch (e: Exception) { } } }

    LaunchedEffect(userId) {
        if (userId.isEmpty()) { isLoading = false; return@LaunchedEffect }
        scope.launch { try { val patternResponse = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/stats/pattern/$userId").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }; val patternJson = JSONObject(patternResponse); val hourlyArray = patternJson.getJSONArray("hourly"); val hourlyList = mutableListOf<HourlyItem>(); for (i in 0 until hourlyArray.length()) { val obj = hourlyArray.getJSONObject(i); hourlyList.add(HourlyItem(obj.getDouble("hour").toInt(), obj.getInt("count"))) }; val destsArray = patternJson.getJSONArray("destinations"); val destList = mutableListOf<DestItem>(); for (i in 0 until destsArray.length()) { val obj = destsArray.getJSONObject(i); destList.add(DestItem(obj.getString("destination"), obj.getInt("count"))) }; hourlyPattern = hourlyList; topDests = destList; isLoading = false } catch (e: Exception) { isLoading = false } }
        scope.launch { try { val response = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/stats/comparison/$userId").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }; val json = JSONObject(response); comparison = ComparisonData(json.optInt("thisWeekTrips",0),json.optInt("thisWeekFare",0),json.optInt("lastWeekTrips",0),json.optInt("lastWeekFare",0),json.optInt("thisMonthTrips",0),json.optInt("thisMonthFare",0),json.optInt("lastMonthTrips",0),json.optInt("lastMonthFare",0)) } catch (e: Exception) { comparison = null } }
        scope.launch { try { val response = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/stats/insights/$userId").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }; val json = JSONObject(response); val dowArray = json.getJSONArray("dayOfWeek"); val dowList = mutableListOf<DayOfWeekItem>(); for (i in 0 until dowArray.length()) { val obj = dowArray.getJSONObject(i); dowList.add(DayOfWeekItem(obj.getString("day"), obj.getInt("avgFare"), obj.getInt("tripCount"))) }; insights = InsightsData(dowList, json.optInt("weekdayAvgFare",0), json.optInt("weekendAvgFare",0), json.optInt("streak",0)) } catch (e: Exception) { insights = null } }
        loadSpots()
    }

    if (showAddDialog) {
        AlertDialog(onDismissRequest = { showAddDialog = false },
            title = { Text("내 장소 등록", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Column {
                Text("자주 가는 곳의 이름을 입력하세요", fontSize = 13.sp, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(value = addSpotName, onValueChange = { addSpotName = it }, placeholder = { Text("예: 강남역", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                if (topDests.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp)); Text("자주 간 곳에서 선택", fontSize = 12.sp, color = muted, modifier = Modifier.padding(bottom = 6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { topDests.take(5).forEach { dest -> OutlinedButton(onClick = { addSpotName = dest.destination }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text(dest.destination.take(20), fontSize = 12.sp) } } }
                }
            } },
            confirmButton = { Button(onClick = { addSpot(addSpotName); addSpotName = ""; showAddDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("등록", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showAddDialog = false; addSpotName = "" }) { Text("취소") } },
            containerColor = Color(0xFF111827))
    }

    Column(modifier = Modifier.fillMaxSize().background(bg)) {
        Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF111827)).padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)) { Text("내 패턴 분석", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        if (isLoading) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) }; return@Column }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("📊 내 수입 인사이트", fontSize = 14.sp, color = muted, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp))
                    insights?.let { ins -> if (ins.streak > 0) { Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B)), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) { Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text("🔥", fontSize = 18.sp); Spacer(Modifier.width(8.dp)); Text("${ins.streak}일 연속 운행 기록 중!", fontSize = 13.sp, color = green, fontWeight = FontWeight.Bold) } } } }
                    comparison?.let { c -> val weekDiff = c.thisWeekFare - c.lastWeekFare; val weekPct = if (c.lastWeekFare > 0) (weekDiff * 100 / c.lastWeekFare) else 0; Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("이번 주", fontSize = 12.sp, color = muted); Text("${String.format("%,d", c.thisWeekFare)}원", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White) }; if (c.lastWeekFare > 0) { Text(if (weekDiff >= 0) "▲ ${weekPct}% (지난주 대비)" else "▼ ${-weekPct}% (지난주 대비)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (weekDiff >= 0) green else Color(0xFFEF4444)) } }; Spacer(Modifier.height(10.dp)); HorizontalDivider(color = Color(0xFF1F2937)); Spacer(Modifier.height(10.dp)); val monthDiff = c.thisMonthFare - c.lastMonthFare; val monthPct = if (c.lastMonthFare > 0) (monthDiff * 100 / c.lastMonthFare) else 0; Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("이번 달", fontSize = 12.sp, color = muted); Text("${String.format("%,d", c.thisMonthFare)}원", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White) }; if (c.lastMonthFare > 0) { Text(if (monthDiff >= 0) "▲ ${monthPct}% (지난달 대비)" else "▼ ${-monthPct}% (지난달 대비)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (monthDiff >= 0) green else Color(0xFFEF4444)) } } } ?: Text("데이터가 더 쌓이면 비교 분석을 보여드려요", fontSize = 12.sp, color = muted)
                    insights?.let { ins -> if (ins.weekdayAvgFare > 0 || ins.weekendAvgFare > 0) { Spacer(Modifier.height(10.dp)); HorizontalDivider(color = Color(0xFF1F2937)); Spacer(Modifier.height(10.dp)); Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("평일 일평균", fontSize = 11.sp, color = muted); Text("${String.format("%,d", ins.weekdayAvgFare)}원", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White) }; Box(modifier = Modifier.width(1.dp).height(32.dp).background(Color(0xFF1F2937))); Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("주말 일평균", fontSize = 11.sp, color = muted); Text("${String.format("%,d", ins.weekendAvgFare)}원", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White) } } }; if (ins.dayOfWeek.any { it.tripCount > 0 }) { Spacer(Modifier.height(14.dp)); HorizontalDivider(color = Color(0xFF1F2937)); Spacer(Modifier.height(10.dp)); Text("요일별 평균 수입", fontSize = 12.sp, color = muted, modifier = Modifier.padding(bottom = 8.dp)); val maxAvg = (ins.dayOfWeek.maxOfOrNull { it.avgFare } ?: 1).coerceAtLeast(1); Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) { ins.dayOfWeek.forEach { d -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) { Text(if (d.avgFare > 0) "${d.avgFare / 10000}만" else "", fontSize = 9.sp, color = muted); Box(modifier = Modifier.width(20.dp).height((50 * d.avgFare / maxAvg).coerceAtLeast(2).dp).background(if (d.day == "일") Color(0xFFEF4444) else accent, RoundedCornerShape(3.dp))); Spacer(Modifier.height(4.dp)); Text(d.day, fontSize = 11.sp, color = if (d.day == "일") Color(0xFFEF4444) else muted, fontWeight = FontWeight.Bold) } } } } }
                }
            }
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().clickable { showSpotsSection = !showSpotsSection }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(if (showSpotsSection) "▼ 📌 내 장소" else "▶ 📌 내 장소", fontSize = 14.sp, color = muted, fontWeight = FontWeight.Bold); if (showSpotsSection) { TextButton(onClick = { showAddDialog = true }, contentPadding = PaddingValues(0.dp)) { Text("+ 추가", fontSize = 13.sp, color = accent, fontWeight = FontWeight.Bold) } } }
                    if (showSpotsSection) { Spacer(Modifier.height(8.dp)); if (spots.isEmpty()) { Text("등록된 장소가 없어요. 자주 가는 곳을 추가해보세요.", fontSize = 13.sp, color = muted) } else { spots.forEach { spot -> val isExpanded = expandedSpotId == spot.id; Column(modifier = Modifier.padding(vertical = 4.dp)) { Row(modifier = Modifier.fillMaxWidth().clickable { if (isExpanded) { expandedSpotId = null } else { expandedSpotId = spot.id; selectedCategory = "normal"; loadCategoryTotals(spot.id); loadHeatmap(spot.id, "normal") } }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Row(verticalAlignment = Alignment.CenterVertically) { Text(if (isExpanded) "▼" else "▶", fontSize = 11.sp, color = muted, modifier = Modifier.padding(end = 8.dp)); Text(spot.name, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold) }; TextButton(onClick = { deleteSpot(spot.id) }, contentPadding = PaddingValues(4.dp)) { Text("삭제", fontSize = 11.sp, color = Color(0xFFEF4444)) } }; if (isExpanded) { Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("normal" to "일반", "long" to "장거리", "airport" to "공항").forEach { (cat, label) -> val count = categoryTotals[cat] ?: 0; FilterChip(selected = selectedCategory == cat, onClick = { selectedCategory = cat; loadHeatmap(spot.id, cat) }, label = { Text("$label (${count})", fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = Color(0xFF1F2937), labelColor = muted)) } } }; if (isExpanded) { if (isLoadingHeatmap) { Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent, modifier = Modifier.size(20.dp)) } } else { if (spotDestinations.isNotEmpty()) { Text("주요 목적지", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)); Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { spotDestinations.take(8).forEach { dest -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(dest.destination.take(20), fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); Text("${dest.count}회", fontSize = 12.sp, color = green, fontWeight = FontWeight.Bold) } } }; Spacer(Modifier.height(12.dp)) }; HeatmapView(heatmapCells) } }; HorizontalDivider(color = Color(0xFF1F2937), thickness = 0.5.dp, modifier = Modifier.padding(top = 4.dp)) } } } }
                }
            }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().clickable { showOtherStats = !showOtherStats }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(if (showOtherStats) "▼ 더 많은 통계 보기" else "▶ 더 많은 통계 보기", fontSize = 14.sp, color = muted, fontWeight = FontWeight.Bold) }
                    if (showOtherStats) { Spacer(Modifier.height(16.dp)); Text("⏰ 시간대별 콜 패턴", fontSize = 14.sp, color = muted, modifier = Modifier.padding(bottom = 12.dp)); if (hourlyPattern.isEmpty()) { Text("데이터 쌓이는 중...", fontSize = 13.sp, color = muted) } else { val maxCount = hourlyPattern.maxOf { it.count }.toFloat(); hourlyPattern.forEach { item -> Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) { Text("${item.hour}시", fontSize = 12.sp, color = muted, modifier = Modifier.width(36.dp)); Box(modifier = Modifier.weight(1f).height(20.dp).background(Color(0xFF1F2937), RoundedCornerShape(4.dp))) { Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(item.count / maxCount).background(accent, RoundedCornerShape(4.dp))) }; Text(" ${item.count}회", fontSize = 12.sp, color = green, modifier = Modifier.width(40.dp)) } } }; Spacer(Modifier.height(20.dp)); HorizontalDivider(color = Color(0xFF1F2937)); Spacer(Modifier.height(20.dp)); Text("📍 자주 간 목적지 TOP 10", fontSize = 14.sp, color = muted, modifier = Modifier.padding(bottom = 12.dp)); if (topDests.isEmpty()) { Text("데이터 쌓이는 중...", fontSize = 13.sp, color = muted) } else { topDests.forEachIndexed { index, dest -> Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Row(verticalAlignment = Alignment.CenterVertically) { Text("${index + 1}", fontSize = 13.sp, color = if (index < 3) accent else muted, modifier = Modifier.width(24.dp), fontWeight = if (index < 3) FontWeight.Bold else FontWeight.Normal); Text(dest.destination.take(20), fontSize = 13.sp, color = Color.White) }; Text("${dest.count}회", fontSize = 13.sp, color = green, fontWeight = FontWeight.Bold) }; if (index < topDests.size - 1) HorizontalDivider(color = Color(0xFF1F2937), thickness = 0.5.dp) } } }
                }
            }
        }
    }
}

@Composable
fun HeatmapView(cells: List<HeatmapCell>) {
    val accent = Color(0xFFF59E0B); val muted = Color(0xFF6B7280)
    if (cells.isEmpty()) { Text("이 장소에서 출발한 운행 데이터가 아직 없어요", fontSize = 12.sp, color = muted, modifier = Modifier.padding(vertical = 12.dp)); return }
    val maxCount = cells.maxOf { it.count }.toFloat(); val cellMap = cells.associateBy { "${it.dayOfWeek}-${it.hour}" }; val dayLabels = listOf("일","월","화","수","목","금","토")
    Column(modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
        Row { Box(modifier = Modifier.width(28.dp)); for (h in 0..23) { Box(modifier = Modifier.width(18.dp), contentAlignment = Alignment.Center) { if (h % 3 == 0) Text("$h", fontSize = 8.sp, color = muted) } } }
        for (d in 0..6) { Row(verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) { Text(dayLabels[d], fontSize = 10.sp, color = if (d == 0) Color(0xFFEF4444) else muted, fontWeight = FontWeight.Bold) }; for (h in 0..23) { val count = cellMap["$d-$h"]?.count ?: 0; val alpha = if (count > 0) (0.25f + 0.75f * (count / maxCount)) else 0f; Box(modifier = Modifier.size(18.dp).padding(1.dp).background(if (count > 0) accent.copy(alpha = alpha) else Color(0xFF1F2937), RoundedCornerShape(3.dp))) } } }
    }
}
