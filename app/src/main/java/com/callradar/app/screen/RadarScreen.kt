// ===== RadarScreen v2 (2026-07) — AI + 지도 통합 '레이더' 탭 =====
// 근무세션(work_start)이 지도의 '모드'를 조종한다:
//  · 근무중(work_start>0) → 라이브 코파일럿
//  · 대기/퇴근후(work_start==0) → 작전/회고 지도
// v2: 맛집(유저 좋은자리) 원터치 등록 + 목록 · 지금 시간대 '돈되는곳/피할곳' 데이터 스트립.
package com.callradar.app.screen

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private data class Hotspot(val id: Int, val name: String, val timeBand: String, val note: String, val hasLoc: Boolean, val confirmed: Int)

@Composable
fun RadarScreen(userId: String) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val server = Config.SERVER_URL
    val workStart = remember { prefs.getLong("work_start", 0L) }
    val live = workStart > 0L
    LaunchedEffect(Unit) { com.callradar.app.Telemetry.log(ctx, "open_screen", "radar", meta = if (live) "live" else "plan") }

    val accent = Color(0xFFF5A623); val green = Color(0xFF10B981); val red = Color(0xFFEF4444); val muted = Color(0xFF9CA3AF)

    // ── 맛집(유저 좋은자리) ──
    var hotspots by remember { mutableStateOf<List<Hotspot>>(emptyList()) }
    var hsLoading by remember { mutableStateOf(true) }
    var hsError by remember { mutableStateOf(false) }
    var crowd by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }   // [v2] 크라우드: 이름, 등록 기사 수
    var showAdd by remember { mutableStateOf(false) }
    var addName by remember { mutableStateOf("") }
    var addTime by remember { mutableStateOf("") }
    var addNote by remember { mutableStateOf("") }
    var addLat by remember { mutableStateOf<Double?>(null) }
    var addLng by remember { mutableStateOf<Double?>(null) }
    var addBusy by remember { mutableStateOf(false) }
    var locMsg by remember { mutableStateOf("") }

    // [v24 진화①] 개인 레이더 — 본인 운행 기록 기반(하드코딩 아님). 데이터 충분(10건+)일 때만 노출.
    var pOrigins by remember { mutableStateOf<List<Triple<String, Int, Int>>>(emptyList()) }
    var pHours by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }
    var pDests by remember { mutableStateOf<List<Triple<String, Int, Int>>>(emptyList()) }
    var pPersonalized by remember { mutableStateOf(false) }

    // [v24] 레이더 AI 음성비서 — 개인 레이더 요약을 음성으로 안내(TTS)
    var ttsReady by remember { mutableStateOf(false) }
    val radarTts = remember { android.speech.tts.TextToSpeech(ctx) { s -> ttsReady = (s == android.speech.tts.TextToSpeech.SUCCESS) } }
    LaunchedEffect(ttsReady) { if (ttsReady) try { radarTts.language = java.util.Locale.KOREAN } catch (e: Exception) {} }
    DisposableEffect(Unit) { onDispose { try { radarTts.stop(); radarTts.shutdown() } catch (e: Exception) {} } }
    val speakGuide: () -> Unit = {
        val sb = StringBuilder()
        if (pOrigins.isNotEmpty()) sb.append("지금 시간대엔 ${pOrigins.first().first}에서 콜이 잘 잡혔어요. ")
        if (pHours.isNotEmpty()) sb.append("제일 잘 버는 시간은 ${pHours.first().first}시예요. ")
        if (pDests.isNotEmpty()) sb.append("돈 되는 목적지는 ${pDests.first().first}입니다.")
        val msg = if (sb.isEmpty()) "아직 데이터가 부족해요. 운행이 쌓이면 맞춤 안내를 해드릴게요." else sb.toString()
        try { radarTts.speak(msg, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "radar") } catch (e: Exception) {}
    }

    fun loadHotspots() {
        hsLoading = true; hsError = false
        scope.launch {
            try {
                // 콜드스타트(Render 무료 티어) 대비 넉넉한 타임아웃
                val resp = withContext(Dispatchers.IO) { (URL("$server/api/hotspots/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 30000 }.inputStream.bufferedReader().readText() }
                val arr = JSONArray(resp)
                hotspots = (0 until arr.length()).map { i -> val o = arr.getJSONObject(i); Hotspot(o.optInt("id"), o.optString("name"), o.optString("time_band"), o.optString("note"), !o.isNull("lat"), o.optInt("confirmed")) }
                hsError = false
            } catch (e: Exception) { hsError = true }
            hsLoading = false
        }
    }
    LaunchedEffect(Unit) { loadHotspots() }
    // [v24 진화①] 개인 레이더 로드
    LaunchedEffect(Unit) {
        try {
            val hr = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul")).get(java.util.Calendar.HOUR_OF_DAY)
            val resp = withContext(Dispatchers.IO) { (URL("$server/api/radar/personal/$userId?hour=$hr").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 30000 }.inputStream.bufferedReader().readText() }
            val o = JSONObject(resp)
            pPersonalized = o.optBoolean("personalized", false)
            fun arr3(key: String): List<Triple<String, Int, Int>> { val a = o.optJSONArray(key) ?: return emptyList(); return (0 until a.length()).map { val x = a.getJSONObject(it); Triple(x.optString("name"), x.optInt("cnt"), x.optInt("avg_fare")) }.filter { it.first.isNotBlank() && !it.first.startsWith("TEST") } }
            pOrigins = arr3("topOrigins"); pDests = arr3("topDestinations")
            val h = o.optJSONArray("bestHours"); pHours = (0 until (h?.length() ?: 0)).map { val x = h!!.getJSONObject(it); Pair(x.optInt("hour"), x.optInt("avg_fare")) }
        } catch (e: Exception) {}
    }
    // [v2] 크라우드 맛집 — 여러 기사가 등록한 자리
    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) {
            try {
                val resp = (URL("$server/api/hotspots/crowd/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 30000 }.inputStream.bufferedReader().readText()
                val arr = JSONObject(resp).optJSONArray("spots")
                (0 until (arr?.length() ?: 0)).map { val o = arr!!.getJSONObject(it); Pair(o.optString("name").ifBlank { "이름 없음" }, o.optInt("drivers")) }
            } catch (e: Exception) { emptyList() }
        }
        crowd = list
    }

    @SuppressLint("MissingPermission")
    fun captureLoc() {
        addLat = null; addLng = null; locMsg = "위치 확인 중…"
        val granted = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) { locMsg = "위치 권한이 없어 이름만 저장돼요"; return }
        try {
            LocationServices.getFusedLocationProviderClient(ctx).lastLocation
                .addOnSuccessListener { loc -> if (loc != null) { addLat = loc.latitude; addLng = loc.longitude; locMsg = "📍 현재 위치 담김" } else locMsg = "위치를 못 잡아 이름만 저장돼요" }
                .addOnFailureListener { locMsg = "위치를 못 잡아 이름만 저장돼요" }
        } catch (e: Exception) { locMsg = "위치를 못 잡아 이름만 저장돼요" }
    }

    fun saveHotspot() {
        if (addBusy || addName.isBlank()) return
        addBusy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val json = JSONObject().apply {
                        put("user_id", userId.toIntOrNull() ?: userId); put("name", addName.trim())
                        if (addLat != null) put("lat", addLat); if (addLng != null) put("lng", addLng)
                        put("time_band", addTime); put("note", addNote)
                    }
                    val conn = (URL("$server/api/hotspots").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 8000 }
                    conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }; conn.responseCode
                }
                addName = ""; addTime = ""; addNote = ""; addLat = null; addLng = null; locMsg = ""; showAdd = false
                loadHotspots()
            } catch (e: Exception) {}
            addBusy = false
        }
    }
    fun delHotspot(id: Int) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val conn = (URL("$server/api/hotspots/$id").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "DELETE"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000 }
                    conn.outputStream.use { it.write(JSONObject().apply { put("user_id", userId.toIntOrNull() ?: userId) }.toString().toByteArray()) }; conn.responseCode
                }
                loadHotspots()
            } catch (e: Exception) {}
        }
    }

    // ── 지금 시간대: 돈되는곳/피할곳 (수입 히트맵 + 배드타임 역산) ──
    var nowIncome by remember { mutableStateOf(-1) }
    var nowGap by remember { mutableStateOf(-1) }
    var incomeRank by remember { mutableStateOf(0f) }   // 0~1 (현재 시간대 수입의 상대 순위)
    var worstZone by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val cal = java.util.Calendar.getInstance()
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1
        val hr = cal.get(java.util.Calendar.HOUR_OF_DAY)
        withContext(Dispatchers.IO) {
            try {
                val cells = JSONObject(URL("$server/api/stats/income-heatmap/$userId").readText()).optJSONArray("cells")
                var cur = 0; var mx = 1
                for (i in 0 until (cells?.length() ?: 0)) { val o = cells!!.getJSONObject(i); val inc = o.optInt("income"); if (inc > mx) mx = inc; if (o.optInt("dow") == dow && o.optInt("hour") == hr) cur = inc }
                nowIncome = cur; incomeRank = if (mx > 0) cur.toFloat() / mx else 0f
            } catch (e: Exception) {}
            try {
                val dz = JSONObject(URL("$server/api/stats/deadzones/$userId").readText())
                val bt = dz.optJSONArray("badTimes")
                for (i in 0 until (bt?.length() ?: 0)) { val o = bt!!.getJSONObject(i); if (o.optInt("dow") == dow && o.optInt("hour") == hr) { nowGap = o.optInt("avg_gap"); break } }
                val bz = dz.optJSONArray("badZones")
                if (bz != null && bz.length() > 0) worstZone = bz.getJSONObject(0).optString("area")
            } catch (e: Exception) {}
        }
    }

    val timeBands = listOf("아무때나", "새벽", "오전", "점심", "오후", "저녁", "심야")

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { if (!addBusy) showAdd = false },
            title = { Text("🍜 맛집(좋은 자리) 등록", color = AppTheme.text, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (locMsg.isNotBlank()) locMsg else "지금 있는 자리를 좋은 콜 자리로 저장해요. 이름만 써도 됩니다.", fontSize = 12.sp, color = if (addLat != null) green else muted)
                    OutlinedTextField(value = addName, onValueChange = { addName = it }, label = { Text("이름 (예: 강남역 11번출구)", color = muted) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                    Text("언제 좋아요?", fontSize = 12.sp, color = muted)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        timeBands.forEach { t -> FilterChip(selected = addTime == t, onClick = { addTime = if (addTime == t) "" else t }, label = { Text(t, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted)) }
                    }
                    OutlinedTextField(value = addNote, onValueChange = { addNote = it }, label = { Text("메모 (선택)", color = muted) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF374151), focusedTextColor = AppTheme.text, unfocusedTextColor = AppTheme.text))
                }
            },
            confirmButton = { Button(onClick = { saveHotspot() }, enabled = addName.isNotBlank() && !addBusy, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text(if (addBusy) "저장 중" else "저장", color = Color.Black, fontWeight = FontWeight.Bold) } },
            dismissButton = { OutlinedButton(onClick = { if (!addBusy) showAdd = false }) { Text("취소") } },
            containerColor = AppTheme.card
        )
    }

    Column(Modifier.fillMaxSize().background(AppTheme.bg)) {
        // 모드 배너
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📡 레이더", color = AppTheme.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.background((if (live) green else muted).copy(alpha = 0.18f), RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(if (live) "🟢 근무중 · 코파일럿" else "⚪ 대기 · 작전 지도", color = if (live) green else muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // [v24] 지도 = 메인(전체). 좌측 플로팅 버튼을 누르면 정보가 오버레이로 뜸(접이식 X).
        Box(Modifier.fillMaxWidth().weight(1f)) {
            DriverMapScreen(userId = userId, onBack = {}, embedded = true)   // 배경 = 지도(히트맵)

            var openPanel by remember { mutableStateOf("") }  // ""=닫힘 | personal | now | spots | crowd

            // 좌측 세로 플로팅 버튼
            Column(Modifier.align(Alignment.CenterStart).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RadarFab("🔊", false) { speakGuide() }   // [v24] AI 음성 안내
                if (pPersonalized) RadarFab("🎯", openPanel == "personal") { openPanel = if (openPanel == "personal") "" else "personal" }
                RadarFab("🤖", openPanel == "now") { openPanel = if (openPanel == "now") "" else "now" }
                RadarFab("🍜", openPanel == "spots") { openPanel = if (openPanel == "spots") "" else "spots" }
                if (crowd.isNotEmpty()) RadarFab("🌐", openPanel == "crowd") { openPanel = if (openPanel == "crowd") "" else "crowd" }
            }

            // 선택된 버튼 정보 — 지도 위 오버레이 패널
            if (openPanel.isNotEmpty()) {
                Card(Modifier.align(Alignment.CenterStart).padding(start = 64.dp, end = 12.dp).widthIn(max = 460.dp), colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(when (openPanel) { "personal" -> "🎯 내 데이터 레이더"; "now" -> "🤖 지금 시간대"; "spots" -> "🍜 내 맛집"; else -> "🌐 다른 기사 자리" }, color = AppTheme.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("✕", color = muted, fontSize = 16.sp, modifier = Modifier.clickable { openPanel = "" })
                        }
                        when (openPanel) {
                            "personal" -> {
                                if (pOrigins.isNotEmpty()) {
                                    Text("이 시간대 내가 콜 잘 잡은 곳", color = muted, fontSize = 11.sp)
                                    pOrigins.take(4).forEach { (name, cnt, avg) ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("· $name", color = AppTheme.text, fontSize = 13.sp)
                                            Text("${cnt}콜" + (if (avg > 0) " · 평균 ${String.format("%,d", avg)}원" else ""), color = green, fontSize = 12.sp)
                                        }
                                    }
                                }
                                if (pHours.isNotEmpty()) { val top = pHours.first(); Text("내가 제일 잘 버는 시간: ${top.first}시" + (if (top.second > 0) " (평균 ${String.format("%,d", top.second)}원)" else ""), color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                if (pDests.isNotEmpty()) { val d = pDests.first(); Text("돈 되는 목적지: ${d.first}" + (if (d.third > 0) " (평균 ${String.format("%,d", d.third)}원)" else ""), color = muted, fontSize = 12.sp) }
                            }
                            "now" -> {
                                val head = when {
                                    incomeRank >= 0.66f -> "지금은 벌이 좋은 시간대예요"
                                    nowGap in 1..999 && nowGap >= 60 -> "지금은 공차가 길 수 있어요"
                                    nowIncome >= 0 -> "지금 시간대 흐름이에요"
                                    else -> "데이터를 불러오는 중…"
                                }
                                Text(head, color = AppTheme.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                if (nowIncome > 0) Text("이 시간대 누적 수입 ${String.format("%,d", nowIncome)}원" + (if (nowGap in 1..999) " · 평균 공차 ${nowGap}분" else ""), color = muted, fontSize = 12.sp)
                                if (worstZone.isNotBlank()) Text("⚠️ ${worstZone} 쪽에 내리면 다음 콜까지 오래 걸려요", color = red.copy(alpha = 0.85f), fontSize = 11.sp)
                            }
                            "spots" -> {
                                Button(onClick = { captureLoc(); showAdd = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(9.dp), colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("➕ 지금 자리 등록", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                if (hotspots.isEmpty() && hsLoading) {
                                    Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("불러오는 중…", fontSize = 12.sp, color = muted) }
                                } else if (hotspots.isEmpty() && hsError) {
                                    Row(verticalAlignment = Alignment.CenterVertically) { Text("불러오지 못했어요", fontSize = 12.sp, color = red.copy(alpha = 0.85f), modifier = Modifier.weight(1f)); TextButton(onClick = { loadHotspots() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("다시 시도", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold) } }
                                } else if (hotspots.isEmpty()) {
                                    Text("몸으로 아는 좋은 자리를 등록해두면, 그 근처에 오면 알려줘요.", fontSize = 12.sp, color = muted)
                                } else {
                                    hotspots.forEach { h ->
                                        Box(Modifier.fillMaxWidth().background(AppTheme.surface2, RoundedCornerShape(10.dp)).clickable { delHotspot(h.id) }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                            Column {
                                                Text((if (h.hasLoc) "📍 " else "") + h.name, color = AppTheme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                val sub = listOfNotNull(h.timeBand.ifBlank { null }, h.note.ifBlank { null }).joinToString(" · ")
                                                if (sub.isNotBlank()) Text(sub, color = muted, fontSize = 10.sp)
                                                if (h.confirmed > 0) Text("✓ 데이터 ${h.confirmed}회", color = green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                    Text("칩을 누르면 삭제돼요", fontSize = 9.sp, color = muted)
                                }
                            }
                            else -> {
                                crowd.forEach { (name, drivers) ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name, color = AppTheme.text, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text("${drivers}명 등록", color = green, fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// [v24] 지도 위 좌측 플로팅 버튼 (누르면 해당 정보 오버레이)
@Composable
private fun RadarFab(icon: String, active: Boolean, onClick: () -> Unit) {
    val accent = Color(0xFFF5A623)
    Box(
        Modifier.size(46.dp)
            .background(if (active) accent else AppTheme.card, RoundedCornerShape(23.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(icon, fontSize = 20.sp) }
}
