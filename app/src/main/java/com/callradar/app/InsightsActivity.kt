package com.callradar.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.callradar.app.screen.AppTheme
import com.callradar.app.ui.theme.CallRadarTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * [인사이트] 운행 데이터가 말해주는 것 — 내 데이터 / 전체 기사
 *
 *  콜레이더는 콜 수락 전에 목적지를 모른다(운행이 시작돼야 감지). 그래서 "이 콜 받아라"는 못 한다.
 *  기사가 실제로 고를 수 있는 건 ①대기 위치 ②시간대 ③운행 스타일뿐이고, 이 화면은 거기에만 답한다.
 *
 *  핵심 지표는 매출이 아니라 **순수입 분당** — 요금에서 연료비(공차 포함)를 빼고,
 *  운행시간 + 다음 콜까지 대기시간으로 나눈 값. 한 콜에 묶이는 시간 전체가 분모다.
 */
class InsightsActivity : ComponentActivity() {
    companion object {
        private const val SERVER_URL = "https://callradar-server.onrender.com"
        fun start(context: Context) {
            context.startActivity(Intent(context, InsightsActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CallRadarTheme { InsightsScreen { finish() } } }
    }
}

private val accent = Color(0xFFF59E0B)
private val green = Color(0xFF10B981)
private val red = Color(0xFFEF4444)
private val muted = Color(0xFF9CA3AF)

@Composable
private fun InsightsScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    val userId = prefs.getString("user_id", "") ?: ""
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }   // 0=내 데이터 1=전체
    // [데이터 품질] 내 기록 중 이상해 보이는 것 — 자동으로 지우지 않고 기사에게 확인받는다.
    //  (심야할증 단거리처럼 진짜 그 요금인 경우가 있어서, 판단은 기사 몫으로 남긴다)
    var badRecs by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun loadBad() {
        try {
            val t = withContext(Dispatchers.IO) {
                (URL("https://callradar-server.onrender.com/api/quality/my/$userId").openConnection().apply {
                    com.callradar.app.Auth.tok?.let { tk -> if (tk.isNotBlank()) setRequestProperty("Authorization", "Bearer $tk") }
                } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 20000 }.inputStream.bufferedReader().readText()
            }
            badRecs = JSONObject(t)
        } catch (e: Exception) {}
    }
    LaunchedEffect(userId) { if (userId.isNotEmpty()) loadBad() }

    LaunchedEffect(userId) {
        loading = true; err = ""
        try {
            val txt = withContext(Dispatchers.IO) {
                (URL("https://callradar-server.onrender.com/api/insights/$userId").openConnection().apply {
                    com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") }
                } as HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 40000 }
                    .inputStream.bufferedReader().readText()
            }
            data = JSONObject(txt)
        } catch (e: Exception) { err = "불러오지 못했어요. 잠시 후 다시 시도해 주세요." }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(AppTheme.bg)) {
        Row(Modifier.fillMaxWidth().background(AppTheme.card).padding(top = 34.dp, start = 10.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("‹", fontSize = 24.sp, color = accent, fontWeight = FontWeight.Bold); Spacer(Modifier.width(4.dp)); Text("홈", fontSize = 14.sp, color = accent)
            }
            Spacer(Modifier.width(4.dp))
            Text("📊 운행 인사이트", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("👤 내 데이터", "🚕 전체 기사").forEachIndexed { i, t ->
                FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(t, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
            }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = accent)
                    Spacer(Modifier.height(12.dp))
                    Text("운행 기록을 분석하는 중…", fontSize = 12.sp, color = muted)
                }
            }
            err.isNotEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(err, fontSize = 13.sp, color = muted) }
            data != null -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
                // [데이터 품질] 확인이 필요한 기록 — 통계에서 빠져 있으니 고치면 내 분석이 정확해진다
                if (tab == 0) {
                    val cnt = badRecs?.optInt("count", 0) ?: 0
                    if (cnt > 0) item {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("⚠️ 확인이 필요한 기록 ${cnt}건", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = red)
                                Text("금액이나 거리가 이상해 보여 통계에서 빼두었어요. 맞는 기록이면 '맞아요'를 눌러주세요.",
                                    fontSize = 11.sp, color = muted, lineHeight = 16.sp)
                                val items = badRecs?.optJSONArray("items")
                                if (items != null) for (i in 0 until minOf(items.length(), 6)) {
                                    val it0 = items.getJSONObject(i)
                                    Column(Modifier.fillMaxWidth()) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text("${it0.optString("time")} · ${it0.optString("origin")} → ${it0.optString("destination")}",
                                                    fontSize = 12.sp, color = AppTheme.text)
                                                Text("${nf(it0.optInt("fare"))}원 · ${it0.optDouble("km", 0.0)}km", fontSize = 11.sp, color = muted)
                                                Text(it0.optString("message"), fontSize = 10.sp, color = red)
                                            }
                                            TextButton(onClick = {
                                                val id = it0.optInt("id")
                                                scope.launch {
                                                    try {
                                                        withContext(Dispatchers.IO) {
                                                            val c2 = (URL("https://callradar-server.onrender.com/api/quality/confirm/$id").openConnection().apply {
                                                                com.callradar.app.Auth.tok?.let { tk -> if (tk.isNotBlank()) setRequestProperty("Authorization", "Bearer $tk") }
                                                            } as HttpURLConnection).apply {
                                                                requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true
                                                                connectTimeout = 8000; readTimeout = 15000
                                                            }
                                                            c2.outputStream.use { os -> os.write(JSONObject().put("user_id", userId).toString().toByteArray()) }
                                                            c2.responseCode
                                                        }
                                                        loadBad()
                                                    } catch (e: Exception) {}
                                                }
                                            }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("맞아요", fontSize = 12.sp, color = green) }
                                        }
                                        HorizontalDivider(color = AppTheme.surface2, modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
                                Text("기록·정산 화면에서 금액을 고치면 바로 반영됩니다.", fontSize = 10.sp, color = muted)
                            }
                        }
                    }
                }
                if (tab == 0) meSection(data!!) else fleetSection(data!!)
                item {
                    val n = data!!.optInt("sampleTotal", 0)
                    Text("전체 표본 ${String.format("%,d", n)}건 · 30분마다 갱신 · 연료 ${data!!.optInt("fuelPrice", 1106)}원/L 기준",
                        fontSize = 10.sp, color = muted, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
                }
            }
        }
    }
}

@Composable
private fun Card2(title: String, sub: String = "", content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
            if (sub.isNotBlank()) Text(sub, fontSize = 11.sp, color = muted)
            content()
        }
    }
}

@Composable
private fun Row2(l: String, r: String, bold: Boolean = false, color: Color = AppTheme.text) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(l, fontSize = 13.sp, color = if (bold) AppTheme.text else muted, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text(r, fontSize = 13.sp, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

private fun nf(v: Int) = String.format("%,d", v)

// ───────────────────────── 내 데이터 ─────────────────────────
private fun androidx.compose.foundation.lazy.LazyListScope.meSection(d: JSONObject) {
    val me = d.optJSONObject("me")
    if (me == null || me.optBoolean("locked")) {
        item {
            Card2("👤 내 데이터 분석", "기록이 쌓이면 자동으로 열려요") {
                val have = me?.optInt("have", 0) ?: 0
                val need = me?.optInt("need", 20) ?: 20
                Text(me?.optString("msg") ?: "운행 기록이 더 필요해요", fontSize = 14.sp, color = AppTheme.text, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { (have.toFloat() / need).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp), color = accent, trackColor = AppTheme.surface2)
                Text("$have / $need 건", fontSize = 11.sp, color = muted)
                Spacer(Modifier.height(6.dp))
                Text("운행을 기록하면 ① 내 성향 진단 ② 잘 버는 시간대 ③ 내 자리 성격 ④ 전체 대비 내 위치를 볼 수 있어요.",
                    fontSize = 12.sp, color = muted, lineHeight = 18.sp)
                Text("자동기록을 켜두면 따로 입력하지 않아도 쌓입니다.", fontSize = 11.sp, color = accent)
            }
        }
        item { Text("아래는 전체 기사 데이터로 본 인사이트예요 — '전체 기사' 탭에서 확인하세요.", fontSize = 12.sp, color = muted, modifier = Modifier.padding(4.dp)) }
        return
    }

    item {
        val vs = me.optInt("vsAll", 0)
        Card2("👤 내 운행 성향", "최근 90일 ${me.optInt("trips")}건 · ${me.optInt("days")}일") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(me.optString("style"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent)
                Column(horizontalAlignment = Alignment.End) {
                    Text("내 순수입 분당", fontSize = 10.sp, color = muted)
                    Text("${nf(me.optInt("perMin"))}원", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = if (vs >= 0) green else red)
                }
            }
            Text("장거리(20km+) ${me.optInt("longPct")}% · 초단거리(5km↓) ${me.optInt("shortPct")}%", fontSize = 12.sp, color = muted)
            Spacer(Modifier.height(2.dp))
            Text(if (vs >= 0) "전체 평균(${nf(me.optInt("allPerMin"))}원)보다 ${vs}% 높아요" else "전체 평균(${nf(me.optInt("allPerMin"))}원)보다 ${-vs}% 낮아요",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (vs >= 0) green else red)
        }
    }

    item {
        Card2("📐 내 숫자 vs 전체 평균", "연료비를 뺀 순수입 기준") {
            Row2("콜당 요금", "${nf(me.optInt("avgFare"))}원   (전체 ${nf(me.optInt("allFare"))}원)")
            Row2("평균 거리", "${me.optDouble("avgKm", 0.0)}km")
            Row2("평균 운행", "${me.optDouble("avgRide", 0.0)}분")
            Row2("평균 대기", "${me.optDouble("avgWait", 0.0)}분   (전체 ${me.optDouble("allWait", 0.0)}분)",
                color = if (me.optDouble("avgWait", 0.0) > me.optDouble("allWait", 0.0) + 4) red else AppTheme.text)
            Row2("콜당 순수입", "${nf(me.optInt("avgNet"))}원", bold = true, color = accent)
        }
    }

    item {
        val best = me.optJSONArray("bestHours"); val worst = me.optJSONArray("worstHours")
        Card2("⏰ 내가 잘 버는 시간 / 아쉬운 시간", "내 기록 기준 (3건 이상인 시간만)") {
            if (best != null) for (i in 0 until best.length()) {
                val h = best.getJSONObject(i)
                Row2("🥇 ${h.optInt("hour")}시", "분당 ${nf(h.optInt("perMin"))}원 · ${h.optInt("n")}건", color = green)
            }
            Spacer(Modifier.height(4.dp))
            if (worst != null) for (i in 0 until worst.length()) {
                val h = worst.getJSONObject(i)
                Row2("🔻 ${h.optInt("hour")}시", "분당 ${nf(h.optInt("perMin"))}원 · ${h.optInt("n")}건", color = red)
            }
        }
    }

    item {
        val sp = me.optJSONArray("spots")
        if (sp != null && sp.length() > 0) Card2("📍 내가 자주 출발하는 자리", "그 자리에서 어떤 콜이 나왔나") {
            for (i in 0 until sp.length()) {
                val s = sp.getJSONObject(i)
                Row2("${s.optString("name")} · ${s.optInt("n")}건", "분당 ${nf(s.optInt("perMin"))}원 · 장거리 ${s.optInt("longPct")}%")
            }
        }
    }

    item {
        val tips = me.optJSONArray("tips")
        if (tips != null && tips.length() > 0) Card2("💡 내 기록이 말하는 것") {
            for (i in 0 until tips.length()) {
                Text("· ${tips.getString(i)}", fontSize = 13.sp, color = AppTheme.text, lineHeight = 20.sp)
            }
        }
    }
}

// ───────────────────────── 전체 기사 ─────────────────────────
private fun androidx.compose.foundation.lazy.LazyListScope.fleetSection(d: JSONObject) {
    item {
        Card2("🚕 거리별 실제 수익", "요금에서 연료비(공차 포함)를 빼고, 운행+대기 시간으로 나눈 값") {
            val bands = d.optJSONArray("bands")
            if (bands != null) for (i in 0 until bands.length()) {
                val b = bands.getJSONObject(i)
                val pm = b.optInt("perMin")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(b.optString("band"), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                        Text("요금 ${nf(b.optInt("fare"))} · 연료 -${nf(b.optInt("fuel"))} · 공차 ${b.optInt("deadPct")}%", fontSize = 10.sp, color = muted)
                    }
                    Text("${nf(pm)}원", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (pm >= 500) green else if (pm < 400) red else accent)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("짧은 콜일수록 손님 태우러 가는 거리(공차) 비중이 커집니다. 2km 콜은 58%가 헛거리예요.",
                fontSize = 11.sp, color = muted, lineHeight = 17.sp)
        }
    }

    item {
        val be = d.optJSONObject("breakEven")
        if (be != null) Card2("⏳ 장거리, 얼마나 기다려도 될까", "단거리 회전과 같아지는 지점") {
            Row2("단거리 실효 수익", "분당 ${nf(be.optInt("shortPerMin"))}원")
            Row2("30km+ 순수입", "${nf(be.optInt("longNet"))}원 · 운행 ${be.optDouble("longRide", 0.0)}분")
            Spacer(Modifier.height(4.dp))
            Text("장거리 1건을 위해 최대 ${be.optInt("maxWait")}분까지 기다려도 단거리 회전과 같아요",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent, lineHeight = 20.sp)
            Text("실제 30km+ 평균 대기는 ${be.optDouble("longWait", 0.0)}분 → 아직 ${be.optInt("margin")}분 여유",
                fontSize = 12.sp, color = green)
        }
    }

    item {
        Card2("🕐 시간대마다 유리한 콜이 다릅니다", "각 시간대 순수입 분당 1·2위") {
            val slots = d.optJSONArray("slots")
            if (slots != null) for (i in 0 until slots.length()) {
                val s = slots.getJSONObject(i)
                val r = s.optJSONArray("ranks") ?: continue
                val a = if (r.length() > 0) r.getJSONObject(0) else null
                val b = if (r.length() > 1) r.getJSONObject(1) else null
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(s.optString("slot"), fontSize = 12.sp, color = muted)
                    Text(buildString {
                        if (a != null) append("${a.optString("band")} ${nf(a.optInt("perMin"))}원")
                        if (b != null) append("  ·  ${b.optString("band")} ${nf(b.optInt("perMin"))}원")
                    }, fontSize = 12.sp, color = AppTheme.text)
                }
            }
        }
    }

    item {
        Card2("📈 24시간 순수입 분당", "전체 기사 90일 평균") {
            val hs = d.optJSONArray("hours")
            if (hs != null) {
                val vals = (0 until hs.length()).map { hs.getJSONObject(it) }
                val mx = vals.maxOfOrNull { it.optInt("perMin") } ?: 1
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    vals.forEach { h ->
                        val v = h.optInt("perMin")
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$v", fontSize = 9.sp, color = muted)
                            Box(Modifier.width(14.dp).height((6 + (v.toFloat() / mx * 54)).dp)
                                .background(if (v >= mx * 0.85) green else if (v <= mx * 0.6) red else accent, RoundedCornerShape(3.dp)))
                            Text("${h.optInt("hour")}", fontSize = 9.sp, color = muted)
                        }
                    }
                }
            }
        }
    }

    item {
        Card2("📍 장거리가 자주 나오는 자리", "여기서 기다리면 장거리 확률이 높아요") {
            val sp = d.optJSONArray("longSpots")
            if (sp != null) for (i in 0 until sp.length()) {
                val s = sp.getJSONObject(i)
                Row2("${s.optString("name")} · ${s.optInt("n")}건", "장거리 ${s.optInt("longPct")}% · 평균 ${nf(s.optInt("fare"))}원",
                    color = if (s.optInt("longPct") >= 50) green else AppTheme.text)
            }
        }
    }

    item {
        Card2("🔄 회전이 좋은 자리", "짧게 여러 번 도는 게 유리한 곳") {
            val sp = d.optJSONArray("rotateSpots")
            if (sp != null) for (i in 0 until sp.length()) {
                val s = sp.getJSONObject(i)
                Row2("${s.optString("name")} · ${s.optInt("n")}건", "분당 ${nf(s.optInt("perMin"))}원 · 대기 ${s.optDouble("wait", 0.0).toInt()}분",
                    color = if (i == 0) green else AppTheme.text)
            }
            Spacer(Modifier.height(2.dp))
            Text("장거리가 거의 없는 동네인데도 분당 수익은 공항권보다 높은 곳이 있어요.", fontSize = 11.sp, color = muted)
        }
    }
}
