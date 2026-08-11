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
private data class RSpot(val name: String, val cnt: Int, val avg: Int, val dist: Double)   // [v26] 개인레이더 스팟(거리 포함)

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
    var pOrigins by remember { mutableStateOf<List<RSpot>>(emptyList()) }
    var pHours by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }
    var pDests by remember { mutableStateOf<List<Triple<String, Int, Int>>>(emptyList()) }
    var pPersonalized by remember { mutableStateOf(false) }
    var pNearby by remember { mutableStateOf(false) }        // [v26] 위치기반 결과 여부
    var pDestPooled by remember { mutableStateOf(false) }    // [v54 ⑤⑥] 돈되는 목적지가 전체기사 통합 폴백인지
    var curLat by remember { mutableStateOf(0.0) }
    var curLng by remember { mutableStateOf(0.0) }

    // [v24] 레이더 AI 음성비서 — 개인 레이더 요약을 음성으로 안내(TTS)
    var ttsReady by remember { mutableStateOf(false) }
    val radarTts = remember { android.speech.tts.TextToSpeech(ctx) { s -> ttsReady = (s == android.speech.tts.TextToSpeech.SUCCESS) } }
    LaunchedEffect(ttsReady) { if (ttsReady) try { radarTts.language = java.util.Locale.KOREAN } catch (e: Exception) {} }
    DisposableEffect(Unit) { onDispose { try { radarTts.stop(); radarTts.shutdown() } catch (e: Exception) {} } }
    val speakGuide: () -> Unit = {
        val sb = StringBuilder()
        if (pOrigins.isNotEmpty()) sb.append("지금 내 근처 ${pOrigins.first().name}에서 콜이 잘 잡혔어요. ")
        if (pHours.isNotEmpty()) sb.append("제일 잘 버는 시간은 ${pHours.first().first}시예요. ")
        if (pDests.isNotEmpty()) sb.append(if (pDestPooled) "이 근처 전체 기사 기준, 돈 되는 목적지는 ${pDests.first().first}입니다." else "지금 위치에서 돈 되는 목적지는 ${pDests.first().first}입니다.")
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
    // [v26] 현재 위치 취득 (개인 레이더 = 내 근처 기준)
    LaunchedEffect(Unit) {
        try {
            val ok = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                     ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (ok) LocationServices.getFusedLocationProviderClient(ctx).lastLocation.addOnSuccessListener { loc -> if (loc != null) { curLat = loc.latitude; curLng = loc.longitude } }
        } catch (e: Exception) {}
    }
    // [v24 진화①/v26] 개인 레이더 로드 — 위치 오면 '내 근처(거리순)'로 재조회
    LaunchedEffect(curLat, curLng) {
        try {
            val hr = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul")).get(java.util.Calendar.HOUR_OF_DAY)
            val loc = if (curLat != 0.0 && curLng != 0.0) "&lat=$curLat&lng=$curLng" else ""
            val resp = withContext(Dispatchers.IO) { (URL("$server/api/radar/personal/$userId?hour=$hr$loc").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 30000 }.inputStream.bufferedReader().readText() }
            val o = JSONObject(resp)
            pPersonalized = o.optBoolean("personalized", false)
            pNearby = o.optBoolean("nearby", false)
            pDestPooled = o.optBoolean("destPooled", false)
            val oa = o.optJSONArray("topOrigins")
            pOrigins = (0 until (oa?.length() ?: 0)).map { val x = oa!!.getJSONObject(it); RSpot(x.optString("name"), x.optInt("cnt"), x.optInt("avg_fare"), x.optDouble("dist_km", -1.0)) }.filter { it.name.isNotBlank() && !it.name.startsWith("TEST") }
            val da = o.optJSONArray("topDestinations")
            pDests = (0 until (da?.length() ?: 0)).map { val x = da!!.getJSONObject(it); Triple(x.optString("name"), x.optInt("cnt"), x.optInt("avg_fare")) }.filter { it.first.isNotBlank() && !it.first.startsWith("TEST") }
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
                    val conn = (URL("$server/api/hotspots").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 8000; readTimeout = 15000 }
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
                    val conn = (URL("$server/api/hotspots/$id").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "DELETE"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 15000 }
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
                val cells = JSONObject((URL("$server/api/stats/income-heatmap/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as java.net.HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 30000 }.inputStream.bufferedReader().use { it.readText() }).optJSONArray("cells")
                var cur = 0; var mx = 1
                for (i in 0 until (cells?.length() ?: 0)) { val o = cells!!.getJSONObject(i); val inc = o.optInt("income"); if (inc > mx) mx = inc; if (o.optInt("dow") == dow && o.optInt("hour") == hr) cur = inc }
                nowIncome = cur; incomeRank = if (mx > 0) cur.toFloat() / mx else 0f
            } catch (e: Exception) {}
            try {
                val dz = JSONObject((URL("$server/api/stats/deadzones/$userId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as java.net.HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 30000 }.inputStream.bufferedReader().use { it.readText() })
                val bt = dz.optJSONArray("badTimes")
                for (i in 0 until (bt?.length() ?: 0)) { val o = bt!!.getJSONObject(i); if (o.optInt("dow") == dow && o.optInt("hour") == hr) { nowGap = o.optInt("avg_gap"); break } }
                val bz = dz.optJSONArray("badZones")
                if (bz != null && bz.length() > 0) worstZone = bz.getJSONObject(0).optString("area")
            } catch (e: Exception) {}
        }
    }

    // [v32] 효율 레이더 — 공차 기반(radar2): 실차율/순원분 + 목적지 리스크 + hotzone
    var effOcc by remember { mutableStateOf(-1) }
    var effWonMin by remember { mutableStateOf(-1) }
    var effGap by remember { mutableStateOf(0) }
    var destRisk by remember { mutableStateOf<List<Triple<String, Double, Int>>>(emptyList()) }
    var destBasis by remember { mutableStateOf("") }   // [v44] "me"=내 기록 / "all"=전체 기사(폴백). 정직 표기용.
    var hzone by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    var hzScope by remember { mutableStateOf(0) }   // 0=전체(지금 시간대) · 1=내 누적 · 2=오늘(내)
    fun rget(path: String): String = (URL("$server$path").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 30000 }.inputStream.bufferedReader().readText()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val e = JSONObject(rget("/api/radar/efficiency?user_id=$userId&period=today"))
                effOcc = if (e.isNull("occupancyRate")) -1 else e.optInt("occupancyRate", -1)
                effWonMin = if (e.isNull("wonPerMin")) -1 else e.optInt("wonPerMin", -1)
                effGap = e.optInt("gapMin", 0)
            } catch (ex: Exception) {}
            try {
                fun parseDr(s: String): List<Triple<String, Double, Int>> {
                    val a = JSONObject(s).optJSONArray("rows")
                    return (0 until (a?.length() ?: 0)).map { val o = a!!.getJSONObject(it); Triple(o.optString("dest"), o.optDouble("median_wait_min", 0.0), o.optInt("samples")) }.filter { it.first.isNotBlank() }
                }
                // [v54] 내 기록 + 전체 기사 종합 — 개인이 얇아도 전체로 풍부하게. 중복 목적지는 내 기록 우선, 나머지는 전체로 보강.
                val mine = parseDr(rget("/api/radar/dest-risk?user_id=$userId"))
                val all = parseDr(rget("/api/radar/dest-risk"))
                val merged = LinkedHashMap<String, Triple<String, Double, Int>>()
                for (r in mine) merged[r.first] = r
                for (r in all) if (!merged.containsKey(r.first)) merged[r.first] = r
                val rows = merged.values.sortedBy { it.second }.take(6)   // 다음 콜까지 대기 짧은 순
                val basis = when { mine.isEmpty() -> "all"; all.size > mine.size -> "mix"; else -> "me" }
                destRisk = rows; destBasis = if (rows.isEmpty()) "" else basis
            } catch (ex: Exception) {}
        }
    }
    // [v34] hotzone은 GPS 확보/변경 시 재조회 — 내 반경(8km) 내 출발 콜만 집계(전국 뒤섞임 방지).
    //  GPS 없을 땐 위치 없이 전국(기존 호환). 있으면 lat/lng로 내 지역 기준.
    LaunchedEffect(curLat, curLng, hzScope) {
        withContext(Dispatchers.IO) {
            try {
                // [레이더 토글] 0=전체(지금 시간대) · 1=내 누적 · 2=오늘(내)
                val hr = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul")).get(java.util.Calendar.HOUR_OF_DAY)
                val qs = StringBuilder("?")
                when (hzScope) {
                    1 -> qs.append("scope=me&user_id=$userId")               // 내 누적
                    2 -> qs.append("scope=me&user_id=$userId&today=1")       // 오늘(내)
                    else -> qs.append("hour=$hr")                            // 전체·지금 시간대
                }
                if (curLat != 0.0 && curLng != 0.0) qs.append("&lat=$curLat&lng=$curLng")
                val a = JSONObject(rget("/api/radar/hotzone$qs")).optJSONArray("rows")
                hzone = (0 until (a?.length() ?: 0)).map { val o = a!!.getJSONObject(it); Pair(o.optString("cell"), o.optDouble("hazard", 0.0)) }.filter { it.first.isNotBlank() }
            } catch (ex: Exception) {}
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

    // [v44 재설계] "손님 내리면 어디 가야 콜 빨리 잡나"를 누르지 않아도 바로 보이게. 지도는 위에 작게, 정보는 아래 항상 표시.
    var showTrack by remember { mutableStateOf(true) }
    val maxHz = (hzone.maxOfOrNull { it.second } ?: 1.0).coerceAtLeast(0.0001)
    Column(Modifier.fillMaxSize().background(AppTheme.bg)) {
        // 헤더 (상태바 침범 방지 top 여백)
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 44.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📡 레이더", color = AppTheme.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.background((if (live) green else muted).copy(alpha = 0.18f), RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(if (live) "🟢 근무중" else "⚪ 대기", color = if (live) green else muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 지도 (상단 고정 높이) + 우상단 작은 토글 2개(음성/궤적)
        Box(Modifier.fillMaxWidth().height(240.dp)) {
            DriverMapScreen(userId = userId, onBack = {}, embedded = true, showTrack = showTrack)
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RadarFab("🔊", false) { speakGuide() }
                RadarFab("🧭", showTrack) { showTrack = !showTrack }
            }
        }

        // 정보 (스크롤) — 항상 보임, 버튼 뒤에 안 숨김
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // 요약 스트립: 실차율 · 순원/분 · 공차
            if (effOcc >= 0 || effWonMin >= 0 || effGap > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadarStat("실차율", if (effOcc >= 0) "${effOcc}%" else "-", green, Modifier.weight(1f))
                    RadarStat("순원/분", if (effWonMin >= 0) String.format("%,d", effWonMin) else "-", accent, Modifier.weight(1f))
                    RadarStat("공차", if (effGap > 0) "${effGap}분" else "-", muted, Modifier.weight(1f))
                }
            }

            // ★ HERO — 손님 내리면 콜 빨리 잡히는 곳 (레이더의 존재 이유)
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("🎯 손님 내리면 — 콜 빨리 잡히는 곳", color = AppTheme.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (destRisk.isNotEmpty()) {
                        Text(when (destBasis) { "me" -> "📍 내 운행 기록 기준"; "mix" -> "📍 내 기록 + 전체 기사 종합"; else -> "📍 전체 기사 기준 · 기사 늘수록 정확해져요" }, color = muted, fontSize = 10.sp)
                        destRisk.take(5).forEach { (d, m, n) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(d, color = AppTheme.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text("약 ${m.toInt()}분 · ${n}건", color = if (m <= 8) green else if (m >= 20) red else accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text("숫자 = 그 동네에 내리면 다음 콜까지 평균 대기(짧을수록 좋음)", color = muted, fontSize = 10.sp)
                    } else {
                        Text("운행이 더 쌓이면 '어디 내리면 콜 빨리 잡히는지'를 알려드려요.", color = muted, fontSize = 12.sp)
                    }
                }
            }

            // 🔥 콜 많은 동네 + [오늘 / 내 누적 / 전체] 토글 (막대로 직관화)
            run {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🔥 콜 많은 동네", color = AppTheme.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        // [지도필터로 이동] 오늘/내누적/전체 토글 제거 → 지도 위 버튼(오늘/30k/50k)으로 통합.
                        Text("지금 시간대 · 이 근처 · 전체 기사(기사 늘수록 정확)", color = muted, fontSize = 10.sp)
                        if (hzone.isEmpty()) {
                            Text("이 조건엔 아직 데이터가 없어요", color = muted, fontSize = 12.sp)
                        } else {
                            hzone.take(6).forEach { (c, hz) ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(c, color = AppTheme.text, fontSize = 14.sp, modifier = Modifier.width(90.dp))
                                    Box(Modifier.weight(1f).height(12.dp).background(AppTheme.surface2, RoundedCornerShape(6.dp))) {
                                        Box(Modifier.fillMaxHeight().fillMaxWidth((hz / maxHz).toFloat().coerceIn(0.06f, 1f)).background(green, RoundedCornerShape(6.dp)))
                                    }
                                }
                            }
                            Text("막대가 길수록 콜이 잘 잡히는 동네예요", color = muted, fontSize = 10.sp)
                        }
                    }
                }
            }

            // 지금 시간대 흐름 (간단)
            if (nowIncome > 0 || worstZone.isNotBlank()) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val head = when {
                            incomeRank >= 0.66f -> "🟢 지금은 벌이 좋은 시간대"
                            nowGap in 60..999 -> "🟠 지금은 공차가 길 수 있어요"
                            else -> "지금 시간대 흐름"
                        }
                        Text(head, color = AppTheme.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        if (nowGap in 1..999) Text("이 시간대 평균 공차 ${nowGap}분", color = muted, fontSize = 12.sp)
                        if (worstZone.isNotBlank()) Text("⚠️ ${worstZone} 쪽에 내리면 다음 콜까지 오래 걸려요", color = red.copy(alpha = 0.85f), fontSize = 11.sp)
                    }
                }
            }

            // 🍜 내 맛집(좋은 자리) — 등록 + 목록
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("🍜 내 맛집(좋은 자리)", color = AppTheme.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Button(onClick = { captureLoc(); showAdd = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(9.dp), colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("➕ 지금 자리", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                    if (hotspots.isEmpty() && hsLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("불러오는 중…", fontSize = 12.sp, color = muted) }
                    } else if (hotspots.isEmpty()) {
                        Text("몸으로 아는 좋은 자리를 등록해두면 그 근처에서 알려줘요.", fontSize = 12.sp, color = muted)
                    } else {
                        hotspots.forEach { h ->
                            Box(Modifier.fillMaxWidth().background(AppTheme.surface2, RoundedCornerShape(10.dp)).clickable { delHotspot(h.id) }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Column {
                                    Text((if (h.hasLoc) "📍 " else "") + h.name, color = AppTheme.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    val sub = listOfNotNull(h.timeBand.ifBlank { null }, h.note.ifBlank { null }).joinToString(" · ")
                                    if (sub.isNotBlank()) Text(sub, color = muted, fontSize = 10.sp)
                                }
                            }
                        }
                        Text("항목을 누르면 삭제돼요", fontSize = 9.sp, color = muted)
                    }
                }
            }

            // 🎯 내 데이터 레이더 (내 기록 기반, 있을 때만)
            if (pPersonalized && pOrigins.isNotEmpty()) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (pNearby) "🎯 내 근처 · 이 시간대 내가 콜 잘 잡은 곳" else "🎯 이 시간대 내가 콜 잘 잡은 곳", color = AppTheme.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        pOrigins.take(4).forEach { s ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("· ${s.name}" + (if (s.dist >= 0) "  ${s.dist}km" else ""), color = if (s.dist in 0.0..3.0) green else AppTheme.text, fontSize = 13.sp)
                                Text("${s.cnt}콜", color = green, fontSize = 12.sp)
                            }
                        }
                        if (pHours.isNotEmpty()) { val top = pHours.first(); Text("내가 제일 잘 버는 시간: ${top.first}시", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// [v44] 레이더 요약 스탯 칩
@Composable
private fun RadarStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.background(AppTheme.card, RoundedCornerShape(12.dp)).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color(0xFF9CA3AF), fontSize = 10.sp)
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
