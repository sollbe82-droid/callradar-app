package com.callradar.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.callradar.app.screen.AppTheme
import com.callradar.app.screen.Config
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.floor

/**
 * [v19] GPS 요금 미터기 (참고용 추정) — 카풀·길빵·예약처럼 미터기 없는 경우 요금 추정.
 *  법적 미터기 아님(GPS 5~10% 오차). 요금표는 설정/서버에서 갱신. docs/30.
 */
class MeterActivity : ComponentActivity() {
    companion object { fun start(c: Context) { c.startActivity(Intent(c, MeterActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MeterScreen(getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getString("user_id", "") ?: "") { finish() } }
    }
}

// 요금표 (지역/차종별). 기본=서울 중형 근사값(2024). 실제 요금은 지역·시기별 상이 → 설정에서 조정.
data class MeterRate(
    val base: Int = 4800, val baseM: Int = 1600,
    val distUnitFare: Int = 100, val distUnitM: Int = 131,
    val timeUnitFare: Int = 100, val timeUnitSec: Int = 30,
    val slowKmh: Int = 15,
    val nightPct: Int = 20, val nightStart: Int = 22, val nightEnd: Int = 4,
    val intercityPct: Int = 20
)

// [v19] 지역별 요금표(중형 근사·2024). ⚠️ 지역·시기별로 다르고 자주 바뀜 → 실제와 차이 가능(참고용). 추후 서버 갱신.
val meterRegions: List<Pair<String, MeterRate>> = listOf(
    "서울" to MeterRate(base = 4800, baseM = 1600, distUnitFare = 100, distUnitM = 131, timeUnitFare = 100, timeUnitSec = 30, intercityPct = 20),
    "경기" to MeterRate(base = 4800, baseM = 2000, distUnitFare = 100, distUnitM = 132, timeUnitFare = 100, timeUnitSec = 31, intercityPct = 20),
    "인천" to MeterRate(base = 4000, baseM = 2000, distUnitFare = 100, distUnitM = 137, timeUnitFare = 100, timeUnitSec = 33, intercityPct = 20),
    "부산" to MeterRate(base = 4800, baseM = 2000, distUnitFare = 100, distUnitM = 133, timeUnitFare = 100, timeUnitSec = 34, intercityPct = 20),
    "대구" to MeterRate(base = 4000, baseM = 2000, distUnitFare = 100, distUnitM = 138, timeUnitFare = 100, timeUnitSec = 34, intercityPct = 20),
    "대전" to MeterRate(base = 4300, baseM = 2100, distUnitFare = 100, distUnitM = 131, timeUnitFare = 100, timeUnitSec = 30, intercityPct = 20),
    "광주" to MeterRate(base = 4300, baseM = 2000, distUnitFare = 100, distUnitM = 137, timeUnitFare = 100, timeUnitSec = 33, intercityPct = 20),
    "울산" to MeterRate(base = 4000, baseM = 2000, distUnitFare = 100, distUnitM = 139, timeUnitFare = 100, timeUnitSec = 35, intercityPct = 20),
    "제주" to MeterRate(base = 4300, baseM = 2000, distUnitFare = 100, distUnitM = 141, timeUnitFare = 100, timeUnitSec = 35, intercityPct = 20),
)
fun rateOfRegion(name: String): MeterRate = meterRegions.find { it.first == name }?.second ?: meterRegions[0].second

// 요금 항목별 내역 (미터기처럼 정밀 표시용)
data class MeterResult(val base: Int, val distFare: Int, val timeFare: Int, val nightAdd: Int, val intercityAdd: Int, val nightPct: Int, val total: Int)

// 순수 함수 — 단위테스트 가능. distance=총 이동거리(m), slowSec=저속(정차·서행) 누적초.
//  심야할증은 시간대별 정밀(nightPctAt): 서울 예) 22~23시 20%, 23~04시 40%.
fun calcMeterBreakdown(distanceM: Double, slowSec: Long, r: MeterRate, nightPct: Int, intercity: Boolean): MeterResult {
    val base = r.base
    val extra = (distanceM - r.baseM).coerceAtLeast(0.0)
    val distFare = (floor(extra / r.distUnitM) * r.distUnitFare).toInt()
    val timeFare = (floor(slowSec.toDouble() / r.timeUnitSec) * r.timeUnitFare).toInt()
    val subtotal = base + distFare + timeFare
    val nightAdd = if (nightPct > 0) (subtotal * nightPct / 100.0).toInt() else 0
    val intercityAdd = if (intercity) (subtotal * r.intercityPct / 100.0).toInt() else 0
    val total = (Math.round((subtotal + nightAdd + intercityAdd) / 100.0) * 100).toInt()
    return MeterResult(base, distFare, timeFare, nightAdd, intercityAdd, nightPct, total)
}

fun calcMeterFare(distanceM: Double, slowSec: Long, r: MeterRate, night: Boolean, intercity: Boolean): Int =
    calcMeterBreakdown(distanceM, slowSec, r, if (night) r.nightPct else 0, intercity).total

// 현재 시각 심야할증률(%) — 시간대별 정밀. 서울 관행: 22~23시 20%, 23~04시 40%, 그 외 0.
fun nightPctNow(r: MeterRate): Int {
    val h = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")).get(Calendar.HOUR_OF_DAY)
    return when {
        h == 22 -> 20            // 22~23시
        (h == 23 || h in 0 until r.nightEnd) -> 40   // 23~04시 (깊은 심야)
        else -> 0
    }
}

@Composable
private fun MeterScreen(userId: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val accent = Color(0xFFF59E0B); val green = Color(0xFF10B981); val muted = Color(0xFF9CA3AF)
    val scope = rememberCoroutineScope()
    val prefs = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
    var region by remember { mutableStateOf(prefs.getString("meter_region", "서울") ?: "서울") }
    val rate = rateOfRegion(region)

    var running by remember { mutableStateOf(false) }
    var distanceM by remember { mutableStateOf(0.0) }
    var slowSec by remember { mutableStateOf(0L) }
    var speedKmh by remember { mutableStateOf(0f) }
    var elapsedSec by remember { mutableStateOf(0L) }
    var intercity by remember { mutableStateOf(false) }
    var nightPct by remember { mutableStateOf(nightPctNow(rate)) }
    var lastLoc by remember { mutableStateOf<Location?>(null) }
    var startMs by remember { mutableStateOf(0L) }
    var lastTickMs by remember { mutableStateOf(0L) }
    var saved by remember { mutableStateOf(false) }
    var hasPerm by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }

    val fused = remember { LocationServices.getFusedLocationProviderClient(ctx) }
    val result = calcMeterBreakdown(distanceM, slowSec, rate, nightPct, intercity)
    val fare = result.total

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPerm = it }

    val callback = remember {
        object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                val loc = res.lastLocation ?: return
                val now = System.currentTimeMillis()
                speedKmh = (loc.speed * 3.6f)
                val prev = lastLoc
                if (prev != null && loc.accuracy <= 30f) {
                    val d = FloatArray(1); Location.distanceBetween(prev.latitude, prev.longitude, loc.latitude, loc.longitude, d)
                    val seg = d[0].toDouble()
                    // GPS 튐 필터: 2m 미만은 정지로 간주, 비현실적 점프(>300m/틱) 무시
                    if (seg in 2.0..300.0 && speedKmh >= rate.slowKmh) distanceM += seg
                }
                // 저속(서행·정차) 시간요금 누적
                if (lastTickMs > 0 && speedKmh < rate.slowKmh) slowSec += (now - lastTickMs) / 1000
                lastTickMs = now
                if (startMs > 0) elapsedSec = (now - startMs) / 1000
                lastLoc = loc
            }
        }
    }

    fun startMeter() {
        if (!hasPerm) { permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION); return }
        distanceM = 0.0; slowSec = 0; elapsedSec = 0; lastLoc = null; saved = false
        startMs = System.currentTimeMillis(); lastTickMs = 0L; nightPct = nightPctNow(rate)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L).setMinUpdateIntervalMillis(1000L).setGranularity(Granularity.GRANULARITY_FINE).build()
        try { fused.requestLocationUpdates(req, callback, Looper.getMainLooper()); running = true } catch (e: SecurityException) { hasPerm = false }
    }
    fun stopMeter() { try { fused.removeLocationUpdates(callback) } catch (e: Exception) {}; running = false }
    DisposableEffect(Unit) { onDispose { try { fused.removeLocationUpdates(callback) } catch (e: Exception) {} } }

    fun saveTrip() {
        if (fare <= 0) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); sdf.timeZone = TimeZone.getTimeZone("UTC")
                    val json = JSONObject().apply {
                        put("user_id", userId); put("platform", "미터기"); put("originName", ""); put("destName", "미터기 운행"); put("fare", fare)
                        put("payment_type", "cash"); put("source", "manual"); put("started_at", sdf.format(Date()))
                    }
                    val conn = (URL("${Config.SERVER_URL}/api/trips/manual").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true }
                    conn.outputStream.write(json.toString().toByteArray(Charsets.UTF_8)); conn.responseCode
                }
                saved = true
            } catch (e: Exception) {}
        }
    }

    Column(Modifier.fillMaxSize().background(AppTheme.bg).padding(16.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🚕 요금 미터기", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.weight(1f))
            TextButton(onClick = { stopMeter(); onClose() }) { Text("닫기", color = muted) }
        }
        // 추정·배터리 배지 (상시)
        Box(Modifier.fillMaxWidth().background(Color(0xFF7F1D1D), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text("⚠️ 참고용 추정(GPS) · 법적 미터기 아님 · 재미로 쓰세요 · 켜두면 배터리 소모가 큽니다", fontSize = 11.sp, color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        // 지역 요금표 선택
        Text("지역 요금표", fontSize = 12.sp, color = muted)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            meterRegions.forEach { (name, _) ->
                FilterChip(selected = region == name, onClick = { if (!running) { region = name; prefs.edit().putString("meter_region", name).apply() } }, label = { Text(name, fontSize = 12.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
            }
        }
        Spacer(Modifier.height(4.dp))
        if (!hasPerm) Text("위치 권한이 필요해요. 시작을 누르면 권한을 요청합니다.", fontSize = 12.sp, color = Color(0xFFEF4444))

        // 요금 대형 표시
        Card(Modifier.fillMaxWidth().padding(vertical = 12.dp), colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("현재 요금(추정)", fontSize = 13.sp, color = muted)
                Text("₩${String.format("%,d", fare)}", fontSize = 52.sp, fontWeight = FontWeight.Bold, color = green)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (nightPct > 0) Box(Modifier.background(Color(0xFF1E3A5F), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) { Text("🌙 심야 +${nightPct}%", fontSize = 11.sp, color = Color(0xFF93C5FD)) }
                    if (intercity) Box(Modifier.background(Color(0xFF3F2D1E), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) { Text("🚗 시계외 +${rate.intercityPct}%", fontSize = 11.sp, color = accent) }
                }
                // [v19] 항목별 내역 (정밀)
                Spacer(Modifier.height(12.dp)); HorizontalDivider(color = AppTheme.surface2); Spacer(Modifier.height(8.dp))
                @Composable fun itemRow(label: String, value: Int, plus: Boolean = false) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, fontSize = 12.sp, color = muted)
                        Text("${if (plus && value > 0) "+" else ""}${String.format("%,d", value)}원", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppTheme.text)
                    }
                }
                itemRow("기본요금", result.base)
                itemRow("거리요금", result.distFare)
                if (result.timeFare > 0) itemRow("시간요금(서행)", result.timeFare)
                if (result.nightAdd > 0) itemRow("심야할증 ${nightPct}%", result.nightAdd, true)
                if (result.intercityAdd > 0) itemRow("시계외할증 ${rate.intercityPct}%", result.intercityAdd, true)
            }
        }
        // 거리·시간·속도
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("거리", fontSize = 11.sp, color = muted); Text(String.format("%.2f km", distanceM / 1000.0), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTheme.text) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("시간", fontSize = 11.sp, color = muted); Text(String.format("%d:%02d", elapsedSec / 60, elapsedSec % 60), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTheme.text) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("속도", fontSize = 11.sp, color = muted); Text("${speedKmh.toInt()} km/h", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (speedKmh >= rate.slowKmh) green else accent) }
        }
        Spacer(Modifier.height(10.dp))
        // 시내 / 시외 토글 (시외=할증)
        Text("운행 구간", fontSize = 12.sp, color = muted)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !intercity, onClick = { intercity = false }, label = { Text("🏙 시내(할증 없음)") }, modifier = Modifier.weight(1f), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = green, selectedLabelColor = Color.White, containerColor = AppTheme.surface2, labelColor = muted))
            FilterChip(selected = intercity, onClick = { intercity = true }, label = { Text("🚗 시외(+${rate.intercityPct}%)") }, modifier = Modifier.weight(1f), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Color.Black, containerColor = AppTheme.surface2, labelColor = muted))
        }

        Spacer(Modifier.weight(1f))
        // 시작/정지
        if (!running) {
            Button(onClick = { startMeter() }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = green), shape = RoundedCornerShape(14.dp)) {
                Text(if (fare > 0) "다시 시작" else "🟢 미터기 시작", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            if (fare > 0 && !saved) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = { saveTrip() }, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(14.dp)) {
                    Text("₩${String.format("%,d", fare)} 기록에 저장", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
            if (saved) { Spacer(Modifier.height(8.dp)); Text("✅ 기록에 저장했어요 (기록 탭에서 확인)", fontSize = 13.sp, color = green, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        } else {
            Button(onClick = { stopMeter() }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)), shape = RoundedCornerShape(14.dp)) {
                Text("🔴 정지 (하차)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
        // [v19] 플로팅 백그라운드 — 내비 위에 요금 띄우기
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            prefs.edit().putBoolean("meter_intercity", intercity).putString("meter_region", region).apply()
            if (!android.provider.Settings.canDrawOverlays(ctx)) {
                try { ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {}
            } else if (!hasPerm) { permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
            else { com.callradar.app.MeterFloatingService.start(ctx); onClose() }
        }, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(12.dp)) {
            Text("📌 플로팅으로 백그라운드 실행 (내비 위에 요금)", color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Text("요금표는 지역별 근사값이에요. 지역·시기별로 달라 실제와 차이가 있을 수 있어요. 플로팅은 탭=저장·길게=취소.", fontSize = 10.sp, color = muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Spacer(Modifier.height(8.dp))
    }
}
