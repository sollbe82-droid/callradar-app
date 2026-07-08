// ===== MoreScreen v4 (2026-07-08) =====
// v4: 권한상태 자동갱신 - 화면복귀시 접근성/알림/배터리/위치 재확인 (켜고 돌아오면 바로 녹색)
// v3d: 탭 위에 "콜레이더 더보기" 제목바 추가 (탭이 최상단에 붙어보이던 문제)
// v3c: 접근성 로그아웃버그 수정 + 제한된설정 경로를 앱목록(ACTION_MANAGE_APPLICATIONS)으로
// v3: 자동화 권한 카드 추가 (배터리최적화/위치/제한된설정 안내), 헤더 "설정"→"자동화 & 정산"
// v2: 기사유형 설정 + 서버연동 + 저작권수정
package com.callradar.app.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val SETTINGS_SERVER = "https://callradar-server.onrender.com"

@Composable
fun MoreScreen(userId: String, onLogout: () -> Unit) {
    val bg = Color(0xFF0A0E1A); val card = Color(0xFF111827); val accent = Color(0xFFF59E0B)
    val green = Color(0xFF10B981); val red = Color(0xFFEF4444); val muted = Color(0xFF6B7280)
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().background(bg)) {
        // 화면 제목 바
        Row(modifier = Modifier.fillMaxWidth().background(card).padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("콜레이더", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent)
            Text("더보기", fontSize = 14.sp, color = muted)
        }
        Row(modifier = Modifier.fillMaxWidth().background(card).padding(top = 4.dp, start = 14.dp, end = 14.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("분석", "랭킹", "링크", "설정").forEachIndexed { index, title ->
                FilterChip(selected = selectedTab == index, onClick = { selectedTab = index }, label = { Text(title, fontSize = 12.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = Color(0xFF1F2937), labelColor = muted))
            }
        }
        when (selectedTab) {
            0 -> StatsScreen(userId = userId)
            1 -> RankingScreen(userId = userId)
            2 -> LinksView(context = context, card = card, accent = accent, muted = muted)
            3 -> SettingsView(userId = userId, context = context, card = card, accent = accent, green = green, red = red, muted = muted, onLogout = onLogout)
        }
    }
}

@Composable
private fun SettingsView(userId: String, context: Context, card: Color, accent: Color, green: Color, red: Color, muted: Color, onLogout: () -> Unit) {
    val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    var driverType by remember { mutableStateOf(prefs.getString("driver_type", "personal") ?: "personal") }
    var affiliation by remember { mutableStateOf(prefs.getString("affiliation", "none") ?: "none") }
    var profitShare by remember { mutableStateOf(prefs.getInt("profit_share", 100)) }
    var lpgRefundRate by remember { mutableStateOf(prefs.getInt("lpg_refund_rate", 0)) }
    var workDays by remember { mutableStateOf(prefs.getInt("work_days", 26)) }
    var showTypeDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }

    fun saveSettingsToServer() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val json = JSONObject().apply {
                        put("user_id", userId); put("driver_type", driverType); put("affiliation", affiliation)
                        put("commission_rate", (prefs.getInt("fee_kakao",0) + prefs.getInt("fee_uber",0) + prefs.getInt("fee_tmoney",0)))
                        put("daily_payment", prefs.getInt("daily_sanap", 0)); put("work_days", workDays)
                        put("profit_share", profitShare); put("lpg_refund_rate", lpgRefundRate)
                    }
                    val conn = (URL("$SETTINGS_SERVER/api/driver-settings").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true }
                    conn.outputStream.write(json.toString().toByteArray()); conn.responseCode
                }
            } catch (e: Exception) { }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val resp = withContext(Dispatchers.IO) { val conn = (URL("$SETTINGS_SERVER/api/driver-settings/$userId").openConnection() as HttpURLConnection).apply { connectTimeout = 5000 }; conn.inputStream.bufferedReader().readText() }
            val j = JSONObject(resp)
            driverType = j.optString("driver_type", "personal"); affiliation = j.optString("affiliation", "none")
            profitShare = j.optInt("profit_share", 100); lpgRefundRate = j.optInt("lpg_refund_rate", 0); workDays = j.optInt("work_days", 26)
            prefs.edit().putString("driver_type", driverType).putString("affiliation", affiliation).putInt("profit_share", profitShare).putInt("lpg_refund_rate", lpgRefundRate).putInt("work_days", workDays).apply()
        } catch (e: Exception) { }
    }

    var dailySanap by remember { mutableStateOf(prefs.getInt("daily_sanap", 0)) }
    var showSanapDialog by remember { mutableStateOf(false) }
    var sanapInput by remember { mutableStateOf("") }
    var showFeeDialog by remember { mutableStateOf(false) }
    var kakaoFee by remember { mutableStateOf(prefs.getInt("fee_kakao", 0)) }
    var uberFee by remember { mutableStateOf(prefs.getInt("fee_uber", 0)) }
    var tmoneyFee by remember { mutableStateOf(prefs.getInt("fee_tmoney", 0)) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var lpgPrice by remember { mutableStateOf(prefs.getInt("lpg_price", 1050)) }
    var lpgDaily by remember { mutableStateOf(prefs.getInt("lpg_daily", 40)) }
    var dailyExpense by remember { mutableStateOf(prefs.getInt("daily_expense", 0)) }
    var showLpgDialog by remember { mutableStateOf(false) }
    var showExpenseDialog by remember { mutableStateOf(false) }

    // 권한 상태 (화면 복귀 시 재확인 위해 state로)
    fun checkNavi() = try { Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains("com.callradar.app") == true } catch (e: Exception) { false }
    fun checkNotif() = try { Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.contains("com.callradar.app") == true } catch (e: Exception) { false }
    fun checkBattery() = try { val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager; pm.isIgnoringBatteryOptimizations(context.packageName) } catch (e: Exception) { false }
    fun checkLocation() = try { context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED } catch (e: Exception) { false }

    var naviEnabled by remember { mutableStateOf(checkNavi()) }
    var notifEnabled by remember { mutableStateOf(checkNotif()) }
    var batteryOk by remember { mutableStateOf(checkBattery()) }
    var locationOk by remember { mutableStateOf(checkLocation()) }

    // 화면 복귀(ON_RESUME) 시 권한 상태 재확인 - 설정 켜고 돌아오면 바로 녹색
    val moreLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(moreLifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                naviEnabled = checkNavi(); notifEnabled = checkNotif(); batteryOk = checkBattery(); locationOk = checkLocation()
            }
        }
        moreLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { moreLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showSanapDialog) {
        AlertDialog(onDismissRequest = { showSanapDialog = false },
            title = { Text("일 사납금 설정", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Column {
                Text("법인택시 일 사납금 (개인택시는 0)", fontSize = 12.sp, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(value = sanapInput, onValueChange = { sanapInput = it.filter { c -> c.isDigit() } }, label = { Text("사납금 (원)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0, 100000, 120000, 150000).forEach { amount -> OutlinedButton(onClick = { sanapInput = amount.toString() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text(if (amount == 0) "없음" else "${amount/10000}만", fontSize = 12.sp) } } }
            } },
            confirmButton = { Button(onClick = { dailySanap = sanapInput.toIntOrNull() ?: 0; prefs.edit().putInt("daily_sanap", dailySanap).apply(); showSanapDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showSanapDialog = false }) { Text("취소") } }, containerColor = Color(0xFF111827))
    }

    if (showFeeDialog) {
        var kInput by remember { mutableStateOf(kakaoFee.toString()) }
        var uInput by remember { mutableStateOf(uberFee.toString()) }
        var tInput by remember { mutableStateOf(tmoneyFee.toString()) }
        AlertDialog(onDismissRequest = { showFeeDialog = false },
            title = { Text("플랫폼별 수수료 설정", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("가맹 기사만 입력 (비가맹은 0%)", fontSize = 12.sp, color = muted)
                OutlinedTextField(value = kInput, onValueChange = { kInput = it.filter { c -> c.isDigit() } }, label = { Text("카카오T 수수료 (%)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                OutlinedTextField(value = uInput, onValueChange = { uInput = it.filter { c -> c.isDigit() } }, label = { Text("우버 수수료 (%)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                OutlinedTextField(value = tInput, onValueChange = { tInput = it.filter { c -> c.isDigit() } }, label = { Text("티머니고 수수료 (%)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
            } },
            confirmButton = { Button(onClick = { kakaoFee = kInput.toIntOrNull() ?: 0; uberFee = uInput.toIntOrNull() ?: 0; tmoneyFee = tInput.toIntOrNull() ?: 0; prefs.edit().putInt("fee_kakao", kakaoFee).putInt("fee_uber", uberFee).putInt("fee_tmoney", tmoneyFee).apply(); showFeeDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showFeeDialog = false }) { Text("취소") } }, containerColor = Color(0xFF111827))
    }

    if (showLpgDialog) {
        var priceInput by remember { mutableStateOf(lpgPrice.toString()) }
        var dailyLInput by remember { mutableStateOf(lpgDaily.toString()) }
        AlertDialog(onDismissRequest = { showLpgDialog = false },
            title = { Text("LPG 정산 설정", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = priceInput, onValueChange = { priceInput = it.filter { c -> c.isDigit() } }, label = { Text("LPG 단가 (원/L)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                OutlinedTextField(value = dailyLInput, onValueChange = { dailyLInput = it.filter { c -> c.isDigit() } }, label = { Text("일 평균 사용량 (L)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                val subsidy = 221
                val cost = (priceInput.toIntOrNull() ?: 0) * (dailyLInput.toIntOrNull() ?: 0)
                val subsidyTotal = subsidy * (dailyLInput.toIntOrNull() ?: 0)
                val net = cost - subsidyTotal
                if (cost > 0) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937)), shape = RoundedCornerShape(8.dp)) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("일 연료비: ${String.format("%,d", cost)}원", fontSize = 12.sp, color = Color.White)
                            Text("유가보조금: -${String.format("%,d", subsidyTotal)}원 (221원/L)", fontSize = 12.sp, color = green)
                            Text("실 연료비: ${String.format("%,d", net)}원", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
                        }
                    }
                }
            } },
            confirmButton = { Button(onClick = { lpgPrice = priceInput.toIntOrNull() ?: 1050; lpgDaily = dailyLInput.toIntOrNull() ?: 40; prefs.edit().putInt("lpg_price", lpgPrice).putInt("lpg_daily", lpgDaily).apply(); showLpgDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showLpgDialog = false }) { Text("취소") } }, containerColor = Color(0xFF111827))
    }

    if (showExpenseDialog) {
        var expInput by remember { mutableStateOf(dailyExpense.toString()) }
        AlertDialog(onDismissRequest = { showExpenseDialog = false },
            title = { Text("일 고정 지출", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("식비, 세차비 등 하루 평균 지출", fontSize = 12.sp, color = muted)
                OutlinedTextField(value = expInput, onValueChange = { expInput = it.filter { c -> c.isDigit() } }, label = { Text("일 지출 (원)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0, 10000, 15000, 20000, 30000).forEach { amount -> OutlinedButton(onClick = { expInput = amount.toString() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text(if (amount == 0) "없음" else "${amount/10000}만", fontSize = 12.sp) } } }
            } },
            confirmButton = { Button(onClick = { dailyExpense = expInput.toIntOrNull() ?: 0; prefs.edit().putInt("daily_expense", dailyExpense).apply(); showExpenseDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showExpenseDialog = false }) { Text("취소") } }, containerColor = Color(0xFF111827))
    }

    // 기사유형 선택 다이얼로그
    if (showTypeDialog) {
        AlertDialog(onDismissRequest = { showTypeDialog = false },
            title = { Text("기사 유형 설정", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("운행 형태", fontSize = 13.sp, color = muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("개인기사" to "personal", "법인기사" to "corporate").forEach { (label, value) ->
                        FilterChip(selected = driverType == value, onClick = { driverType = value }, label = { Text(label, fontSize = 12.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = Color(0xFF1F2937), labelColor = muted))
                    }
                }
                Text("가맹 형태", fontSize = 13.sp, color = muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("비가맹" to "none", "카카오가맹" to "kakao", "우버가맹" to "uber").forEach { (label, value) ->
                        FilterChip(selected = affiliation == value, onClick = { affiliation = value }, label = { Text(label, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = green, selectedLabelColor = Color.Black, containerColor = Color(0xFF1F2937), labelColor = muted))
                    }
                }
                if (driverType == "corporate") {
                    Text("만근일 수", fontSize = 13.sp, color = muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(25, 26).forEach { d -> FilterChip(selected = workDays == d, onClick = { workDays = d }, label = { Text("${d}일", fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = Color(0xFF1F2937), labelColor = muted)) }
                    }
                }
                Text("💡 수수료·사납금은 아래 항목에서 직접 입력하세요", fontSize = 11.sp, color = muted)
            } },
            confirmButton = { Button(onClick = { prefs.edit().putString("driver_type", driverType).putString("affiliation", affiliation).putInt("work_days", workDays).apply(); saveSettingsToServer(); showTypeDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showTypeDialog = false }) { Text("취소") } }, containerColor = Color(0xFF111827))
    }

    // 초과수익 분배율 다이얼로그 (법인)
    if (showShareDialog) {
        var shareInput by remember { mutableStateOf(profitShare.toString()) }
        AlertDialog(onDismissRequest = { showShareDialog = false },
            title = { Text("초과수익 분배율", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("사납금 초과분 중 기사 몫 (%)", fontSize = 12.sp, color = muted)
                OutlinedTextField(value = shareInput, onValueChange = { v -> val f = v.filter { it.isDigit() }.take(3); if (f.isEmpty() || f.toInt() <= 100) shareInput = f }, label = { Text("기사 몫 (%)", color = muted) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(100, 90, 80, 70, 60).forEach { p -> OutlinedButton(onClick = { shareInput = p.toString() }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text("${p}%", fontSize = 11.sp) } } }
            } },
            confirmButton = { Button(onClick = { profitShare = shareInput.toIntOrNull() ?: 100; prefs.edit().putInt("profit_share", profitShare).apply(); saveSettingsToServer(); showShareDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black) } },
            dismissButton = { OutlinedButton(onClick = { showShareDialog = false }) { Text("취소") } }, containerColor = Color(0xFF111827))
    }

    if (showLogoutConfirm) {
        AlertDialog(onDismissRequest = { showLogoutConfirm = false },
            title = { Text("로그아웃", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("로그아웃하시겠습니까?", color = Color(0xFF9CA3AF)) },
            confirmButton = { Button(onClick = { showLogoutConfirm = false; onLogout() }, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("로그아웃", color = Color.White) } },
            dismissButton = { OutlinedButton(onClick = { showLogoutConfirm = false }) { Text("취소") } }, containerColor = Color(0xFF111827))
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 헤더
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⚙️", fontSize = 22.sp)
            Column {
                Text("자동화 & 정산", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("자동기록 권한과 기사 정산을 관리하세요", fontSize = 11.sp, color = muted)
            }
        }
        HorizontalDivider(color = Color(0xFF1F2937), modifier = Modifier.padding(bottom = 4.dp))

        Text("서비스 상태", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = muted, modifier = Modifier.padding(bottom = 4.dp))

        // 문의 및 소통
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("📞 문의 및 소통", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                HorizontalDivider(color = Color(0xFF374151), modifier = Modifier.padding(vertical = 8.dp))
                Text("앱 개선에 대한 아이디어나 문의사항이 있으신가요?\n아래 오픈채팅방 또는 이메일로 언제든 연락주세요.", fontSize = 12.sp, color = Color(0xFF9CA3AF), lineHeight = 18.sp)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://open.kakao.com/o/gsyuVMCi"))) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFEE500)), shape = RoundedCornerShape(10.dp)) { Text("카카오 오픈채팅방 바로가기", fontSize = 14.sp, color = Color.Black, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(6.dp))
                Text("✉️ sollbe82@gmail.com", fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
        }
        Spacer(Modifier.height(8.dp))
        // 앱 정보
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("ℹ️ 앱 정보", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                HorizontalDivider(color = Color(0xFF374151), modifier = Modifier.padding(vertical = 8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("버전", fontSize = 13.sp, color = Color(0xFF9CA3AF)); Text("1.0.0 β", fontSize = 13.sp, color = green) }
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("상태", fontSize = 13.sp, color = Color(0xFF9CA3AF)); Text("베타 테스트 중", fontSize = 13.sp, color = accent) }
                Spacer(Modifier.height(4.dp))
                Text("콜레이더 - 택시의신", fontSize = 11.sp, color = Color(0xFF6B7280))
            }
        }
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth().clickable { try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) { } }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("접근성 서비스", fontSize = 14.sp, color = Color.White); Text("자동 기록에 필요 (유료)", fontSize = 11.sp, color = muted) }
                Text(if (naviEnabled) "● 켜짐" else "● 꺼짐", fontSize = 12.sp, color = if (naviEnabled) green else red)
            }
        }

        Card(modifier = Modifier.fillMaxWidth().clickable { try { context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } catch (e: Exception) { } }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("알림 접근 허용", fontSize = 14.sp, color = Color.White); Text("택시투데이 요금 자동 매칭", fontSize = 11.sp, color = muted) }
                Text(if (notifEnabled) "● 켜짐" else "● 꺼짐", fontSize = 12.sp, color = if (notifEnabled) green else red)
            }
        }

        // 배터리 최적화 제외
        Card(modifier = Modifier.fillMaxWidth().clickable { try { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) { try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) } catch (e2: Exception) { } } }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("배터리 최적화 제외", fontSize = 14.sp, color = Color.White); Text("백그라운드 자동기록 유지", fontSize = 11.sp, color = muted) }
                Text(if (batteryOk) "● 켜짐" else "● 꺼짐", fontSize = 12.sp, color = if (batteryOk) green else red)
            }
        }

        // 위치 권한
        Card(modifier = Modifier.fillMaxWidth().clickable { try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) } catch (e: Exception) { } }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("위치 권한 (항상 허용)", fontSize = 14.sp, color = Color.White); Text("출발·도착지 자동 감지", fontSize = 11.sp, color = muted) }
                Text(if (locationOk) "● 켜짐" else "● 꺼짐", fontSize = 12.sp, color = if (locationOk) green else red)
            }
        }

        // 제한된 설정 허용 (업데이트마다 리셋 - 안내)
        Card(modifier = Modifier.fillMaxWidth().clickable { try { context.startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)) } catch (e: Exception) { try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) } catch (e2: Exception) { } } }, colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2416)), shape = RoundedCornerShape(10.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⚠️", fontSize = 14.sp)
                    Text("제한된 설정 허용", fontSize = 14.sp, color = accent, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text("앱 업데이트할 때마다 접근성이 꺼집니다.\n앱 목록에서 '콜레이더' → ⋮(우측상단) → '제한된 설정 허용'을 누른 뒤 접근성을 다시 켜세요.", fontSize = 11.sp, color = muted, lineHeight = 16.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("정산 설정", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = muted, modifier = Modifier.padding(bottom = 4.dp))

        // 기사유형 카드 (대표)
        Card(modifier = Modifier.fillMaxWidth().clickable { showTypeDialog = true }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("🚖 기사 유형", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold); Text("정산 방식의 기준이 됩니다", fontSize = 11.sp, color = muted) }
                Text("${if (driverType == "corporate") "법인" else "개인"} · ${when(affiliation){"kakao"->"카카오";"uber"->"우버";else->"비가맹"}}", fontSize = 13.sp, color = accent, fontWeight = FontWeight.Bold)
            }
        }

        // 법인 전용: 사납금 + 분배율
        if (driverType == "corporate") {
            Card(modifier = Modifier.fillMaxWidth().clickable { sanapInput = dailySanap.toString(); showSanapDialog = true }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("일 사납금", fontSize = 14.sp, color = Color.White); Text("만근 ${workDays}일 기준", fontSize = 11.sp, color = muted) }
                    Text(if (dailySanap > 0) "${String.format("%,d", dailySanap)}원" else "미설정", fontSize = 13.sp, color = if (dailySanap > 0) accent else muted)
                }
            }
            Card(modifier = Modifier.fillMaxWidth().clickable { showShareDialog = true }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("초과수익 분배율", fontSize = 14.sp, color = Color.White); Text("사납금 초과분 중 기사 몫", fontSize = 11.sp, color = muted) }
                    Text("${profitShare}%", fontSize = 13.sp, color = accent)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth().clickable { showFeeDialog = true }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("플랫폼별 수수료", fontSize = 14.sp, color = Color.White); Text("가맹 기사만 설정 (비가맹은 0%)", fontSize = 11.sp, color = muted) }
                Text("카${kakaoFee}% 우${uberFee}% 티${tmoneyFee}%", fontSize = 11.sp, color = accent)
            }
        }

        Card(modifier = Modifier.fillMaxWidth().clickable { showLpgDialog = true }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("LPG 정산", fontSize = 14.sp, color = Color.White); Text("단가·사용량·유가보조금", fontSize = 11.sp, color = muted) }
                Text(if (lpgPrice > 0) "${lpgPrice}원/L · ${lpgDaily}L" else "미설정", fontSize = 11.sp, color = accent)
            }
        }

        Card(modifier = Modifier.fillMaxWidth().clickable { showExpenseDialog = true }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("일 고정 지출", fontSize = 14.sp, color = Color.White); Text("식비·세차비 등", fontSize = 11.sp, color = muted) }
                Text(if (dailyExpense > 0) "${String.format("%,d", dailyExpense)}원" else "미설정", fontSize = 11.sp, color = accent)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("계정", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = muted, modifier = Modifier.padding(bottom = 4.dp))

        Card(modifier = Modifier.fillMaxWidth().clickable { showLogoutConfirm = true }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Text("로그아웃", fontSize = 14.sp, color = red)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("콜레이더 v1.0.0 (베타)", fontSize = 11.sp, color = muted, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text("© 2026 콜레이더 (CallRadar)", fontSize = 10.sp, color = muted, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun LinksView(context: Context, card: Color, accent: Color, muted: Color) {
    data class LinkItem(val emoji: String, val title: String, val desc: String, val url: String)
    data class LinkSection(val title: String, val color: Color, val links: List<LinkItem>)

    val sections = listOf(
        LinkSection("✈️ 공항 기사용", accent, listOf(
            LinkItem("🛫", "인천국제공항", "실시간 항공편·혼잡도 확인", "https://www.airport.kr"),
            LinkItem("🛬", "김포공항", "국내선 항공편 확인", "https://www.airport.co.kr/gimpo"),
            LinkItem("🚄", "공항철도 시간표", "AREX 운행 정보", "https://www.arex.or.kr"),
            LinkItem("🌍", "FlightRadar24", "실시간 항공기 추적", "https://www.flightradar24.com"),
            LinkItem("🛣️", "서울 도시고속도로", "공항로·올림픽대로 실시간", "https://www.ex.co.kr"),
            LinkItem("⛅", "항공기상청", "공항 기상 정보", "https://amo.kma.go.kr"),
            LinkItem("🚢", "인천항 크루즈 일정", "입항 크루즈 하선 일정", "https://www.icpa.or.kr")
        ))
    )

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        sections.forEach { section ->
            Text(section.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = section.color, modifier = Modifier.padding(bottom = 8.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(12.dp)) {
                Column {
                    section.links.forEachIndexed { index, link ->
                        Row(modifier = Modifier.fillMaxWidth().clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) }.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(link.emoji, fontSize = 24.sp)
                                Column { Text(link.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White); Text(link.desc, fontSize = 11.sp, color = muted) }
                            }
                            Text("→", fontSize = 16.sp, color = muted)
                        }
                        if (index < section.links.size - 1) HorizontalDivider(color = Color(0xFF1F2937), modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
