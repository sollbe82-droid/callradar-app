package com.callradar.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.callradar.app.ui.theme.CallRadarTheme
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * [명세서 스펙 v31+] 회사 프로필 관리 + 실시간 예상 기사몫.
 * 실제 이번 달 총매출(/api/profile)·근무일수(/api/stats/daily)·가스비(설정 일부담×근무일)로
 * 활성 프로필의 예상 기사몫을 즉시 계산해 보여준다(홈 계산은 무손상).
 */
class CompanyProfileActivity : ComponentActivity() {

    private val SERVER_URL = "https://callradar-server.onrender.com"

    // 서버에서 불러온 실측치
    private var monthFare by mutableStateOf(0)
    private var workedDays by mutableStateOf(0)
    private var loading by mutableStateOf(true)
    private var refreshTick by mutableStateOf(0)

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, CompanyProfileActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadActuals()
        setContent { CallRadarTheme { Screen() } }
    }

    private fun userId(): String? = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getString("user_id", null)

    private fun loadActuals() {
        loading = true
        val uid = userId()
        if (uid.isNullOrBlank()) { loading = false; return }
        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val dsh = prefs.getInt("day_start_hour", 0)
        Thread {
            var mFare = 0; var days = 0
            try {
                val prof = get("$SERVER_URL/api/profile/$uid")
                mFare = JSONObject(prof).optInt("monthFare", 0)
            } catch (e: Exception) { Log.e("CallRadar", "profile 로드 실패: ${e.message}") }
            try {
                val ym = SimpleDateFormat("yyyy-MM", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date())
                val daily = get("$SERVER_URL/api/stats/daily/$uid?month=$ym&dayStart=$dsh")
                val arr = JSONArray(daily)
                for (i in 0 until arr.length()) if (arr.getJSONObject(i).optInt("total_fare", 0) > 0) days++
            } catch (e: Exception) { Log.e("CallRadar", "daily 로드 실패: ${e.message}") }
            runOnUiThread { monthFare = mFare; workedDays = days; loading = false }
        }.start()
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection().apply {
            com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") }
        } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
        return conn.inputStream.bufferedReader().readText()
    }

    // ── theme ──
    private val bg = Color(0xFF0A0E1A); private val card = Color(0xFF111827)
    private val accent = Color(0xFFF59E0B); private val green = Color(0xFF10B981)
    private val red = Color(0xFFEF4444); private val muted = Color(0xFF6B7280)

    @Composable
    private fun Screen() {
        val ctx = this
        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val lpgDailyCost = prefs.getInt("lpg_daily_cost", 0)
        var profiles by remember(refreshTick) { mutableStateOf(CompanyProfile.all(prefs)) }
        var activeKey by remember(refreshTick) { mutableStateOf(CompanyProfile.active(prefs)?.key() ?: "") }
        var editing by remember { mutableStateOf<CompanyProfile?>(null) }
        var showEdit by remember { mutableStateOf(false) }
        var applyMsg by remember { mutableStateOf("") }

        Column(modifier = Modifier.fillMaxSize().background(bg).padding(20.dp).verticalScroll(rememberScrollState())) {
            Text("회사 프로필", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            Text("회사 × 근무형태별 급여식. 명세서가 없어도 운행기록으로 예상급여를 계산해요.", fontSize = 12.sp, color = muted, modifier = Modifier.padding(bottom = 14.dp))

            // 실시간 예상 기사몫 (활성 프로필)
            val active = profiles.firstOrNull { it.key() == activeKey } ?: profiles.firstOrNull()
            EstimateCard(active, monthFare, workedDays, lpgDailyCost)

            Spacer(Modifier.height(16.dp))
            Text("내 프로필", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))

            profiles.forEach { p ->
                val isActive = p.key() == activeKey
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .border(1.5.dp, if (isActive) accent else Color(0xFF2A3B56), RoundedCornerShape(14.dp))
                        .clickable { CompanyProfile.setActive(prefs, p.key()); activeKey = p.key() },
                    colors = CardDefaults.cardColors(containerColor = if (isActive) accent.copy(alpha = 0.12f) else card),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text((if (isActive) "✅ " else "") + p.label(), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("편집", fontSize = 12.sp, color = accent, modifier = Modifier.clickable { editing = p; showEdit = true })
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("사납 ${p.sanapDaily.won()}원/일 · 가스 ${p.gasBearer}부담 · 초과율 ${(p.overRate * 100).toInt()}%" + (if (p.baseSalary > 0) " · 기본급 ${p.baseSalary.won()}원" else ""),
                            fontSize = 12.sp, color = muted)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { editing = CompanyProfile("", "일차", 0, "기사", 1.0, 0); showEdit = true },
                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text("+ 회사 프로필 추가", fontWeight = FontWeight.Bold) }

            Spacer(Modifier.height(14.dp))
            if (active != null) {
                Button(onClick = {
                    CompanyProfile.applySanapToSettings(prefs, active)
                    applyMsg = "‘${active.label()}’ 사납금 ${active.sanapDaily.won()}원을 기사 설정에 적용했어요 → 홈 예상급여에 반영됩니다."
                }, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(12.dp)) {
                    Text("이 프로필을 기사 설정에 적용", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                if (applyMsg.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(applyMsg, fontSize = 12.sp, color = green) }
                Text("가스·초과율 등 세부 정산은 기존 ⚙️기사 설정에서 관리해요.", fontSize = 10.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(24.dp))
        }

        if (showEdit && editing != null) {
            EditDialog(editing!!, onDismiss = { showEdit = false }, onSave = { p ->
                CompanyProfile.upsert(prefs, p)
                CompanyProfile.setActive(prefs, p.key())
                BackupSync.pushProfiles(this)   // [v32] 서버 백업(기변 대비)
                showEdit = false; refreshTick++
            }, onDelete = { p ->
                CompanyProfile.remove(prefs, p.key()); BackupSync.pushProfiles(this); showEdit = false; refreshTick++
            })
        }
    }

    @Composable
    private fun EstimateCard(p: CompanyProfile?, fare: Int, days: Int, lpgDailyCost: Int) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("💰 이번 달 예상 기사몫" + (if (p != null) " · ${p.label()}" else ""), fontSize = 13.sp, color = muted)
                if (loading) {
                    Spacer(Modifier.height(10.dp)); CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
                } else if (p == null) {
                    Spacer(Modifier.height(8.dp)); Text("프로필을 추가하거나 선택하세요", fontSize = 14.sp, color = muted)
                } else {
                    val driverGas = if (p.gasBearer == "기사") lpgDailyCost * days else 0
                    val est = p.expectedShare(fare, days, driverGas)
                    Text("${est.won()}원", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = green)
                    Spacer(Modifier.height(10.dp)); HorizontalDivider(color = Color(0xFF1F2937)); Spacer(Modifier.height(10.dp))
                    Row2("총매출(이번 달 운행기록)", "${fare.won()}원", Color.White)
                    Row2("− 사납금 (${p.sanapDaily.won()} × ${days}일)", "${(p.sanapDaily * days).won()}원", red)
                    if (p.overRate != 1.0) Row2("× 초과율", "${(p.overRate * 100).toInt()}%", accent)
                    Row2(if (p.gasBearer == "기사") "− 가스비 (기사부담 · ${days}일)" else "가스비 (회사부담)", if (p.gasBearer == "기사") "${(lpgDailyCost * days).won()}원" else "0원", if (p.gasBearer == "기사") red else muted)
                    if (p.baseSalary > 0) Row2("+ 기본급/수당", "${p.baseSalary.won()}원", green)
                    Spacer(Modifier.height(6.dp))
                    Text("운행기록이 쌓일수록·명세서를 스캔할수록 정확해져요", fontSize = 10.sp, color = muted)
                    if (days == 0) Text("아직 이번 달 근무 기록이 없어 0으로 표시돼요", fontSize = 10.sp, color = accent, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }

    @Composable
    private fun Row2(label: String, value: String, color: Color) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp, color = muted)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }

    @Composable
    private fun EditDialog(p: CompanyProfile, onDismiss: () -> Unit, onSave: (CompanyProfile) -> Unit, onDelete: (CompanyProfile) -> Unit) {
        var company by remember { mutableStateOf(p.company) }
        var workType by remember { mutableStateOf(p.workType.ifBlank { "일차" }) }
        var sanap by remember { mutableStateOf(if (p.sanapDaily > 0) p.sanapDaily.toString() else "") }
        var gasBearer by remember { mutableStateOf(p.gasBearer) }
        var over by remember { mutableStateOf((p.overRate * 100).toInt().toString()) }
        var base by remember { mutableStateOf(if (p.baseSalary > 0) p.baseSalary.toString() else "") }
        val exists = CompanyProfile.all(getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)).any { it.key() == p.key() }

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = card,
            title = { Text(if (exists) "프로필 편집" else "회사 프로필 추가", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Field("회사명", company) { company = it }
                    Spacer(Modifier.height(8.dp))
                    Text("근무형태", fontSize = 12.sp, color = muted)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("일차", "주간", "야간", "격일").forEach { w ->
                            Chip(w, workType == w) { workType = w }
                        }
                    }
                    NumField("일 사납금(원)", sanap) { sanap = it }
                    Spacer(Modifier.height(8.dp))
                    Text("가스비 부담", fontSize = 12.sp, color = muted)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("기사", "회사").forEach { g -> Chip("${g}부담", gasBearer == g) { gasBearer = g } }
                    }
                    NumField("초과율(%) · 기본 100", over) { over = it }
                    Spacer(Modifier.height(8.dp))
                    NumField("기본급/수당(원) · 도급이면 0", base) { base = it }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSave(CompanyProfile(
                        company = company.trim(),
                        workType = workType,
                        sanapDaily = sanap.filter { it.isDigit() }.toIntOrNull() ?: 0,
                        gasBearer = gasBearer,
                        overRate = ((over.filter { it.isDigit() }.toIntOrNull() ?: 100).coerceIn(0, 200)) / 100.0,
                        baseSalary = base.filter { it.isDigit() }.toIntOrNull() ?: 0
                    ))
                }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("저장", color = Color.Black, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                Row {
                    if (exists) TextButton(onClick = { onDelete(p) }) { Text("삭제", color = red) }
                    TextButton(onClick = onDismiss) { Text("취소", color = muted) }
                }
            }
        )
    }

    @Composable
    private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
        Box(modifier = Modifier.background(if (selected) accent else Color(0xFF1F2937), RoundedCornerShape(8.dp))
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(text, fontSize = 12.sp, color = if (selected) Color.Black else muted, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun Field(label: String, value: String, onChange: (String) -> Unit) {
        OutlinedTextField(value = value, onValueChange = onChange, singleLine = true,
            label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedLabelColor = accent, unfocusedLabelColor = muted))
    }

    @Composable
    private fun NumField(label: String, value: String, onChange: (String) -> Unit) {
        OutlinedTextField(value = value, onValueChange = { onChange(it.filter { c -> c.isDigit() }) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedLabelColor = accent, unfocusedLabelColor = muted))
    }

    private fun Int.won(): String = String.format("%,d", this)
}
