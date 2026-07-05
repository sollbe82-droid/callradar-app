package com.callradar.app.screen

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

data class Badge(val emoji: String, val name: String)
data class LevelInfo(val level: Int, val title: String, val next: Int)
data class HomeProfile(
    val nickname: String, val points: Int, val totalTrips: Int,
    val levelInfo: LevelInfo, val badges: List<Badge>,
    val guildName: String, val myRank: Int, val monthFare: Int,
    val carNumber: String, val employeeId: String, val workType: String,
    val driverType: String, val companyName: String
)
data class RecentTrip(val id: Int, val origin: String, val destination: String, val fare: Int, val platform: String, val time: String)
data class PlatformStat(val platform: String, val count: Int, val totalFare: Int)

@Composable
fun HomeScreen(nickname: String, userId: String, refreshKey: Int, onLogout: () -> Unit) {
    val bg = Color(0xFF0A0E1A); val card = Color(0xFF111827); val accent = Color(0xFFF59E0B)
    val green = Color(0xFF10B981); val red = Color(0xFFEF4444); val muted = Color(0xFF6B7280)
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    var goalFare by remember { mutableStateOf(prefs.getInt("goal_fare", 300000)) }
    var dailySanap by remember { mutableStateOf(prefs.getInt("daily_sanap", 0)) }
    var showGoalDialog by remember { mutableStateOf(false) }
    var goalInput by remember { mutableStateOf("") }
    var sanapInput by remember { mutableStateOf("") }
    var todayTrips by remember { mutableStateOf(0) }
    var todayFare by remember { mutableStateOf(0) }
    var profile by remember { mutableStateOf<HomeProfile?>(null) }
    var recentTrips by remember { mutableStateOf<List<RecentTrip>>(emptyList()) }
    var platformStats by remember { mutableStateOf<List<PlatformStat>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) } 
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val naviEnabled = remember { isNaviEnabled(context) }
    val scope = rememberCoroutineScope()

    // LPG/지출 설정 읽기
    val lpgType = prefs.getInt("lpg_type", 0) // 0=기사부담, 1=회사한도, 2=회사무료
    val lpgPrice = prefs.getInt("lpg_price", 0)
    val lpgDaily = prefs.getInt("lpg_daily", 0)
    val lpgCompanyLimit = prefs.getInt("lpg_company_limit", 0)
    val dailyExpense = prefs.getInt("daily_expense", 0)
    val subsidy = 221

    fun calcDailyLpgCost(): Int {
        val totalCost = lpgPrice * lpgDaily
        val subsidyTotal = subsidy * lpgDaily
        return when (lpgType) {
            0 -> totalCost - subsidyTotal // 기사 전액 부담 - 보조금
            1 -> { val overL = maxOf(0, lpgDaily - lpgCompanyLimit); (lpgPrice * overL) - subsidyTotal } // 초과분만 - 보조금
            2 -> -subsidyTotal // 회사 무료, 보조금만 받음
            else -> totalCost - subsidyTotal
        }
    }

    fun calcNetIncome(fare: Int, days: Int): Int {
        val lpgNet = calcDailyLpgCost()
        return fare - (dailySanap * days) - (lpgNet * days) - (dailyExpense * days)
    }

    if (showGoalDialog) {
        AlertDialog(onDismissRequest = { showGoalDialog = false },
            title = { Text("목표/사납금 설정", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Column {
                Text("하루 목표 매출", fontSize = 13.sp, color = muted, modifier = Modifier.padding(bottom = 4.dp))
                OutlinedTextField(value = goalInput, onValueChange = { goalInput = it.filter { c -> c.isDigit() } }, label = { Text("목표 금액 (원)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(200000, 300000, 400000, 500000).forEach { amount -> OutlinedButton(onClick = { goalInput = amount.toString() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text("${amount/10000}만", fontSize = 12.sp) } } }
                Spacer(Modifier.height(12.dp))
                Text("일 사납금 (법인택시)", fontSize = 13.sp, color = muted, modifier = Modifier.padding(bottom = 4.dp))
                OutlinedTextField(value = sanapInput, onValueChange = { sanapInput = it.filter { c -> c.isDigit() } }, label = { Text("사납금 (원, 없으면 0)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0, 100000, 120000, 150000).forEach { amount -> OutlinedButton(onClick = { sanapInput = amount.toString() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text(if (amount == 0) "없음" else "${amount/10000}만", fontSize = 12.sp) } } }
            } },
            confirmButton = { Button(onClick = {
                val g = goalInput.toIntOrNull(); if (g != null && g > 0) goalFare = g
                val s = sanapInput.toIntOrNull(); dailySanap = s ?: 0
                prefs.edit().putInt("goal_fare", goalFare).putInt("daily_sanap", dailySanap).apply()
                showGoalDialog = false
            }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showGoalDialog = false }) { Text("취소") } },
            containerColor = Color(0xFF111827))
    }

    LaunchedEffect(userId, refreshKey) {
        if (userId.isEmpty()) { isLoading = false; return@LaunchedEffect }
        scope.launch {
            try {
                val todayResponse = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/today/$userId").openConnection() as HttpURLConnection).apply { connectTimeout = 8000 }; conn.inputStream.bufferedReader().readText() }
                val todayJson = JSONObject(todayResponse)
                todayTrips = todayJson.optInt("tripCount", 0)
                todayFare = todayJson.optInt("todayFare", 0)

                val profileResponse = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/profile/$userId").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }
                val json = JSONObject(profileResponse)
                val levelJson = json.optJSONObject("levelInfo")
                val badgesJson = json.optJSONArray("badges")
                val badges = mutableListOf<Badge>()
                if (badgesJson != null) for (i in 0 until badgesJson.length()) { val b = badgesJson.getJSONObject(i); badges.add(Badge(b.getString("emoji"), b.getString("name"))) }
                profile = HomeProfile(
                    json.optString("nickname", nickname), json.optInt("points", 0), json.optInt("total_trips", 0),
                    LevelInfo(levelJson?.optInt("level", 1) ?: 1, levelJson?.optString("title", "신입기사") ?: "신입기사", levelJson?.optInt("next", 100) ?: 100),
                    badges, json.optString("guild_name", ""), json.optInt("myRank", 0), json.optInt("monthFare", 0),
                    json.optString("car_number", ""), json.optString("employee_id", ""),
                    json.optString("work_type", ""), json.optString("driver_type", ""), json.optString("company_name", "")
                )

                val tripsResponse = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/trips/$userId?limit=3").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }
                val tripsArr = JSONArray(tripsResponse)
                val tripList = mutableListOf<RecentTrip>()
                for (i in 0 until tripsArr.length()) {
                    val obj = tripsArr.getJSONObject(i)
                    val rawTime = obj.optString("started_at", "")
                    val time = try { val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); sdf.timeZone = java.util.TimeZone.getTimeZone("UTC"); val d = sdf.parse(rawTime); val out = SimpleDateFormat("HH:mm", Locale.KOREA); out.timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul"); out.format(d!!) } catch (e: Exception) { "" }
                    tripList.add(RecentTrip(obj.getInt("id"), obj.optString("origin", ""), obj.optString("destination", ""), obj.optInt("fare", 0), obj.optString("platform", ""), time))
                }
                recentTrips = tripList

                try {
                    val platResponse = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/stats/platform/$userId").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }
                    val platJson = JSONObject(platResponse)
                    val todayArr = platJson.getJSONArray("today")
                    val platList = mutableListOf<PlatformStat>()
                    for (i in 0 until todayArr.length()) {
                        val obj = todayArr.getJSONObject(i)
                        platList.add(PlatformStat(obj.getString("platform"), obj.getInt("count"), obj.getInt("total_fare")))
                    }
                    platformStats = platList
                } catch (e: Exception) { }

                isLoading = false
            } catch (e: Exception) { errorMessage = "서버 연결 실패"; isLoading = false }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth().background(card).padding(top = 48.dp, start = 20.dp, end = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text("콜레이더", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent); Text("β", fontSize = 11.sp, color = muted) }
                profile?.let { p ->
                    Text(buildString { append(p.nickname); if (p.carNumber.isNotEmpty()) append(" · ${p.carNumber}") }, fontSize = 12.sp, color = muted)
                } ?: Text("${nickname}님", fontSize = 12.sp, color = muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = if (naviEnabled) Color(0xFF064E3B) else Color(0xFF7F1D1D)), shape = RoundedCornerShape(20.dp)) {
                    Text(if (naviEnabled) "● ON" else "● OFF", fontSize = 11.sp, color = if (naviEnabled) green else red, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                }
                Button(onClick = { onLogout() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151)), shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp)) { Text("종료", fontSize = 11.sp, color = Color.White) }
            }
        }

        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 월 누적 + 순수익
            val cal = Calendar.getInstance(); val month = cal.get(Calendar.MONTH) + 1
            val monthFare = profile?.monthFare ?: 0
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937)), shape = RoundedCornerShape(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("${month}월 누적", fontSize = 13.sp, color = muted)
                        if (dailySanap > 0 || lpgPrice > 0 || dailyExpense > 0) {
                            val workDays = cal.get(Calendar.DAY_OF_MONTH)
                            val netIncome = calcNetIncome(monthFare, workDays)
                            Text("순수익 ${String.format("%,d", netIncome)}원", fontSize = 11.sp, color = if (netIncome > 0) accent else red)
                        }
                    }
                    Text(if (monthFare > 0) "${String.format("%,d", monthFare)}원" else "0원", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (monthFare > 0) green else muted)
                }
            }

            // 오늘 매출
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("오늘 매출", fontSize = 12.sp, color = muted)
                            Text(if (todayFare > 0) "${String.format("%,d", todayFare)}원" else "0원", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = if (todayFare > 0) green else muted)
                            if (dailySanap > 0 || lpgPrice > 0 || dailyExpense > 0) {
                                val todayNet = calcNetIncome(todayFare, 1)
                                Text("순수익 ${String.format("%,d", todayNet)}원", fontSize = 11.sp, color = if (todayNet > 0) accent else red)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            val progress = (todayFare.toFloat() / goalFare.toFloat()).coerceIn(0f, 1f)
                            Text("${(progress * 100).toInt()}%", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (progress >= 1f) green else accent)
                            TextButton(onClick = { goalInput = goalFare.toString(); sanapInput = dailySanap.toString(); showGoalDialog = true }, contentPadding = PaddingValues(0.dp)) { Text("목표 ${String.format("%,d", goalFare)}원 ✏️", fontSize = 11.sp, color = muted) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val progress = (todayFare.toFloat() / goalFare.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp), color = if (progress >= 1f) green else accent, trackColor = Color(0xFF1F2937))
                    Spacer(Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$todayTrips", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White); Text("오늘 콜", fontSize = 11.sp, color = muted) }
                        Box(modifier = Modifier.width(1.dp).height(40.dp).background(Color(0xFF1F2937)))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(if (todayTrips > 0 && todayFare > 0) "${String.format("%,d", todayFare / todayTrips)}" else "-", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White); Text("콜 평균", fontSize = 11.sp, color = muted) }
                        Box(modifier = Modifier.width(1.dp).height(40.dp).background(Color(0xFF1F2937)))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(if (todayFare >= goalFare) "달성!" else "${String.format("%,d", goalFare - todayFare)}", fontSize = if (todayFare >= goalFare) 18.sp else 20.sp, fontWeight = FontWeight.Bold, color = if (todayFare >= goalFare) green else Color.White); Text(if (todayFare >= goalFare) "목표완료" else "남은금액", fontSize = 11.sp, color = muted) }
                    }
                }
            }

            // 플랫폼별 매출
            if (platformStats.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("플랫폼별 매출", fontSize = 12.sp, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                        val feeKakao = prefs.getInt("fee_kakao", 0)
                        val feeUber = prefs.getInt("fee_uber", 0)
                        val feeTmoney = prefs.getInt("fee_tmoney", 0)
                        platformStats.forEach { stat ->
                            val feeRate = when { stat.platform.contains("카카오") -> feeKakao; stat.platform.contains("우버") -> feeUber; stat.platform.contains("티머니") -> feeTmoney; else -> 0 }
                            val netFare = stat.totalFare * (100 - feeRate) / 100
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stat.platform, fontSize = 13.sp, color = Color.White)
                                    Text("${stat.count}건", fontSize = 12.sp, color = muted)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${String.format("%,d", stat.totalFare)}원", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = green)
                                    if (feeRate > 0) { Text("실수령 ${String.format("%,d", netFare)}원 (${feeRate}%)", fontSize = 10.sp, color = accent) }
                                }
                            }
                        }
                    }
                }
            }
// 에러 메시지
if (errorMessage != null) {
Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)), shape = RoundedCornerShape(10.dp)) {
Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
Text("⚠️ $errorMessage", fontSize = 12.sp, color = Color.White)
TextButton(onClick = { errorMessage = null; isLoading = true; scope.launch { try { val todayResponse = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/today/$userId").openConnection() as HttpURLConnection).apply { connectTimeout = 8000 }; conn.inputStream.bufferedReader().readText() }; val todayJson = JSONObject(todayResponse); todayTrips = todayJson.optInt("tripCount", 0); todayFare = todayJson.optInt("todayFare", 0); errorMessage = null; isLoading = false } catch (e: Exception) { errorMessage = "서버 연결 실패"; isLoading = false } } }) { Text("재시도", fontSize = 11.sp, color = accent) }
}
}
}
            // 최근 운행
            if (recentTrips.isNotEmpty()) {
                Text("최근 운행", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = muted)
                recentTrips.forEach { trip ->
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (trip.origin.isNotEmpty()) { Text(trip.origin.take(6), fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(" → ", fontSize = 12.sp, color = muted) }
                                    Text(trip.destination.take(10), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("${trip.platform} · ${trip.time}", fontSize = 11.sp, color = muted)
                            }
                            if (trip.fare > 0) { Text("${String.format("%,d", trip.fare)}원", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = green) }
                            else { Text("금액입력", fontSize = 12.sp, color = accent) }
                        }
                    }
                }
            }
        }
    }
}

private fun isNaviEnabled(context: Context): Boolean {
    return try { Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains("com.callradar.app") == true } catch (e: Exception) { false }
}
