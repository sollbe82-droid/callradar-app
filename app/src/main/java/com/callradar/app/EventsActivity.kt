package com.callradar.app

// [행사개편] 대형 행사·야구·공연 수요 예보 화면 — 간편모드 콜카드/메뉴에서 진입.
//  서버가 대형만 필터해 내려줌(잡축제 제거). 야구는 memo에 "시작·예상 종료" 포함 → 퇴근길 수요 타이밍 재료.
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTheme.isDark = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getBoolean("dark_mode", true)
        setContent { CallRadarTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        val accent = Color(0xFFF59E0B); val muted = Color(0xFF6B7280); val green = Color(0xFF10B981)
        var events by remember { mutableStateOf<List<Ev>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var areaFilter by remember { mutableStateOf("전체") }
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

        val filtered = events.filter { areaFilter == "전체" || it.area.contains(areaFilter) }
        val sports = filtered.filter { it.category == "스포츠" }
        val others = filtered.filter { it.category != "스포츠" }

        Column(Modifier.fillMaxSize().background(AppTheme.bg)) {
            Row(Modifier.fillMaxWidth().background(AppTheme.card).padding(top = 34.dp, start = 10.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { finish() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("‹", fontSize = 24.sp, color = accent, fontWeight = FontWeight.Bold); Spacer(Modifier.width(4.dp)); Text("홈", fontSize = 14.sp, color = accent) }
                Spacer(Modifier.width(4.dp))
                Text("행사 · 수요 예보", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
            }
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("전체", "서울", "경기", "인천").forEach { a ->
                    FilterChip(selected = areaFilter == a, onClick = { areaFilter = a }, label = { Text(a, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
                }
            }
            if (loading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) } }
            else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                if (sports.isNotEmpty()) {
                    item { Text("⚾ 오늘·내일 야구 (종료 후 수요)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(top = 4.dp)) }
                    items(sports) { e -> EvCard(e, green, muted) }
                }
                if (others.isNotEmpty()) {
                    item { Text("🎪 대형 행사 · 공연", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(top = 8.dp)) }
                    items(others) { e -> EvCard(e, accent, muted) }
                }
                if (sports.isEmpty() && others.isEmpty()) {
                    item { Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { Text("이 지역엔 예정된 대형 행사가 없어요", fontSize = 13.sp, color = muted) } }
                }
            }
        }
    }

    @Composable
    private fun EvCard(e: Ev, tint: Color, muted: Color) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(e.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.text, modifier = Modifier.weight(1f))
                    if (e.area.isNotBlank() && e.area != "null") Text(e.area, fontSize = 11.sp, color = tint, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(3.dp))
                if (e.venue.isNotBlank() && e.venue != "null") Text(e.venue, fontSize = 11.sp, color = muted, maxLines = 1)
                val dateStr = if (e.end.isNotBlank() && e.end != e.start && e.end != "null") "${e.start} ~ ${e.end}" else e.start
                Text(dateStr + (if (e.memo.isNotBlank() && e.memo != "null") " · ${e.memo}" else ""), fontSize = 11.sp, color = tint)
            }
        }
    }
}
