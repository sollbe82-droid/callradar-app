package com.callradar.app.screen

// ═══════ [분석 2.0] 승인 스펙 구현 ═══════
// 원칙: "껍데기는 우버처럼, 속은 통계청처럼"
//  ① AI 주간 브리핑이 결론을 먼저 말함  ② KPI 4개만(전주 대비·활동시간당·중앙값·상위%)
//  ③ 히트맵은 시간당 정규화 + 표본부족 칸 정직하게 억제(?) + 내/전체 토글(전체=집단 데이터 보강)
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private data class HeatCell(val level: Int, val rate: Int, val hrs: Int)

@Composable
fun Stats2Screen(userId: String) {
    val SERVER_URL = "https://callradar-server.onrender.com"
    val accent = Color(0xFFF59E0B); val green = Color(0xFF10B981); val muted = AppTheme.muted
    val scope = rememberCoroutineScope()

    var brief by remember { mutableStateOf("이번 주 데이터를 읽는 중...") }
    var kpi by remember { mutableStateOf<JSONObject?>(null) }
    var heatMine by remember { mutableStateOf<List<List<HeatCell>>>(emptyList()) }
    var heatAll by remember { mutableStateOf<List<List<HeatCell>>>(emptyList()) }
    var weekly by remember { mutableStateOf(listOf(0L, 0L, 0L, 0L)) }
    var comp by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var showAllHeat by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    fun getJson(path: String): JSONObject? = try {
        val conn = (URL("$SERVER_URL$path").openConnection().apply { com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") } } as HttpURLConnection)
            .apply { connectTimeout = 15000; readTimeout = 30000 }
        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText(); conn.disconnect()
        JSONObject(body)
    } catch (e: Exception) { null }

    fun parseHeat(o: JSONObject, key: String): List<List<HeatCell>> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { b ->
            val row = arr.getJSONArray(b)
            (0 until row.length()).map { d ->
                val c = row.getJSONObject(d)
                HeatCell(c.optInt("level", -1), c.optInt("rate", 0), c.optInt("hrs", 0))
            }
        }
    }

    var waking by remember { mutableStateOf(false) }   // [콜드스타트] 4초 넘게 걸리면 서버 웨이크업 안내

    LaunchedEffect(userId) {
        if (userId.isEmpty()) return@LaunchedEffect
        // [콜드스타트] 4초 후에도 로딩 중이면 안내 문구 표시 (Render 무료 서버 슬립 → 첫 응답 30~60초)
        scope.launch { kotlinx.coroutines.delay(4000); if (loading) waking = true }
        // [콜드스타트] 브리핑(AI·느림)과 통계를 병렬로 — 브리핑이 통계 표시를 막지 않게
        scope.launch {
            withContext(Dispatchers.IO) {
                getJson("/api/briefing2/$userId")?.let { b -> brief = b.optString("text", brief) }
            }
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                // [콜드스타트] 통계는 최대 3회 재시도(5초 간격) — 슬립 깨어나는 동안의 타임아웃 흡수
                var s: JSONObject? = null
                for (attempt in 1..3) {
                    s = getJson("/api/stats2/$userId")
                    if (s != null) break
                    kotlinx.coroutines.delay(5000)
                }
                s?.let { st ->
                    kpi = st.optJSONObject("kpi")
                    heatMine = parseHeat(st, "heatMine"); heatAll = parseHeat(st, "heatAll")
                    st.optJSONArray("weekly")?.let { w -> weekly = (0 until w.length()).map { w.optLong(it) } }
                    st.optJSONArray("comp")?.let { c -> comp = (0 until c.length()).map { i -> val o = c.getJSONObject(i); o.optString("platform") to o.optInt("c") } }
                }
            }
            loading = false; waking = false
        }
    }

    Column(Modifier.fillMaxSize().background(AppTheme.bg).verticalScroll(rememberScrollState()).padding(14.dp)) {

        // ① AI 주간 브리핑 — 결론 먼저
        Card(colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("💡 주간 브리핑", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent)
                Spacer(Modifier.height(6.dp))
                Text(brief, fontSize = 14.sp, color = AppTheme.text, lineHeight = 22.sp)
            }
        }
        Spacer(Modifier.height(10.dp))

        // ② KPI 4
        kpi?.let { k ->
            val fare7 = k.optLong("fare7"); val fare14 = k.optLong("fare14")
            val delta = if (fare14 > 0) ((fare7 - fare14) * 100 / fare14).toInt() else 0
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KpiBox(Modifier.weight(1f), "주간 매출", "${String.format("%,d", fare7)}원",
                    if (fare14 > 0) (if (delta >= 0) "▲ 전주 대비 +$delta%" else "▼ 전주 대비 $delta%") else "첫 주", if (delta >= 0) green else Color(0xFFF87171))
                KpiBox(Modifier.weight(1f), "활동시간당", "${String.format("%,d", k.optInt("perHour"))}원", "활동 ${k.optInt("hours7")}시간", muted)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KpiBox(Modifier.weight(1f), "건수 · 건당(중앙값)", "${k.optInt("trips7")}건 · ${String.format("%,d", k.optInt("median7"))}원", "중앙값=보통 받는 금액", muted)
                val pct = if (k.isNull("percentile")) null else k.optInt("percentile")
                KpiBox(Modifier.weight(1f), "전체 기사 중", if (pct != null) "상위 $pct%" else "집계 중",
                    "활동시간당 기준 · ${k.optInt("peers")}명 비교", if (pct != null && pct <= 30) green else muted)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ③ 히트맵 — 시간당 정규화, 표본부족 억제, 내/전체 토글
        Card(colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔥 언제 벌리나", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                    Text("  시간당 매출 기준·3주", fontSize = 10.sp, color = muted)
                    Spacer(Modifier.weight(1f))
                    FilterChip(selected = !showAllHeat, onClick = { showAllHeat = false }, label = { Text("내 데이터", fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, labelColor = muted))
                    Spacer(Modifier.width(4.dp))
                    FilterChip(selected = showAllHeat, onClick = { showAllHeat = true }, label = { Text("전체 평균", fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF3B82F6), selectedLabelColor = Color.White, labelColor = muted))
                }
                Spacer(Modifier.height(10.dp))
                val heat = if (showAllHeat) heatAll else heatMine
                val bands = listOf("06-10", "10-14", "14-18", "18-22", "22-06")
                val days = listOf("월", "화", "수", "목", "금", "토", "일")
                val palette = if (showAllHeat) listOf(Color(0xFF1E293B), Color(0xFF1E3A8A), Color(0xFF2563EB), Color(0xFF60A5FA))
                               else listOf(Color(0xFF1F2937), Color(0xFF78350F), Color(0xFFB45309), Color(0xFFF59E0B))
                Row { Spacer(Modifier.width(42.dp)); days.forEach { d -> Text(d, fontSize = 9.sp, color = muted, modifier = Modifier.weight(1f), maxLines = 1) } }
                if (heat.isEmpty() && !loading) Text("데이터가 아직 없어요", fontSize = 12.sp, color = muted, modifier = Modifier.padding(vertical = 20.dp))
                heat.forEachIndexed { b, row ->
                    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(bands[b], fontSize = 8.5.sp, color = muted, modifier = Modifier.width(42.dp))
                        row.forEach { c ->
                            Box(Modifier.weight(1f).height(26.dp).padding(horizontal = 1.5.dp)
                                .background(if (c.level < 0) Color(0xFF111827) else palette[c.level], RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center) {
                                if (c.level < 0) Text("·", fontSize = 10.sp, color = Color(0xFF374151))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("· = 데이터 부족(3시간 미만) — 억지로 색칠하지 않아요", fontSize = 9.5.sp, color = muted, modifier = Modifier.weight(1f))
                    Text("적음", fontSize = 9.sp, color = muted); Spacer(Modifier.width(3.dp))
                    palette.forEach { p -> Box(Modifier.size(9.dp).background(p, RoundedCornerShape(2.dp))); Spacer(Modifier.width(2.dp)) }
                    Text(" 많음", fontSize = 9.sp, color = muted)
                }
                if (showAllHeat) Text("파란 칸 = 전체 기사 익명 집계 — 내가 안 뛰어본 시간대의 수요 참고용", fontSize = 9.5.sp, color = Color(0xFF60A5FA), modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(10.dp))

        // ④ 4주 추이 (막대)
        Card(colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("📈 최근 4주 매출", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                Spacer(Modifier.height(10.dp))
                val maxW = (weekly.maxOrNull() ?: 1L).coerceAtLeast(1L)
                val labels = listOf("3주 전", "2주 전", "지난주", "이번 주")
                weekly.forEachIndexed { i, v ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(labels[i], fontSize = 10.sp, color = muted, modifier = Modifier.width(46.dp))
                        Box(Modifier.weight(1f).height(16.dp)) {
                            Box(Modifier.fillMaxWidth((v.toFloat() / maxW).coerceIn(0.02f, 1f)).fillMaxHeight()
                                .background(if (i == 3) green else AppTheme.surface2, RoundedCornerShape(4.dp)))
                        }
                        Text(String.format("%,d", v), fontSize = 10.sp, color = if (i == 3) green else muted, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ⑤ 구성 (플랫폼)
        if (comp.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("💳 30일 콜 구성", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                    Spacer(Modifier.height(8.dp))
                    val total = comp.sumOf { it.second }.coerceAtLeast(1)
                    val colors = listOf(Color(0xFF3B82F6), accent, green, Color(0xFFA78BFA), Color(0xFF94A3B8))
                    Row(Modifier.fillMaxWidth().height(14.dp)) {
                        comp.forEachIndexed { i, (_, c) ->
                            Box(Modifier.weight(c.toFloat()).fillMaxHeight().padding(horizontal = 0.5.dp).background(colors[i % colors.size], RoundedCornerShape(3.dp)))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    comp.forEachIndexed { i, (name, c) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                            Box(Modifier.size(8.dp).background(colors[i % colors.size], RoundedCornerShape(2.dp)))
                            Text("  $name  ${c * 100 / total}% (${c}건)", fontSize = 11.sp, color = muted)
                        }
                    }
                }
            }
        }
        if (loading) {
            Spacer(Modifier.height(20.dp)); CircularProgressIndicator(color = accent, modifier = Modifier.align(Alignment.CenterHorizontally))
            if (waking) {
                Spacer(Modifier.height(10.dp))
                Text("서버를 깨우는 중이에요… 첫 로딩만 최대 1분 걸릴 수 있어요", fontSize = 12.sp, color = muted, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun KpiBox(modifier: Modifier, label: String, value: String, sub: String, subColor: Color) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, fontSize = 10.5.sp, color = AppTheme.muted)
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppTheme.text, maxLines = 1)
            Text(sub, fontSize = 9.5.sp, color = subColor, maxLines = 1)
        }
    }
}
