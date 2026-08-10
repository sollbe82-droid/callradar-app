package com.callradar.app.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val SERVER_URL = Config.SERVER_URL

data class RankItem(val rank: Int, val nickname: String, val companyName: String, val weekTrips: Int, val points: Int, val isMe: Boolean)
data class GuildItem(val id: Int, val name: String, val memberCount: Int, val weekTrips: Int, val avgTrips: Double)
data class GuildMember(val id: Int, val nickname: String, val role: String)
data class MyGuild(val id: Int, val name: String, val inviteCode: String, val role: String, val memberCount: Int, val members: List<GuildMember>)

// [v18] 랭킹 인메모리 캐시 — 탭 재진입/테마전환 때 화면이 비워졌다 다시 차는 "초기화" 깜빡임 방지
private object RankingCache {
    var individual: List<RankItem> = emptyList()
    var company: List<Triple<String, Int, Double>> = emptyList()
    var guild: List<GuildItem> = emptyList()
    var myGuild: MyGuild? = null
    var loaded: Boolean = false
}

@Composable
fun RankingScreen(userId: String) {
    val bg = AppTheme.bg; val card = AppTheme.card; val accent = Color(0xFFF59E0B); val green = Color(0xFF10B981); val muted = AppTheme.muted; val red = Color(0xFFEF4444)
    var selectedTab by remember { mutableStateOf(0) }
    // [v18] 재진입·테마전환 시 초기화 깜빡임 방지: 마지막 로드값을 캐시에서 즉시 표시, 백그라운드 갱신
    var individualRanking by remember { mutableStateOf<List<RankItem>>(RankingCache.individual) }
    var companyRanking by remember { mutableStateOf<List<Triple<String, Int, Double>>>(RankingCache.company) }
    var guildRanking by remember { mutableStateOf<List<GuildItem>>(RankingCache.guild) }
    var myGuild by remember { mutableStateOf<MyGuild?>(RankingCache.myGuild) }
    var isLoading by remember { mutableStateOf(!RankingCache.loaded) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showGuildDialog by remember { mutableStateOf(false) }
    var showMyGuildDialog by remember { mutableStateOf(false) }
    var guildDialogMode by remember { mutableStateOf("") }
    var companyInput by remember { mutableStateOf("") }
    var driverTypeInput by remember { mutableStateOf("personal") }
    var carNumberInput by remember { mutableStateOf("") }
    var employeeIdInput by remember { mutableStateOf("") }
    var workTypeInput by remember { mutableStateOf("") }
    var guildNameInput by remember { mutableStateOf("") }
    var guildDescInput by remember { mutableStateOf("") }
    var guildCodeInput by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf("") }
    var statusIsSuccess by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadData() {
        scope.launch {
            try {
                val indResponse = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/ranking/individual").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 30000 }; conn.inputStream.bufferedReader().readText() }
                val indArray = JSONArray(indResponse); val indList = mutableListOf<RankItem>()
                for (i in 0 until indArray.length()) { val obj = indArray.getJSONObject(i); indList.add(RankItem(i+1, obj.optString("nickname","기사님"), obj.optString("company_name",""), obj.optInt("week_trips",0), obj.optInt("points",0), obj.optString("id")==userId||obj.optInt("id").toString()==userId)) }
                val compResponse = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/ranking/company").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 30000 }; conn.inputStream.bufferedReader().readText() }
                val compArray = JSONArray(compResponse); val compList = mutableListOf<Triple<String, Int, Double>>()
                for (i in 0 until compArray.length()) { val obj = compArray.getJSONObject(i); compList.add(Triple(obj.optString("company_name",""), obj.optInt("member_count",0), obj.optDouble("avg_trips",0.0))) }
                val guildResponse = withContext(Dispatchers.IO) { val conn = (URL("$SERVER_URL/api/ranking/guild").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 30000 }; conn.inputStream.bufferedReader().readText() }
                val guildArray = JSONArray(guildResponse); val guildList = mutableListOf<GuildItem>()
                for (i in 0 until guildArray.length()) { val obj = guildArray.getJSONObject(i); guildList.add(GuildItem(obj.optInt("id"), obj.optString("name",""), obj.optInt("member_count",0), obj.optInt("week_trips",0), obj.optDouble("avg_trips",0.0))) }
                val myGuildResponse = withContext(Dispatchers.IO) { try { val conn = (URL("$SERVER_URL/api/guilds/my/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 30000 }; conn.inputStream.bufferedReader().readText() } catch (e: Exception) { "null" } }
                if (myGuildResponse != "null" && myGuildResponse.isNotEmpty()) {
                    try { val mg = JSONObject(myGuildResponse); val membersArray = mg.optJSONArray("members"); val membersList = mutableListOf<GuildMember>(); if (membersArray != null) for (i in 0 until membersArray.length()) { val m = membersArray.getJSONObject(i); membersList.add(GuildMember(m.optInt("id"), m.optString("nickname",""), m.optString("role","member"))) }; myGuild = MyGuild(mg.optInt("id"), mg.optString("name",""), mg.optString("invite_code",""), mg.optString("role","member"), mg.optInt("member_count",0), membersList) } catch (e: Exception) { myGuild = null }
                }
                individualRanking = indList; companyRanking = compList; guildRanking = guildList; isLoading = false
                RankingCache.individual = indList; RankingCache.company = compList; RankingCache.guild = guildList; RankingCache.myGuild = myGuild; RankingCache.loaded = true
            } catch (e: Exception) { isLoading = false }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    if (showProfileDialog) {
        AlertDialog(onDismissRequest = { showProfileDialog = false },
            title = { Text("프로필 설정", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("기사 유형", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("personal" to "개인택시", "corporate" to "법인택시").forEach { (type, label) -> FilterChip(selected = driverTypeInput == type, onClick = { driverTypeInput = type }, label = { Text(label, fontSize = 12.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = Color(0xFF6B7280))) } }
                OutlinedTextField(value = carNumberInput, onValueChange = { carNumberInput = it }, label = { Text("차량번호 (예: 서울 37바 3537)", color = Color(0xFF6B7280)) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                if (driverTypeInput == "corporate") {
                    OutlinedTextField(value = companyInput, onValueChange = { companyInput = it }, label = { Text("회사명", color = Color(0xFF6B7280)) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                    OutlinedTextField(value = employeeIdInput, onValueChange = { employeeIdInput = it }, label = { Text("사번", color = Color(0xFF6B7280)) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                    Text("근무형태", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("주간", "야간", "일차").forEach { type -> FilterChip(selected = workTypeInput == type, onClick = { workTypeInput = type }, label = { Text(type, fontSize = 12.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = Color(0xFF6B7280))) } }
                }
            } },
            confirmButton = { Button(onClick = { scope.launch { try { withContext(Dispatchers.IO) { val json = JSONObject().apply { put("company_name", if (companyInput.isNotEmpty()) companyInput else null); put("driver_type", driverTypeInput); put("car_number", if (carNumberInput.isNotEmpty()) carNumberInput else null); put("employee_id", if (employeeIdInput.isNotEmpty()) employeeIdInput else null); put("work_type", if (workTypeInput.isNotEmpty()) workTypeInput else null) }; val conn = (URL("$SERVER_URL/api/users/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 15000 }; conn.outputStream.write(json.toString().toByteArray()); conn.responseCode } } catch (e: Exception) { } }; showProfileDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showProfileDialog = false }) { Text("취소") } }, containerColor = AppTheme.card)
    }

    if (showGuildDialog) {
        AlertDialog(onDismissRequest = { showGuildDialog = false; statusMsg = "" }, title = { Text(if (guildDialogMode == "create") "길드 만들기 🛡️" else "길드 가입 🛡️", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column { if (guildDialogMode == "create") { OutlinedTextField(value = guildNameInput, onValueChange = { guildNameInput = it }, label = { Text("길드 이름", color = Color(0xFF6B7280)) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text)); Spacer(Modifier.height(8.dp)); OutlinedTextField(value = guildDescInput, onValueChange = { guildDescInput = it }, label = { Text("소개 (선택)", color = Color(0xFF6B7280)) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text)) } else { OutlinedTextField(value = guildCodeInput, onValueChange = { guildCodeInput = it.uppercase() }, label = { Text("초대 코드 6자리", color = Color(0xFF6B7280)) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text)) }; if (statusMsg.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Card(colors = CardDefaults.cardColors(containerColor = if (statusIsSuccess) Color(0xFF064E3B) else Color(0xFF7F1D1D)), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) { Text(statusMsg, fontSize = 13.sp, color = if (statusIsSuccess) green else Color(0xFFFCA5A5), modifier = Modifier.padding(12.dp)) } } } },
            confirmButton = { if (!statusIsSuccess) Button(onClick = { scope.launch { try { val response = withContext(Dispatchers.IO) { if (guildDialogMode == "create") { val json = JSONObject().apply { put("user_id", userId); put("name", guildNameInput); put("description", guildDescInput) }; val conn = (URL("$SERVER_URL/api/guilds").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 15000 }; conn.outputStream.write(json.toString().toByteArray()); conn.inputStream.bufferedReader().readText() } else { val json = JSONObject().apply { put("user_id", userId); put("invite_code", guildCodeInput) }; val conn = (URL("$SERVER_URL/api/guilds/join").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 15000 }; conn.outputStream.write(json.toString().toByteArray()); conn.inputStream.bufferedReader().readText() } }; val json = JSONObject(response); statusIsSuccess = true; statusMsg = if (guildDialogMode == "create") "✅ '${json.optString("name")}' 생성 완료!\n초대코드: ${json.optString("invite_code")}" else "✅ 가입 완료!"; loadData() } catch (e: Exception) { statusIsSuccess = false; statusMsg = "오류가 발생했어요" } } }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text(if (guildDialogMode == "create") "만들기" else "가입", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showGuildDialog = false; statusMsg = ""; statusIsSuccess = false }) { Text(if (statusIsSuccess) "닫기" else "취소") } }, containerColor = AppTheme.card)
    }

    if (showMyGuildDialog && myGuild != null) {
        val mg = myGuild!!
        AlertDialog(onDismissRequest = { showMyGuildDialog = false }, title = { Text("🛡️ ${mg.name}", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = { Column { if (mg.role == "master") { Card(colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) { Column(modifier = Modifier.padding(12.dp)) { Text("초대코드", fontSize = 12.sp, color = Color(0xFF6B7280)); Text(mg.inviteCode, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = accent, letterSpacing = 4.sp); Text("멤버에게 이 코드를 공유하세요", fontSize = 11.sp, color = Color(0xFF6B7280)) } } }; Text("멤버 ${mg.memberCount}명", fontSize = 13.sp, color = Color(0xFF6B7280), modifier = Modifier.padding(bottom = 8.dp)); mg.members.forEach { member -> Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Row(verticalAlignment = Alignment.CenterVertically) { Text(if (member.role == "master") "👑" else "👤", fontSize = 14.sp); Spacer(Modifier.width(8.dp)); Text(member.nickname, fontSize = 14.sp, color = if (member.role == "master") accent else Color.White) }; if (mg.role == "master" && member.role != "master") { TextButton(onClick = { scope.launch { try { withContext(Dispatchers.IO) { val json = JSONObject().apply { put("user_id", userId) }; val conn = (URL("$SERVER_URL/api/guilds/${mg.id}/members/${member.id}").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "DELETE"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 15000 }; conn.outputStream.write(json.toString().toByteArray()); conn.responseCode }; loadData(); showMyGuildDialog = false } catch (e: Exception) { } } }) { Text("추방", fontSize = 12.sp, color = red) } } } } } },
            confirmButton = { Button(onClick = { scope.launch { try { withContext(Dispatchers.IO) { val json = JSONObject().apply { put("user_id", userId) }; val url = if (mg.role == "master") "$SERVER_URL/api/guilds/${mg.id}" else "$SERVER_URL/api/guilds/${mg.id}/leave"; val conn = (URL(url).openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "DELETE"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 15000 }; conn.outputStream.write(json.toString().toByteArray()); conn.responseCode }; myGuild = null; showMyGuildDialog = false; loadData() } catch (e: Exception) { } } }, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text(if (mg.role == "master") "길드 해체" else "길드 탈퇴", color = AppTheme.text) } },
            dismissButton = { OutlinedButton(onClick = { showMyGuildDialog = false }) { Text("닫기") } }, containerColor = AppTheme.card)
    }

    Column(modifier = Modifier.fillMaxSize().background(bg)) {
        Row(modifier = Modifier.fillMaxWidth().background(AppTheme.card).padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("개인", "회사", "길드").forEachIndexed { index, title -> FilterChip(selected = selectedTab == index, onClick = { selectedTab = index }, label = { Text(title, fontSize = 13.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = Color(0xFF6B7280))) } }
            Row { if (myGuild != null) { TextButton(onClick = { showMyGuildDialog = true }) { Text("내 길드", fontSize = 12.sp, color = green) } }; TextButton(onClick = { showProfileDialog = true }) { Text("프로필", fontSize = 12.sp, color = accent) } }
        }
        if (isLoading) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) } }
        else {
            when (selectedTab) {
                0 -> { if (individualRanking.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("🏆", fontSize = 48.sp); Spacer(Modifier.height(16.dp)); Text("아직 랭킹 데이터가 없어요", fontSize = 14.sp, color = Color(0xFF6B7280)) } } } else { LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { itemsIndexed(individualRanking) { _, item -> Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (item.isMe) AppTheme.surface2 else card), shape = RoundedCornerShape(12.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(when (item.rank) { 1->"🥇"; 2->"🥈"; 3->"🥉"; else->"${item.rank}" }, fontSize = if (item.rank<=3) 20.sp else 14.sp, color = if (item.rank<=3) accent else Color(0xFF6B7280), modifier = Modifier.width(36.dp)); Column(modifier = Modifier.weight(1f)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(item.nickname, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (item.isMe) accent else AppTheme.text); if (item.isMe) { Spacer(Modifier.width(6.dp)); Text("나", fontSize = 11.sp, color = accent) } }; if (item.companyName.isNotEmpty() && item.companyName != "null") Text(item.companyName, fontSize = 12.sp, color = Color(0xFF6B7280)) }; Column(horizontalAlignment = Alignment.End) { Text("${item.weekTrips}콜", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = green); Text("${item.points}pt", fontSize = 11.sp, color = Color(0xFF6B7280)) } } } } } } }
                1 -> { if (companyRanking.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("🏢", fontSize = 48.sp); Spacer(Modifier.height(16.dp)); Text("아직 회사 랭킹이 없어요", fontSize = 14.sp, color = Color(0xFF6B7280)); Text("프로필에서 회사명을 입력해주세요", fontSize = 13.sp, color = Color(0xFF6B7280)) } } } else { LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { itemsIndexed(companyRanking) { index, (name, members, avg) -> Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(when (index+1) { 1->"🥇"; 2->"🥈"; 3->"🥉"; else->"${index+1}" }, fontSize = if (index<3) 20.sp else 14.sp, color = if (index<3) accent else Color(0xFF6B7280), modifier = Modifier.width(36.dp)); Column(modifier = Modifier.weight(1f)) { Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text); Text("${members}명", fontSize = 12.sp, color = Color(0xFF6B7280)) }; Column(horizontalAlignment = Alignment.End) { Text("평균 ${avg}콜", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = green); Text("이번 주", fontSize = 11.sp, color = Color(0xFF6B7280)) } } } } } } }
                2 -> { Column(modifier = Modifier.fillMaxSize()) { if (myGuild == null) { Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { guildDialogMode = "create"; guildNameInput = ""; guildDescInput = ""; statusMsg = ""; statusIsSuccess = false; showGuildDialog = true }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(10.dp)) { Text("길드 만들기", color = Color.Black, fontWeight = FontWeight.Bold) }; OutlinedButton(onClick = { guildDialogMode = "join"; guildCodeInput = ""; statusMsg = ""; statusIsSuccess = false; showGuildDialog = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text("코드로 가입", fontWeight = FontWeight.Bold) } } } else { Card(modifier = Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = AppTheme.surface2), shape = RoundedCornerShape(12.dp)) { Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("🛡️ ${myGuild!!.name}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = accent); Text("${myGuild!!.memberCount}명 | ${if (myGuild!!.role == "master") "👑 길드장" else "멤버"}", fontSize = 12.sp, color = Color(0xFF6B7280)) } } } }; if (guildRanking.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("🛡️", fontSize = 48.sp); Spacer(Modifier.height(16.dp)); Text("아직 길드가 없어요", fontSize = 14.sp, color = Color(0xFF6B7280)) } } } else { LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { itemsIndexed(guildRanking) { index, guild -> Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (myGuild?.id == guild.id) AppTheme.surface2 else card), shape = RoundedCornerShape(12.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(when (index+1) { 1->"🥇"; 2->"🥈"; 3->"🥉"; else->"${index+1}" }, fontSize = if (index<3) 20.sp else 14.sp, color = if (index<3) accent else Color(0xFF6B7280), modifier = Modifier.width(36.dp)); Column(modifier = Modifier.weight(1f)) { Row(verticalAlignment = Alignment.CenterVertically) { Text("🛡️ ${guild.name}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text); if (myGuild?.id == guild.id) { Spacer(Modifier.width(6.dp)); Text("내 길드", fontSize = 11.sp, color = accent) } }; Text("${guild.memberCount}명", fontSize = 12.sp, color = Color(0xFF6B7280)) }; Column(horizontalAlignment = Alignment.End) { Text("평균 ${guild.avgTrips}콜", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = green); Text("이번 주", fontSize = 11.sp, color = Color(0xFF6B7280)) } } } } } } } }
            }
        }
    }
}
