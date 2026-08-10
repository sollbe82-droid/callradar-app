package com.callradar.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.callradar.app.ui.theme.CallRadarTheme
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

/**
 * [세무 스펙] 개인택시 연간 세무 리포트(추정). 서버 /api/tax/report 표시.
 *  종소세: 경비율(단순/기준) vs 장부(실제경비) 최소세액 추천 + 부가세(일반=1/11−매입).
 *  ※ 추정·준비자료. 실제 신고는 세무사·국세청 확인 필요(서버 disclaimer 그대로 노출).
 */
class TaxReportActivity : ComponentActivity() {

    private val SERVER_URL = "https://callradar-server.onrender.com"
    private var report by mutableStateOf<JSONObject?>(null)
    private var loading by mutableStateOf(true)
    private var errorMsg by mutableStateOf("")
    private var year by mutableStateOf(Calendar.getInstance().get(Calendar.YEAR))
    private var isSimpleVat by mutableStateOf(false)   // 부가세 간이과세 여부

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, TaxReportActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        load()
        setContent { CallRadarTheme { Screen() } }
    }

    private fun load() {
        loading = true; errorMsg = ""
        val uid = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getString("user_id", null)
        if (uid.isNullOrBlank()) { loading = false; errorMsg = "로그인이 필요합니다"; return }
        Thread {
            try {
                val url = "$SERVER_URL/api/tax/report?user_id=$uid&year=$year&is_simple=$isSimpleVat"
                val conn = (URL(url).openConnection().apply {
                    com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") }
                } as HttpURLConnection).apply { connectTimeout = 12000; readTimeout = 12000 }
                val txt = conn.inputStream.bufferedReader().readText()
                val j = JSONObject(txt)
                runOnUiThread { report = j; loading = false }
            } catch (e: Exception) {
                Log.e("CallRadar", "세무 리포트 실패: ${e.message}")
                runOnUiThread { errorMsg = "불러오기 실패 — 서버가 깨어나는 중일 수 있어요. 다시 시도하세요."; loading = false }
            }
        }.start()
    }

    private val bg = Color(0xFF0A0E1A); private val card = Color(0xFF111827)
    private val accent = Color(0xFFF59E0B); private val green = Color(0xFF10B981)
    private val red = Color(0xFFEF4444); private val blue = Color(0xFF3B82F6); private val muted = Color(0xFF6B7280)

    @Composable
    private fun Screen() {
        Column(Modifier.fillMaxSize().background(bg).padding(20.dp).verticalScroll(rememberScrollState())) {
            Text("세무 리포트", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            Text("개인택시 연간 추정. 종소세(경비율 vs 장부) + 부가세.", fontSize = 12.sp, color = muted, modifier = Modifier.padding(bottom = 12.dp))

            // 연도 · 과세유형 선택
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val thisYear = Calendar.getInstance().get(Calendar.YEAR)
                Chip("${thisYear}년", year == thisYear) { if (year != thisYear) { year = thisYear; load() } }
                Chip("${thisYear - 1}년", year == thisYear - 1) { if (year != thisYear - 1) { year = thisYear - 1; load() } }
                Spacer(Modifier.weight(1f))
                Chip("일반과세", !isSimpleVat) { if (isSimpleVat) { isSimpleVat = false; load() } }
                Chip("간이과세", isSimpleVat) { if (!isSimpleVat) { isSimpleVat = true; load() } }
            }

            if (loading) { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) } }
            else if (errorMsg.isNotEmpty()) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(errorMsg, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                        TextButton(onClick = { load() }) { Text("재시도", color = accent) }
                    }
                }
            } else report?.let { r -> ReportBody(r) }
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun ReportBody(r: JSONObject) {
        val income = r.optInt("income", 0)
        val cardIncome = r.optInt("cardIncome", 0)
        val totalExpense = r.optInt("totalExpense", 0)
        val it = r.optJSONObject("incomeTax") ?: JSONObject()
        val vat = r.optJSONObject("vat") ?: JSONObject()

        // 수입·경비 요약
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("📊 ${r.optInt("year", year)}년 집계", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(10.dp))
                KV("총수입(운행)", "${income.won()}원", Color.White)
                KV("└ 카드·자동결제 (과세매출 근사)", "${cardIncome.won()}원", muted)
                KV("총경비(지출)", "${totalExpense.won()}원", red)
                val cats = r.optJSONObject("expenseByCategory")
                if (cats != null && cats.length() > 0) {
                    Spacer(Modifier.height(6.dp)); HorizontalDivider(color = Color(0xFF1F2937)); Spacer(Modifier.height(6.dp))
                    val keys = cats.keys()
                    while (keys.hasNext()) { val k = keys.next(); KV("  · $k", "${cats.optInt(k, 0).won()}원", muted) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // 종합소득세
        val byRate = it.optJSONObject("byRate") ?: JSONObject()
        val byBook = it.optJSONObject("byBook") ?: JSONObject()
        val recommended = it.optString("recommended", "")
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("🧾 종합소득세 (추정)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("적용 경비율: ${it.optString("method", "-")}", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))

                MethodBlock("경비율 방식", byRate, recommended == "rate")
                Spacer(Modifier.height(8.dp))
                MethodBlock("장부 방식(실제경비)", byBook, recommended == "book")

                Spacer(Modifier.height(12.dp)); HorizontalDivider(color = Color(0xFF1F2937)); Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("추천 · 예상세액", fontSize = 13.sp, color = muted)
                    Text("${it.optInt("estimatedTax", 0).won()}원", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = green)
                }
                val saving = it.optInt("saving", 0)
                if (saving > 0) Text("${if (recommended == "book") "장부" else "경비율"} 선택 시 최대 ${saving.won()}원 절세", fontSize = 12.sp, color = accent, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(12.dp))

        // 부가가치세
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("💳 부가가치세 (추정)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(vat.optString("type", "-"), fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
                if (vat.isNull("estimated")) {
                    Text(vat.optString("note", "간이 부가율은 업종·연도별 상이 — 국세청 확인"), fontSize = 12.sp, color = accent)
                } else {
                    KV("매출세액 (공급대가 1/11)", "${vat.optInt("salesVat", 0).won()}원", Color.White)
                    KV("매입세액", "${vat.optInt("purchaseVat", 0).won()}원", muted)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("예상 납부세액", fontSize = 13.sp, color = muted)
                        Text("${vat.optInt("estimated", 0).won()}원", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = blue)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("⚠ ${r.optString("disclaimer", "예상 추정치입니다. 실제 신고는 국세청·세무사 확인이 필요합니다.")}  (기준 ${r.optString("configVersion", "")})",
            fontSize = 11.sp, color = muted)
    }

    @Composable
    private fun MethodBlock(title: String, o: JSONObject, isRec: Boolean) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (isRec) accent.copy(alpha = 0.12f) else Color(0xFF0F1524)), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text((if (isRec) "✅ " else "") + title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (isRec) accent else Color.White)
                Spacer(Modifier.height(6.dp))
                KV("인정 경비", "${o.optInt("expense", 0).won()}원", muted)
                KV("소득금액", "${o.optInt("incomeAmount", 0).won()}원", muted)
                KV("과세표준", "${o.optInt("taxBase", 0).won()}원", muted)
                KV("산출세액", "${o.optInt("tax", 0).won()}원", if (isRec) green else Color.White)
            }
        }
    }

    @Composable
    private fun KV(k: String, v: String, color: Color) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(k, fontSize = 13.sp, color = muted, modifier = Modifier.weight(1f))
            Text(v, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }

    @Composable
    private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
        Box(Modifier.background(if (selected) accent else Color(0xFF1F2937), RoundedCornerShape(8.dp)).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(text, fontSize = 12.sp, color = if (selected) Color.Black else muted, fontWeight = FontWeight.Bold)
        }
    }

    private fun Int.won(): String = String.format("%,d", this)
}
