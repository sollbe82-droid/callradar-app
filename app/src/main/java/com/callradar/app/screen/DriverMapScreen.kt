// ===== DriverMapScreen v1 (2026-07) — 내 운행 개인 히트맵 (카카오맵 SDK v2) =====
// 개인·무료·즉시 데이터: 내 운행 출발지 좌표를 격자 집계해 밀도(초록<주황<빨강) 마커로 표시.
// 라이프사이클(start/resume/pause/finish)을 정확히 지켜 크래시 방지(카카오 문서 주의사항).
package com.callradar.app.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.callradar.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

private const val MAP_SERVER = Config.SERVER_URL

@Composable
fun DriverMapScreen(userId: String, onBack: () -> Unit, embedded: Boolean = false, showTrack: Boolean = true) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mapRef by remember { mutableStateOf<com.kakao.vectormap.KakaoMap?>(null) }   // [궤적토글] 지도 준비되면 참조 저장 → showTrack 토글에 반응
    val accent = Color(0xFFF5A623)
    val muted = Color(0xFF9CA3AF)

    var pointCount by remember { mutableStateOf(0) }
    var mapFilter by remember { mutableStateOf(0) }   // [지도필터] 0=오늘 1=30k+ 2=50k+ (버튼 누를 때마다 순환)
    var status by remember { mutableStateOf("불러오는 중…") }
    var authFailed by remember { mutableStateOf(false) }

    val hasKey = BuildConfig.KAKAO_NATIVE_KEY.isNotBlank()

    Column(Modifier.fillMaxSize().background(AppTheme.bg)) {
        if (!embedded) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("‹ 더보기", color = accent, fontSize = 15.sp) }
                Spacer(Modifier.width(6.dp))
                Text("🗺️ 내 운행 지도", color = AppTheme.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }

        if (!hasKey) { SetupGuide(accent, muted); return }

        val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
        val mapView = remember { com.kakao.vectormap.MapView(ctx) }

        DisposableEffect(lifecycleOwner, mapView) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> try { mapView.resume() } catch (e: Exception) {}
                    Lifecycle.Event.ON_PAUSE -> try { mapView.pause() } catch (e: Exception) {}
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                try { mapView.finish() } catch (e: Exception) {}
            }
        }

        // [궤적토글] 지도 준비 후 showTrack에 따라 오늘 궤적 폴리라인 그림/지움 (레이더 🧭 버튼과 연동)
        LaunchedEffect(mapRef, showTrack) {
            val km = mapRef ?: return@LaunchedEffect
            if (showTrack) drawTodayTrack(km, ctx, scope) else clearTrack(km)
        }

        // [지도필터] 오늘/30k/50k 전환 시 히트맵 다시 그림 (지도 준비 후에도 1회)
        LaunchedEffect(mapRef, mapFilter) {
            val km = mapRef ?: return@LaunchedEffect
            loadMyHeatmap(km, userId, scope, mapFilter) { pts, _, msg -> pointCount = pts; status = msg }
        }

        // [#1] 지도 열릴 때 내 위치로 중심 (GPS 준비되면 1회)
        LaunchedEffect(mapRef) {
            val km = mapRef ?: return@LaunchedEffect
            kotlinx.coroutines.delay(600)
            val la = com.callradar.app.LocationTrackingService.currentLat
            val ln = com.callradar.app.LocationTrackingService.currentLng
            if (la != 0.0 || ln != 0.0) km.moveCamera(com.kakao.vectormap.camera.CameraUpdateFactory.newCenterPosition(com.kakao.vectormap.LatLng.from(la, ln), 14))
        }

        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    mapView.start(
                        object : com.kakao.vectormap.MapLifeCycleCallback() {
                            override fun onMapDestroy() {}
                            override fun onMapError(error: Exception?) { authFailed = true; status = "지도 인증 실패" }
                        },
                        object : com.kakao.vectormap.KakaoMapReadyCallback() {
                            override fun onMapReady(kakaoMap: com.kakao.vectormap.KakaoMap) {
                                mapRef = kakaoMap   // [궤적토글/지도필터] LaunchedEffect가 showTrack·mapFilter에 맞춰 그림
                            }
                            override fun getZoomLevel(): Int = 11
                            override fun getPosition(): com.kakao.vectormap.LatLng {
                                // [#1] 서울 하드코딩 대신 내 위치로 시작(없으면 서울 폴백)
                                val la = com.callradar.app.LocationTrackingService.currentLat
                                val ln = com.callradar.app.LocationTrackingService.currentLng
                                return if (la != 0.0 || ln != 0.0) com.kakao.vectormap.LatLng.from(la, ln)
                                       else com.kakao.vectormap.LatLng.from(37.5665, 126.9780)
                            }
                        }
                    )
                    mapView
                }
            )

            // [지도필터 버튼] 누를 때마다 오늘→30k→50k 순환. Radar FAB(우상단)와 안 겹치게 좌상단.
            if (!authFailed)
            Box(
                Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(Color(0xFF111827).copy(alpha = 0.9f), RoundedCornerShape(20.dp))
                    .clickable { mapFilter = (mapFilter + 1) % 3 }
                    .padding(horizontal = 13.dp, vertical = 7.dp)
            ) {
                Text(when (mapFilter) { 1 -> "💰 30k↑"; 2 -> "💰 50k↑"; else -> "📍 오늘" }, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            // [#1] 내 위치로 재중심 (우하단 · 네비앱처럼)
            if (!authFailed)
            Box(
                Modifier.align(Alignment.BottomEnd).padding(12.dp)
                    .background(Color(0xFF2563EB).copy(alpha = 0.92f), RoundedCornerShape(24.dp))
                    .clickable {
                        val la = com.callradar.app.LocationTrackingService.currentLat
                        val ln = com.callradar.app.LocationTrackingService.currentLng
                        if (la != 0.0 || ln != 0.0) mapRef?.moveCamera(com.kakao.vectormap.camera.CameraUpdateFactory.newCenterPosition(com.kakao.vectormap.LatLng.from(la, ln), 15))
                    }
                    .padding(horizontal = 13.dp, vertical = 9.dp)
            ) {
                Text("📍 내 위치", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            // [v44] 레이더 임베드에선 '내 운행 밀도' 범례를 숨겨 지도 뷰 가림 방지. 단 지도 인증 실패 경고는 유지.
            if (!embedded || authFailed)
            Card(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).fillMaxWidth(0.94f),
                colors = CardDefaults.cardColors(containerColor = AppTheme.card.copy(alpha = 0.92f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    if (authFailed) {
                        Text("지도 인증 실패", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("카카오 콘솔에 이 앱의 키 해시가 등록됐는지, 네이티브 앱 키가 맞는지 확인하세요.", color = muted, fontSize = 11.sp)
                    } else {
                        Text("내 운행 밀도 · 출발지 기준", color = AppTheme.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(status, color = muted, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Legend(Color(0xFF22C55E), "적음")
                            Legend(Color(0xFFF59E0B), "보통")
                            Legend(Color(0xFFEF4444), "많음")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(12.dp).background(c, RoundedCornerShape(6.dp)))
        Text(label, color = Color(0xFF9CA3AF), fontSize = 11.sp)
    }
}

@Composable
private fun SetupGuide(accent: Color, muted: Color) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = AppTheme.card), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🗺️ 지도 사용 준비", color = AppTheme.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("카카오 개발자 콘솔에서 아래 2가지만 하면 지도가 켜집니다.", color = muted, fontSize = 12.sp)
                Text("① 플랫폼 > Android > 키 해시 등록:", color = AppTheme.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("OWz233FduQ5xuAdzyBamK2vh//I=", color = accent, fontSize = 13.sp)
                Text("② 네이티브 앱 키를 local.properties에\nKAKAO_NATIVE_KEY=... 로 넣고 다시 빌드", color = AppTheme.text, fontSize = 13.sp)
                Text("등록 후 앱을 다시 설치하면 내 운행 밀도 지도가 나옵니다.", color = muted, fontSize = 11.sp)
            }
        }
    }
}

private fun loadMyHeatmap(
    kakaoMap: com.kakao.vectormap.KakaoMap,
    userId: String,
    scope: kotlinx.coroutines.CoroutineScope,
    filter: Int,   // 0=오늘 1=30k+ 2=50k+
    onDone: (Int, Int, String) -> Unit
) {
    scope.launch {
        try {
            val resp = withContext(Dispatchers.IO) {
                val conn = (URL("$MAP_SERVER/api/trips/$userId?limit=1000").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                conn.inputStream.bufferedReader().readText()
            }
            val arr = JSONArray(resp)
            val cells = HashMap<String, Int>()
            var pts = 0
            val kstDay = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul") }
            val todayStr = kstDay.format(java.util.Date())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val lat = o.optDouble("origin_lat", Double.NaN)
                val lng = o.optDouble("origin_lng", Double.NaN)
                if (lat.isNaN() || lng.isNaN() || lat == 0.0 || lng == 0.0) continue
                when (filter) {   // [지도필터] 오늘 / 30k+ / 50k+
                    0 -> { val d = parseUtcLoose(o.optString("started_at", "")) ?: continue; if (kstDay.format(d) != todayStr) continue }
                    1 -> { if (o.optInt("fare", 0) < 30000) continue }
                    2 -> { if (o.optInt("fare", 0) < 50000) continue }
                }
                pts++
                val key = "${(lat * 1000).roundToInt()},${(lng * 1000).roundToInt()}"
                cells[key] = (cells[key] ?: 0) + 1
            }
            withContext(Dispatchers.Main) {
                val manager = kakaoMap.labelManager
                val layer = manager?.layer
                try { layer?.removeAll() } catch (e: Exception) {}   // [필터전환] 기존 점 지우고 다시 그림
                if (layer != null && cells.isNotEmpty()) {
                    val density = kakaoMap.mapDpScale.coerceAtLeast(1f)
                    val styleLow = manager.addLabelStyles(com.kakao.vectormap.label.LabelStyles.from(com.kakao.vectormap.label.LabelStyle.from(circleBmp(0xFF22C55E.toInt(), 20, density))))
                    val styleMid = manager.addLabelStyles(com.kakao.vectormap.label.LabelStyles.from(com.kakao.vectormap.label.LabelStyle.from(circleBmp(0xFFF59E0B.toInt(), 26, density))))
                    val styleHigh = manager.addLabelStyles(com.kakao.vectormap.label.LabelStyles.from(com.kakao.vectormap.label.LabelStyle.from(circleBmp(0xFFEF4444.toInt(), 32, density))))
                    val styleTop = manager.addLabelStyles(com.kakao.vectormap.label.LabelStyles.from(com.kakao.vectormap.label.LabelStyle.from(circleBmp(0xFF000000.toInt(), 38, density))))  // [4단계] 흑색=최다
                    var hottest: com.kakao.vectormap.LatLng? = null; var hottestN = 0
                    for ((key, n) in cells) {
                        val parts = key.split(","); val lat = parts[0].toInt() / 1000.0; val lng = parts[1].toInt() / 1000.0
                        val style = if (n >= 10) styleTop else if (n >= 6) styleHigh else if (n >= 3) styleMid else styleLow
                        val pos = com.kakao.vectormap.LatLng.from(lat, lng)
                        layer.addLabel(com.kakao.vectormap.label.LabelOptions.from(pos).setStyles(style))
                        if (n > hottestN) { hottestN = n; hottest = pos }
                    }
                    // [#1] 핫존 클러스터로 카메라 이동 제거 — 내 위치 중심 유지(필터 누를 때마다 공항 등으로 튀던 문제)
                    if (hottest == null) { /* no-op */ }
                }
                val fname = when (filter) { 1 -> "30k↑ 장거리"; 2 -> "50k↑ 장거리"; else -> "오늘" }
                onDone(pts, cells.size, if (pts == 0) "$fname · 표시할 운행 없음" else "$fname · ${pts}건 · ${cells.size}곳")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onDone(0, 0, "불러오기 실패") }
        }
    }
}

// [지도필터] started_at(UTC 문자열) 유연 파싱
private fun parseUtcLoose(s: String): java.util.Date? {
    if (s.isBlank()) return null
    for (f in arrayOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss")) {
        try { val sdf = java.text.SimpleDateFormat(f, java.util.Locale.US); sdf.timeZone = java.util.TimeZone.getTimeZone("UTC"); return sdf.parse(s) } catch (e: Exception) {}
    }
    return null
}

// [궤적on지도] 오늘 영업일 시작(야간·일차 기사 dayStart 반영)
private fun trackDayStart(ctx: android.content.Context): Long {
    val prefs = ctx.getSharedPreferences("callradar_prefs", android.content.Context.MODE_PRIVATE)
    val h = prefs.getInt("day_start_hour", 0)
    val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"))
    if (c.get(java.util.Calendar.HOUR_OF_DAY) < h) c.add(java.util.Calendar.DAY_OF_YEAR, -1)
    c.set(java.util.Calendar.HOUR_OF_DAY, h); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

// [궤적on지도] 오늘 근무 궤적(로컬 GPS 브레드크럼)을 레이더 지도 위에 폴리라인으로.
//  실차=파랑 실선, 공차=회색. 10분 이상 끊긴 구간·실차/공차 전환은 세그먼트 분리. 시작(초록)·현재(주황) 마커.
private fun drawTodayTrack(
    kakaoMap: com.kakao.vectormap.KakaoMap,
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope
) {
    scope.launch {
        try {
            val db = com.callradar.app.LocalTrackDatabase.getInstance(ctx)
            val since = trackDayStart(ctx)
            val pts = withContext(Dispatchers.IO) { db.pointsSince(since) }
            if (pts.size < 2) return@launch
            withContext(Dispatchers.Main) {
                val mgr = kakaoMap.routeLineManager ?: return@withContext
                val layer = mgr.layer ?: return@withContext
                try { layer.removeAll() } catch (e: Exception) {}   // [궤적토글] 재그리기 전 기존 궤적 제거(중복 방지)
                val dp = kakaoMap.mapDpScale.coerceAtLeast(1f)
                val loadedStyles = com.kakao.vectormap.route.RouteLineStyles.from(com.kakao.vectormap.route.RouteLineStyle.from(6f * dp, 0xFF3B82F6.toInt()))
                val emptyStyles = com.kakao.vectormap.route.RouteLineStyles.from(com.kakao.vectormap.route.RouteLineStyle.from(4f * dp, 0xFF9CA3AF.toInt()))
                val segments = ArrayList<com.kakao.vectormap.route.RouteLineSegment>()
                var run = ArrayList<com.kakao.vectormap.LatLng>()
                var runLoaded = pts[0].loaded
                run.add(com.kakao.vectormap.LatLng.from(pts[0].lat, pts[0].lng))
                var minLat = pts[0].lat; var maxLat = pts[0].lat; var minLng = pts[0].lng; var maxLng = pts[0].lng
                fun flush() { if (run.size >= 2) segments.add(com.kakao.vectormap.route.RouteLineSegment.from(run, if (runLoaded) loadedStyles else emptyStyles)) }
                for (i in 1 until pts.size) {
                    val p = pts[i]; val prev = pts[i - 1]
                    minLat = minOf(minLat, p.lat); maxLat = maxOf(maxLat, p.lat); minLng = minOf(minLng, p.lng); maxLng = maxOf(maxLng, p.lng)
                    if (p.loaded != runLoaded || (p.ts - prev.ts) > 10 * 60_000L) {
                        flush(); run = ArrayList(); run.add(com.kakao.vectormap.LatLng.from(prev.lat, prev.lng)); runLoaded = p.loaded
                    }
                    run.add(com.kakao.vectormap.LatLng.from(p.lat, p.lng))
                }
                flush()
                if (segments.isEmpty()) return@withContext
                try { layer.addRouteLine(com.kakao.vectormap.route.RouteLineOptions.from(segments)) } catch (e: Exception) {}
                // 카메라를 오늘 궤적으로 이동(히트맵 카메라보다 뒤에 실행되게 살짝 지연 → 오늘 경로 우선)
                kotlinx.coroutines.delay(600)
                try {
                    val cLat = (minLat + maxLat) / 2; val cLng = (minLng + maxLng) / 2
                    val span = maxOf(maxLat - minLat, maxLng - minLng)
                    val zoom = when { span > 0.3 -> 10; span > 0.15 -> 11; span > 0.07 -> 12; span > 0.03 -> 13; span > 0.015 -> 14; else -> 15 }
                    kakaoMap.moveCamera(com.kakao.vectormap.camera.CameraUpdateFactory.newCenterPosition(com.kakao.vectormap.LatLng.from(cLat, cLng), zoom))
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }
}

// [궤적토글] 지도에서 오늘 궤적 폴리라인 제거(🧭 끄기)
private fun clearTrack(kakaoMap: com.kakao.vectormap.KakaoMap) {
    try { kakaoMap.routeLineManager?.layer?.removeAll() } catch (e: Exception) {}
}

private fun circleBmp(color: Int, sizeDp: Int, density: Float): Bitmap {
    val px = (sizeDp * density).roundToInt().coerceAtLeast(10)
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = (color and 0x00FFFFFF) or 0xCC000000.toInt()
    c.drawCircle(px / 2f, px / 2f, px / 2f - density, p)
    p.style = Paint.Style.STROKE; p.strokeWidth = density.coerceAtLeast(1f) * 1.5f; p.color = 0xFFFFFFFF.toInt()
    c.drawCircle(px / 2f, px / 2f, px / 2f - density, p)
    return bmp
}
