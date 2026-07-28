// ===== DriverMapScreen v1 (2026-07) — 내 운행 개인 히트맵 (카카오맵 SDK v2) =====
// 개인·무료·즉시 데이터: 내 운행 출발지 좌표를 격자 집계해 밀도(초록<주황<빨강) 마커로 표시.
// 라이프사이클(start/resume/pause/finish)을 정확히 지켜 크래시 방지(카카오 문서 주의사항).
package com.callradar.app.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
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
fun DriverMapScreen(userId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = Color(0xFFF5A623)
    val muted = Color(0xFF9CA3AF)

    var pointCount by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("불러오는 중…") }
    var authFailed by remember { mutableStateOf(false) }

    val hasKey = BuildConfig.KAKAO_NATIVE_KEY.isNotBlank()

    Column(Modifier.fillMaxSize().background(AppTheme.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("‹ 더보기", color = accent, fontSize = 15.sp) }
            Spacer(Modifier.width(6.dp))
            Text("🗺️ 내 운행 지도", color = AppTheme.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
                                loadMyHeatmap(kakaoMap, userId, scope) { pts, cells, msg ->
                                    pointCount = pts; status = msg
                                }
                            }
                            override fun getZoomLevel(): Int = 11
                            override fun getPosition(): com.kakao.vectormap.LatLng =
                                com.kakao.vectormap.LatLng.from(37.5665, 126.9780)
                        }
                    )
                    mapView
                }
            )

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
    onDone: (Int, Int, String) -> Unit
) {
    scope.launch {
        try {
            val resp = withContext(Dispatchers.IO) {
                val conn = (URL("$MAP_SERVER/api/trips/$userId?limit=2000").openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                conn.inputStream.bufferedReader().readText()
            }
            val arr = JSONArray(resp)
            val cells = HashMap<String, Int>()
            var pts = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val lat = o.optDouble("origin_lat", Double.NaN)
                val lng = o.optDouble("origin_lng", Double.NaN)
                if (lat.isNaN() || lng.isNaN() || lat == 0.0 || lng == 0.0) continue
                pts++
                val key = "${(lat * 1000).roundToInt()},${(lng * 1000).roundToInt()}"
                cells[key] = (cells[key] ?: 0) + 1
            }
            withContext(Dispatchers.Main) {
                val manager = kakaoMap.labelManager
                val layer = manager?.layer
                if (layer != null && cells.isNotEmpty()) {
                    val density = kakaoMap.mapDpScale.coerceAtLeast(1f)
                    val styleLow = manager.addLabelStyles(com.kakao.vectormap.label.LabelStyles.from(com.kakao.vectormap.label.LabelStyle.from(circleBmp(0xFF22C55E.toInt(), 20, density))))
                    val styleMid = manager.addLabelStyles(com.kakao.vectormap.label.LabelStyles.from(com.kakao.vectormap.label.LabelStyle.from(circleBmp(0xFFF59E0B.toInt(), 26, density))))
                    val styleHigh = manager.addLabelStyles(com.kakao.vectormap.label.LabelStyles.from(com.kakao.vectormap.label.LabelStyle.from(circleBmp(0xFFEF4444.toInt(), 34, density))))
                    var hottest: com.kakao.vectormap.LatLng? = null; var hottestN = 0
                    for ((key, n) in cells) {
                        val parts = key.split(","); val lat = parts[0].toInt() / 1000.0; val lng = parts[1].toInt() / 1000.0
                        val style = if (n >= 8) styleHigh else if (n >= 3) styleMid else styleLow
                        val pos = com.kakao.vectormap.LatLng.from(lat, lng)
                        layer.addLabel(com.kakao.vectormap.label.LabelOptions.from(pos).setStyles(style))
                        if (n > hottestN) { hottestN = n; hottest = pos }
                    }
                    if (hottest != null) kakaoMap.moveCamera(com.kakao.vectormap.camera.CameraUpdateFactory.newCenterPosition(hottest, 12))
                }
                onDone(pts, cells.size, if (pts == 0) "좌표가 있는 운행이 아직 없어요 (기록이 쌓이면 표시)" else "내 운행 ${pts}건 · ${cells.size}곳")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onDone(0, 0, "불러오기 실패") }
        }
    }
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
