package com.callradar.app

// [행사개편] 대형 행사·야구·공연 수요 예보 화면 — 간편모드 콜카드/메뉴에서 진입.
//  서버가 대형만 필터해 내려줌(잡축제 제거). 야구는 memo에 "시작·예상 종료" 포함 → 퇴근길 수요 타이밍 재료.
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.callradar.app.ui.theme.CallRadarTheme
import com.callradar.app.screen.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

class EventsActivity : ComponentActivity() {
    companion object {
        private const val SERVER_URL = "https://callradar-server.onrender.com"
        fun start(context: Context) {
            context.startActivity(Intent(context, EventsActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }

    data class Ev(val title: String, val category: String, val venue: String, val area: String, val start: String, val end: String, val memo: String)

    private val gpsRegion = mutableStateOf("")   // [GPS 지역] 현재 위치로 판별한 시도

    // [GPS 지역] 좌표 → 시도 근사 판별 (행정 경계 bbox 근사 — 필터 기본값 용도라 충분)
    private fun regionOf(lat: Double, lng: Double): String = when {
        lat in 37.42..37.72 && lng in 126.76..127.19 -> "서울"
        lat in 37.33..37.62 && lng in 126.36..126.80 -> "인천"
        lat in 36.89..38.30 && lng in 126.50..127.87 -> "경기"
        lat in 35.02..35.40 && lng in 128.75..129.31 -> "부산"
        lat in 35.60..36.02 && lng in 128.35..128.77 -> "대구"
        lat in 35.05..35.27 && lng in 126.64..127.02 -> "광주"
        lat in 36.18..36.50 && lng in 127.25..127.56 -> "대전"
        lat in 35.40..35.73 && lng in 129.00..129.47 -> "울산"
        lat in 34.90..35.90 && lng in 127.85..129.20 -> "경남"
        else -> ""
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun detectGpsRegion() {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this).lastLocation
                .addOnSuccessListener { loc -> if (loc != null) gpsRegion.value = regionOf(loc.latitude, loc.longitude) }
        } catch (e: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTheme.isDark = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getBoolean("dark_mode", true)
        detectGpsRegion()   // [GPS 지역] 현재 위치 기준 기본 필터
        setContent { CallRadarTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        val accent = Color(0xFFF59E0B); val muted = Color(0xFF6B7280); val green = Color(0xFF10B981)
        var events by remember { mutableStateOf<List<Ev>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        // [지역 개선] 유저가 브리핑 설정에서 고른 내 지역을 기본 선택 (지방 기사도 자기 동네 우선)
        val myRegion = remember {
            (getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getString("event_regions", "") ?: "")
                .split(",").firstOrNull { it.isNotBlank() }?.trim() ?: ""
        }
        var areaFilter by remember { mutableStateOf(if (myRegion.isNotBlank()) myRegion else "전체") }
        var userPicked by remember { mutableStateOf(false) }
        val gpsReg by gpsRegion
        // [GPS 지역] 설정 지역이 없으면 GPS로 판별된 시도를 기본 선택 (유저가 직접 고르기 전까지만)
        LaunchedEffect(gpsReg) { if (!userPicked && myRegion.isBlank() && gpsReg.isNotBlank()) areaFilter = gpsReg }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            scope.launch {
                try {
                    val body = withContext(Dispatchers.IO) {
                        (URL("$SERVER_URL/api/events?days=30").openConnection().apply { com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") } } as HttpURLConnection)
                            .apply { connectTimeout = 8000; readTimeout = 20000 }.inputStream.bufferedReader().readText()
                    }
                    val arr = JSONArray(body)
                    events = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        Ev(o.optString("title"), o.optString("category"), o.optString("venue"), o.optString("area"),
                           o.optString("start_at").take(10), o.optString("end_at").take(10), o.optString("memo"))
                    }
                } catch (e: Exception) {} finally { loading = false }
            }
        }

        // [중복·구분 개선] 제목+장소+날짜로 중복 제거, 야구는 오늘/내일 분리(연전이 중복처럼 보이던 문제)
        val filtered = events.filter { areaFilter == "전체" || it.area.contains(areaFilter) }
            .distinctBy { "${it.title}|${it.venue}|${it.start}" }
        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA)
            .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul") }.format(java.util.Date())
        // [시간 경과 숨김] 예상 종료 +1시간 지났거나 '종료됨' 표시된 경기는 오늘 목록에서 제거
        val nowMin = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"))
            .let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
        val sportsToday = filtered.filter { it.category == "스포츠" && it.start == todayStr }
            .filterNot { e ->
                if (e.memo.contains("종료됨")) true
                else Regex("예상 종료 (\\d{2}):(\\d{2})").find(e.memo)?.let { m ->
                    val endMin = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
                    endMin in 300..1439 && nowMin > endMin + 60   // 심야(00~05시 종료)는 자정 넘김이라 제외 안 함
                } ?: false
            }
        val sportsTomorrow = filtered.filter { it.category == "스포츠" && it.start > todayStr }
        val others = filtered.filter { it.category != "스포츠" }

        Column(Modifier.fillMaxSize().background(AppTheme.bg)) {
            Row(Modifier.fillMaxWidth().background(AppTheme.card).padding(top = 34.dp, start = 10.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { finish() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("‹", fontSize = 24.sp, color = accent, fontWeight = FontWeight.Bold); Spacer(Modifier.width(4.dp)); Text("홈", fontSize = 14.sp, color = accent) }
                Spacer(Modifier.width(4.dp))
                Text("행사 · 수요 예보", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
            }
            // [지역 개선] 칩을 데이터에 있는 지역으로 동적 생성 — 부산·광주·대구 등 지방도 자동 표시, 내 지역 맨 앞
            val areas = remember(events, myRegion) {
                val fromData = events.mapNotNull { it.area.takeIf { a -> a.isNotBlank() && a != "null" } }.distinct().sorted()
                val ordered = if (myRegion.isNotBlank() && fromData.contains(myRegion))
                    listOf(myRegion) + fromData.filter { it != myRegion } else fromData
                listOf("전체") + ordered
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                areas.forEach { a ->
                    FilterChip(selected = areaFilter == a, onClick = { areaFilter = a; userPicked = true }, label = { Text(a, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
                }
            }
            if (loading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) } }
            else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                if (sportsToday.isNotEmpty()) {
                    item { Text("⚾ 오늘 야구", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(top = 4.dp)) }
                    items(sportsToday) { e -> EvCard(e, green, muted, coach = true) }
                }
                if (sportsTomorrow.isNotEmpty()) {
                    item { Text("⚾ 내일 야구", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = muted, modifier = Modifier.padding(top = 8.dp)) }
                    items(sportsTomorrow) { e -> EvCard(e, muted, muted) }
                }
                if (others.isNotEmpty()) {
                    item { Text("🎪 대형 행사 · 공연", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(top = 8.dp)) }
                    items(others) { e -> EvCard(e, accent, muted) }
                }
                if (sportsToday.isEmpty() && sportsTomorrow.isEmpty() && others.isEmpty()) {
                    item { Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { Text("이 지역엔 예정된 대형 행사가 없어요", fontSize = 13.sp, color = muted) } }
                }
            }
        }
    }

    @Composable
    private fun EvCard(e: Ev, tint: Color, muted: Color, coach: Boolean = false) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(e.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text, modifier = Modifier.weight(1f))
                    if (e.area.isNotBlank() && e.area != "null") Text(e.area, fontSize = 11.sp, color = tint, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(3.dp))
                if (e.venue.isNotBlank() && e.venue != "null") Text(e.venue, fontSize = 11.sp, color = muted, maxLines = 1)
                // [내용 개선] 야구는 날짜 대신 시간만(섹션이 오늘/내일 구분), 그 외는 기간 표시
                val memoOk = e.memo.isNotBlank() && e.memo != "null"
                if (e.category == "스포츠") {
                    if (memoOk) Text(e.memo, fontSize = 12.sp, color = tint, fontWeight = FontWeight.Bold)
                } else {
                    val dateStr = if (e.end.isNotBlank() && e.end != e.start && e.end != "null") "${e.start} ~ ${e.end}" else e.start
                    Text(dateStr + (if (memoOk) " · ${e.memo}" else ""), fontSize = 11.sp, color = tint)
                }
                // [수요 코치] 오늘 경기: 종료 시각에서 대기 타이밍을 계산해 행동 조언 한 줄
                if (coach && memoOk) {
                    val m = Regex("예상 종료 (\\d{2}):(\\d{2})").find(e.memo)
                    if (m != null) {
                        val eh = m.groupValues[1].toInt(); val em = m.groupValues[2].toInt()
                        val waitMin = (eh * 60 + em - 30 + 1440) % 1440
                        val wh = waitMin / 60; val wm = waitMin % 60
                        Spacer(Modifier.height(6.dp))
                        Text("💡 ${String.format("%02d:%02d", wh, wm)}부터 구장 주변 대기 추천 — 점수 차 크면 더 일찍 나와요", fontSize = 11.sp, color = Color(0xFFFBBF24))
                    }
                }
            }
        }
    }
}
