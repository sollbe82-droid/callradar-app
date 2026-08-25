package com.callradar.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.callradar.app.ui.theme.CallRadarTheme
import com.callradar.app.screen.AppTheme
import java.io.File
import java.util.*
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * [v32/33] 오늘 운행 궤적 — 실차(파랑 실선)/공차(회색 점선) + 시작·현재 마커.
 *  실차거리·공차거리·실차율·시간 요약 + 지도 이미지(PNG) 공유.
 *  궤적은 근무모드(WorkSessionService)에서만 기록된 로컬 데이터. 지도는 GPS 좌표를 화면에 투영해 그림.
 */
class TrackActivity : ComponentActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, TrackActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }

    private var bitmap by mutableStateOf<Bitmap?>(null)
    private var stats by mutableStateOf<LocalTrackDatabase.DayStats?>(null)
    private var loading by mutableStateOf(true)
    private var guide by mutableStateOf("")   // [궤적버그] 빈 상태 사유 안내(권한/정밀/출근/오늘점없음 구분)
    private var dark = true   // [테마버그] 화면 테마(라이트/다크) — 캔버스 배경/범례 색에 반영
    private var dayOffset by mutableStateOf(0)       // [과거날짜] 0=오늘, 음수=지난 날(최대 -30)
    private var dateLabel by mutableStateOf("오늘")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // [테마버그] 앱 전역 테마 동기화 — 라이트 모드인데 궤적 화면만 다크로 뜨던 문제 수정
        dark = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getBoolean("dark_mode", true)
        AppTheme.isDark = dark
        load()
        setContent { CallRadarTheme { Screen() } }
    }

    private fun dayStart(): Long {
        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val h = prefs.getInt("day_start_hour", 0)
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))
        if (c.get(Calendar.HOUR_OF_DAY) < h) c.add(Calendar.DAY_OF_YEAR, -1)
        c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun load() {
        loading = true
        Thread {
            val db = LocalTrackDatabase.getInstance(this)
            // [과거날짜] 오늘 영업일 시작 기준으로 dayOffset일 전 하루 창을 조회
            val today = dayStart()
            val since = today + dayOffset * 86400_000L
            val until = since + 86400_000L
            val label = if (dayOffset == 0) "오늘" else {
                val f = java.text.SimpleDateFormat("M/d (E)", Locale.KOREA); f.timeZone = TimeZone.getTimeZone("Asia/Seoul"); f.format(java.util.Date(since))
            }
            var pts = db.pointsBetween(since, until)
            // [v44] 로컬에 궤적이 없으면(기기·스토어 변경 등) 서버 백업에서 복원
            if (pts.size < 2) {
                val srv = com.callradar.app.TrackSync.fetchRange(this, since, until)
                if (srv.size >= 2) pts = srv
            }
            val st = db.statsOf(pts)
            val bmp = renderTrack(pts)
            runOnUiThread {
                bitmap = bmp; stats = st; dateLabel = label
                guide = if (st.points < 2) (if (dayOffset == 0) emptyGuide() else "이 날은 기록된 궤적이 없어요.") else ""
                loading = false
            }
        }.start()
    }

    // [궤적버그] 궤적이 비었을 때 '왜 안 되는지' 사유별 안내 — 미작동 제보의 대부분이 아래 4가지.
    private fun emptyGuide(): String {
        val p = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val workActive = p.getLong("work_start", 0L) > 0L
        return when {
            !fine && !coarse -> "위치 권한이 꺼져 있어요. 설정 > 앱 권한 > 위치를 허용하면 궤적이 기록돼요."
            !fine && coarse -> "'대략적 위치'만 허용돼 있어요. 설정에서 '정확한 위치(정밀)'를 켜면 궤적이 정확히 남아요."
            !workActive -> "아직 근무(출근) 중이 아니에요. 홈에서 '출근'을 누르면 그때부터 이동 궤적이 기록돼요."
            else -> "오늘 기록된 궤적이 아직 없어요. 근무 중 이동하면 몇 초 간격으로 쌓여요."
        }
    }

    /** GPS 점들을 정사각 비트맵에 투영해 궤적을 그림. 위경도 거리 왜곡은 cos(lat)로 보정. */
    private fun renderTrack(pts: List<LocalTrackDatabase.Pt>): Bitmap {
        val size = 1080
        val pad = 90f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        // [테마버그] 캔버스 배경도 화면 테마 반영 — 라이트 모드에서 어두운 박스로 보이던 문제 수정
        cv.drawColor(android.graphics.Color.parseColor(if (dark) "#0A0E1A" else "#F2F4F7"))
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#F59E0B"); textSize = 40f; isFakeBoldText = true }
        cv.drawText("콜레이더 · 오늘 운행 궤적", pad, 60f, title)
        if (pts.size < 2) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor(if (dark) "#6B7280" else "#64748B"); textSize = 34f }
            cv.drawText("아직 궤적이 없어요. 근무를 시작하면 기록돼요.", pad, size / 2f, p)
            return bmp
        }
        // [v41] 진짜 지도 배경(OpenStreetMap 타일) 위에 궤적 — 실패(네트워크/타일 없음) 시 아래 단색 배경으로 폴백
        try { renderTrackOnMap(pts)?.let { return it } } catch (e: Exception) {}
        var minLat = pts[0].lat; var maxLat = pts[0].lat; var minLng = pts[0].lng; var maxLng = pts[0].lng
        for (p in pts) { minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat); minLng = min(minLng, p.lng); maxLng = max(maxLng, p.lng) }
        // [과확대] 폴백(단색) 렌더도 최소 ~1km 창 확보
        if (maxLat - minLat < 0.009) { val c = (minLat + maxLat) / 2; minLat = c - 0.0045; maxLat = c + 0.0045 }
        if (maxLng - minLng < 0.009) { val c = (minLng + maxLng) / 2; minLng = c - 0.0045; maxLng = c + 0.0045 }
        val midLat = (minLat + maxLat) / 2.0
        val latRange = max(1e-5, maxLat - minLat)
        val lngRange = max(1e-5, (maxLng - minLng) * cos(Math.toRadians(midLat)))
        val span = max(latRange, lngRange)
        val drawW = size - pad * 2
        fun sx(lng: Double): Float { val nx = ((lng - minLng) * cos(Math.toRadians(midLat))) / span; return pad + (nx.toFloat()) * drawW + (drawW - (lngRange / span).toFloat() * drawW) / 2f }
        fun sy(lat: Double): Float { val ny = (lat - minLat) / span; return (size - pad) - (ny.toFloat()) * drawW - (drawW - (latRange / span).toFloat() * drawW) / 2f }

        // [v41] 실차=붉은색 실선(요청), 공차=회색 점선(현재색 유지)
        val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#EF4444"); strokeWidth = 9f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#6B7280"); strokeWidth = 6f; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(14f, 14f), 0f) }
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            if (b.ts - a.ts > 10 * 60_000L) continue
            val path = Path().apply { moveTo(sx(a.lng), sy(a.lat)); lineTo(sx(b.lng), sy(b.lat)) }
            cv.drawPath(path, if (b.loaded) solid else dashed)
        }
        // [v41] 승차 출발(공차→실차)·하차 도착(실차→공차) 지점을 빨간 포인트로 표시(요청)
        val rideDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#EF4444") }
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            if (b.loaded && !a.loaded) cv.drawCircle(sx(b.lng), sy(b.lat), 14f, rideDot)   // 승차(출발)
            if (!b.loaded && a.loaded) cv.drawCircle(sx(a.lng), sy(a.lat), 14f, rideDot)   // 하차(도착)
        }
        // 하루 시작(초록)·현재(주황) 마커
        val startP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#10B981") }
        val nowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#F59E0B") }
        cv.drawCircle(sx(pts.first().lng), sy(pts.first().lat), 16f, startP)
        cv.drawCircle(sx(pts.last().lng), sy(pts.last().lat), 18f, nowP)
        val legend = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor(if (dark) "#9CA3AF" else "#64748B"); textSize = 26f }
        cv.drawText("● 시작   ● 현재   ● 승·하차   ── 실차   ┄ 공차", pad, size - 30f, legend)
        return bmp
    }

    /** [v41] OpenStreetMap 래스터 타일을 배경으로 궤적을 그림(공유 이미지에 진짜 지도).
     *  카카오맵 SDK엔 스냅샷 API가 없어 타일 방식 사용. 네트워크/타일 실패 시 null → renderTrack이 단색 배경으로 폴백.
     *  ⚠️ 백그라운드 스레드(load())에서만 호출됨(네트워크). */
    private fun renderTrackOnMap(pts: List<LocalTrackDatabase.Pt>): Bitmap? {
        var minLat = pts[0].lat; var maxLat = pts[0].lat; var minLng = pts[0].lng; var maxLng = pts[0].lng
        for (p in pts) { minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat); minLng = min(minLng, p.lng); maxLng = max(maxLng, p.lng) }
        // [과확대] 궤적 범위가 좁아도(정차·짧은 이동) 최소 ~1km 창을 확보 → 동네 골목까지 확대되던 문제 수정
        val minSpanDeg = 0.009  // 위도 기준 약 1km
        if (maxLat - minLat < minSpanDeg) { val c = (minLat + maxLat) / 2; minLat = c - minSpanDeg / 2; maxLat = c + minSpanDeg / 2 }
        if (maxLng - minLng < minSpanDeg) { val c = (minLng + maxLng) / 2; minLng = c - minSpanDeg / 2; maxLng = c + minSpanDeg / 2 }
        // 웹메르카토르 픽셀 좌표
        fun pxX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * 256.0 * (1 shl z)
        fun pxY(lat: Double, z: Int): Double { val s = Math.sin(Math.toRadians(lat)).coerceIn(-0.9999, 0.9999); return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * 256.0 * (1 shl z) }

        // ── [v93 잘림수정] 캔버스를 먼저 정하고 궤적을 그 안에 맞춘다(letterbox fit).
        //
        // 예전 방식은 반대였다. 궤적 bbox에 70px 패딩만 두고 그 크기를 그대로 출력 크기로 썼다.
        // 그래서 세 가지가 한꺼번에 터졌다:
        //   ① 제목 밴드(58px)·범례 밴드(46px)가 패딩(70px) 위에 그냥 덮여, 최북단 마커가 밴드에 파묻혔다.
        //   ② 서초→수원처럼 남북으로 긴 날은 폭이 좁게 나와 "콜레이더 · 운행 궤적" 제목과
        //      범례 줄이 이미지 밖으로 잘려나갔다.
        //   ③ coerceIn(256,1400)이 폭·높이만 자르고 원점(x0,y0)은 안 옮겨서 우측·하단이 통째로 크롭됐다.
        //
        // 이제는 폭을 1080으로 고정하고, 밴드가 차지할 자리를 뺀 '유효 지도 영역'을 먼저 잡은 뒤
        // 그 안에 bbox가 들어가는 줌을 고르고 중앙 정렬한다. 밴드가 궤적을 덮을 일이 없다.
        val outW = 1080
        val headerH = 76f      // 상단 제목 밴드
        val footerH = 60f      // 하단 범례 + OSM 저작자 표기
        val mapPad = 30.0      // 궤적과 유효영역 경계 사이 숨 쉴 틈
        val innerW = outW - mapPad * 2

        // 종횡비는 줌과 무관하므로 아무 줌에서나 계산해도 된다(z=14 기준).
        val refSpanX = (pxX(maxLng, 14) - pxX(minLng, 14)).coerceAtLeast(1.0)
        val refSpanY = (pxY(minLat, 14) - pxY(maxLat, 14)).coerceAtLeast(1.0)
        // 너무 납작하거나 너무 길쭉한 이미지는 공유했을 때 보기 나쁘다 → 0.62~1.45 사이로 가둔다.
        val innerH = (innerW * (refSpanY / refSpanX)).coerceIn(outW * 0.62, outW * 1.45)
        val outH = Math.ceil(innerH + mapPad * 2 + headerH + footerH).toInt()

        // 유효 영역(innerW × innerH)에 bbox가 들어가는 가장 상세한 줌
        var z = 16   // [과확대] 최대 줌 18→16 (도로·동네 맥락이 보이는 수준으로 제한)
        while (z > 3) {
            val spanX = pxX(maxLng, z) - pxX(minLng, z)
            val spanY = pxY(minLat, z) - pxY(maxLat, z)
            if (spanX <= innerW && spanY <= innerH) break
            z--
        }
        // bbox 중심을 유효 영역 중심에 맞춘다 → 상하좌우 어디로도 안 잘린다.
        val bboxCx = (pxX(minLng, z) + pxX(maxLng, z)) / 2.0
        val bboxCy = (pxY(minLat, z) + pxY(maxLat, z)) / 2.0
        val x0 = bboxCx - outW / 2.0
        val y0 = bboxCy - (headerH + mapPad + innerH / 2.0)

        val tx0 = Math.floor(x0 / 256.0).toInt(); val tx1 = Math.floor((x0 + outW) / 256.0).toInt()
        val ty0 = Math.floor(y0 / 256.0).toInt(); val ty1 = Math.floor((y0 + outH) / 256.0).toInt()
        val tileCount = (tx1 - tx0 + 1) * (ty1 - ty0 + 1)
        // 1080폭 캔버스는 가로 최대 5~6장, 세로 최대 8장 → 상한을 36에서 90으로 올린다.
        //  (예전 36은 새 캔버스에선 매번 걸려 지도 없는 단색 폴백으로 떨어진다)
        if (tileCount <= 0 || tileCount > 90) return null
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(android.graphics.Color.parseColor("#E8ECEF"))
        var loaded = 0
        val maxTile = (1 shl z) - 1
        for (tx in tx0..tx1) for (ty in ty0..ty1) {
            if (tx < 0 || ty < 0 || tx > maxTile || ty > maxTile) continue
            val tb = try {
                val conn = (java.net.URL("https://tile.openstreetmap.org/$z/$tx/$ty.png").openConnection() as java.net.HttpURLConnection).apply {
                    setRequestProperty("User-Agent", "CallRadar/2.4.1 (taxi trip map)")
                    connectTimeout = 5000; readTimeout = 5000
                }
                conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null }
            if (tb != null) {
                cv.drawBitmap(tb, (tx * 256 - x0).toFloat(), (ty * 256 - y0).toFloat(), null)
                tb.recycle(); loaded++
            }
        }
        if (loaded == 0) return null   // 네트워크 실패 → 단색 폴백
        fun sx(lng: Double): Float = (pxX(lng, z) - x0).toFloat()
        fun sy(lat: Double): Float = (pxY(lat, z) - y0).toFloat()
        // 실차=빨강 실선, 공차=회색 점선
        val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#EF4444"); strokeWidth = 9f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#64748B"); strokeWidth = 6f; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(14f, 14f), 0f) }
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            if (b.ts - a.ts > 10 * 60_000L) continue
            cv.drawLine(sx(a.lng), sy(a.lat), sx(b.lng), sy(b.lat), if (b.loaded) solid else dashed)
        }
        // 승·하차 빨간 포인트(흰 테두리)
        val rideDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#EF4444") }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            if (b.loaded && !a.loaded) { cv.drawCircle(sx(b.lng), sy(b.lat), 13f, rideDot); cv.drawCircle(sx(b.lng), sy(b.lat), 13f, ring) }
            if (!b.loaded && a.loaded) { cv.drawCircle(sx(a.lng), sy(a.lat), 13f, rideDot); cv.drawCircle(sx(a.lng), sy(a.lat), 13f, ring) }
        }
        val startP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#10B981") }
        val nowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#F59E0B") }
        cv.drawCircle(sx(pts.first().lng), sy(pts.first().lat), 15f, startP); cv.drawCircle(sx(pts.first().lng), sy(pts.first().lat), 15f, ring)
        cv.drawCircle(sx(pts.last().lng), sy(pts.last().lat), 17f, nowP); cv.drawCircle(sx(pts.last().lng), sy(pts.last().lat), 17f, ring)
        // ── 밴드와 글자
        // [v93] 글자가 이미지 밖으로 나가지 않게 폭에 맞춰 자동 축소한다.
        //  예전엔 textSize가 고정이라 폭 좁은 이미지에서 제목·범례가 그대로 잘려나갔다.
        fun fitSize(p: Paint, s: String, maxW: Float, start: Float): Float {
            var ts = start
            p.textSize = ts
            while (ts > 12f && p.measureText(s) > maxW) { ts -= 1f; p.textSize = ts }
            return ts
        }
        val band = Paint().apply { color = android.graphics.Color.parseColor("#CC0A0E1A") }
        // 상단 제목 밴드
        cv.drawRect(0f, 0f, outW.toFloat(), headerH, band)
        val titleTxt = "콜레이더 · 운행 궤적"
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#F59E0B"); isFakeBoldText = true }
        fitSize(title, titleTxt, outW - 40f, 42f)
        cv.drawText(titleTxt, 20f, headerH * 0.70f, title)
        // 하단 범례 + OSM 저작자 표기(필수 — OSM 라이선스상 뺄 수 없다)
        val footTop = outH - footerH
        cv.drawRect(0f, footTop, outW.toFloat(), outH.toFloat(), band)
        val attrTxt = "© OpenStreetMap"
        val attr = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#E5E7EB"); textAlign = Paint.Align.RIGHT }
        fitSize(attr, attrTxt, outW * 0.30f, 20f)
        cv.drawText(attrTxt, outW - 16f, footTop + footerH * 0.66f, attr)
        val legendTxt = "● 시작  ● 현재  ● 승·하차  ─ 실차  ┄ 공차"
        val legend = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        // 저작자 표기가 차지한 폭을 빼고 남는 자리에 맞춘다 → 둘이 겹치지 않는다
        fitSize(legend, legendTxt, outW - 32f - attr.measureText(attrTxt) - 20f, 24f)
        cv.drawText(legendTxt, 16f, footTop + footerH * 0.66f, legend)
        return out
    }

    /**
     * [v92] 공유 이미지 아래에 요약 수치를 붙인다.
     *
     *  화면에는 '오늘 요약' 카드가 있는데 공유하면 지도만 나갔다. 받는 사람 입장에선
     *  선만 그려진 그림이라 실차율이 얼마인지, 몇 km를 뛴 건지 알 수가 없다.
     *  지도 렌더는 두 갈래(OSM 타일 / 단색 폴백)라 각각 고치면 어긋나기 쉬우니,
     *  완성된 비트맵 아래에 패널 한 장을 덧대는 방식으로 한 곳에서 처리한다.
     */
    private fun withSummary(src: Bitmap): Bitmap {
        // 요약 수치가 없어도(집계 실패·궤적 없음) 도장은 찍어서 내보낸다 —
        //  워터마크 없는 이미지가 돌아다니면 넣은 의미가 없다.
        val s = stats ?: return src.copy(Bitmap.Config.ARGB_8888, true)
            .also { stamp(Canvas(it), it.width.toFloat(), it.height.toFloat()) }
        val w = src.width
        // [v93] 폭이 1080으로 고정되면서 0.34배(367px)는 지도보다 패널이 더 눈에 띄는 크기가 됐다.
        //  비율은 유지하되 상한을 둔다 — 주인공은 궤적이지 숫자판이 아니다.
        val panel = (w * 0.26f).toInt().coerceIn(150, 300)
        val out = Bitmap.createBitmap(w, src.height + panel, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawBitmap(src, 0f, 0f, null)

        val panelTop = src.height.toFloat()
        cv.drawRect(0f, panelTop, w.toFloat(), out.height.toFloat(),
            Paint().apply { color = android.graphics.Color.parseColor(if (dark) "#0F172A" else "#F8FAFC") })
        cv.drawLine(0f, panelTop, w.toFloat(), panelTop,
            Paint().apply { color = android.graphics.Color.parseColor("#F59E0B"); strokeWidth = 4f })

        val lkm = s.loadedM / 1000.0
        val ekm = s.emptyM / 1000.0
        val tkm = lkm + ekm
        val occ = if (tkm > 0) (lkm / tkm * 100).toInt() else 0
        val totalMin = s.loadedMinutes + s.emptyMinutes

        val pad = w * 0.055f
        val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(if (dark) "#94A3B8" else "#64748B"); textSize = w * 0.030f
        }
        val valueP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(if (dark) "#F1F5F9" else "#0F172A")
            textSize = w * 0.052f; isFakeBoldText = true
        }
        // 3열 — 총 주행 / 실차율 / 운행시간. 한 줄에 다 보이는 게 핵심이라 항목을 늘리지 않는다.
        val cols = listOf(
            Triple("총 주행", String.format("%.1f km", tkm), null),
            Triple("실차율", "${occ}%", "#10B981"),
            Triple("운행시간", if (totalMin >= 60) "${totalMin / 60}시간 ${totalMin % 60}분" else "${totalMin}분", null)
        )
        val colW = (w - pad * 2) / 3f
        cols.forEachIndexed { i, (label, value, hex) ->
            val cx = pad + colW * i + colW / 2f
            labelP.textAlign = Paint.Align.CENTER; valueP.textAlign = Paint.Align.CENTER
            val vp = if (hex != null) Paint(valueP).apply { color = android.graphics.Color.parseColor(hex) } else Paint(valueP)
            // [v93] "5시간 49분"처럼 긴 값이 옆 칸을 침범하던 것 방지 — 칸 폭에 맞춰 줄인다
            while (vp.textSize > 16f && vp.measureText(value) > colW * 0.92f) vp.textSize = vp.textSize - 1f
            cv.drawText(label, cx, panelTop + panel * 0.30f, labelP)
            cv.drawText(value, cx, panelTop + panel * 0.58f, vp)
        }
        // 세부 한 줄 — 실차/공차 내역
        val detailP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(if (dark) "#64748B" else "#94A3B8")
            textSize = w * 0.028f; textAlign = Paint.Align.CENTER
        }
        cv.drawText(
            String.format("실차 %.1fkm · 공차 %.1fkm   |   %s", lkm, ekm, dateLabel),
            w / 2f, panelTop + panel * 0.80f, detailP)

        stamp(cv, w.toFloat(), src.height.toFloat())
        return out
    }

    /**
     * [v92] 콜레이더 도장 — 화면 캡처(ScreenCaptureService.watermark)와 같은 모양.
     *
     *  궤적 공유는 좌상단에 작은 제목 글자만 있어서, 톡방에 올라가면 어느 앱으로 뽑은
     *  그림인지 알아볼 수가 없었다. 캡처 쪽엔 이미 기울어진 도장이 있으니 같은 모양을 쓴다 —
     *  둘이 다르면 같은 앱에서 나온 이미지로 안 보인다.
     *
     *  자리는 지도 우하단. 궤적 선은 대개 화면 중앙을 지나가고, 하단 범례 띠 위쪽이 비어 있다.
     */
    private fun stamp(c: Canvas, w: Float, mapH: Float) {
        val gold = android.graphics.Color.rgb(245, 158, 11)
        val label = "콜레이더"
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gold; isFakeBoldText = true
            // [v93] 폭 1080 고정 후 0.055배(59px)는 도장이 궤적을 덮을 만큼 커졌다 → 상·하한을 둔다
            textSize = (w * 0.045f).coerceIn(30f, 46f)
            letterSpacing = 0.14f; textAlign = Paint.Align.CENTER
        }
        val padX = tp.textSize * 0.52f; val padY = tp.textSize * 0.34f
        val boxW = tp.measureText(label) + padX * 2
        val boxH = tp.textSize + padY * 2
        // [v93] 회전(-30°)까지 감안해 박스가 이미지 밖으로 안 나가게 가둔다.
        //  예전엔 cx=0.70w 고정이라 폭이 좁은 이미지에서 우측이 잘려나갔다.
        val half = (boxW + boxH) / 2f * 0.75f
        val cx = (w * 0.70f).coerceIn(half, w - half)
        val cy = (mapH * 0.82f).coerceIn(half, mapH - half)
        c.save(); c.rotate(-30f, cx, cy)
        val r = android.graphics.RectF(cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy + boxH / 2f)
        val radius = boxH * 0.24f
        // 불투명 박스 — 지도가 밝든(주간 타일) 어둡든 글자가 항상 뜬다
        c.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(10, 14, 26) })
        c.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gold; style = Paint.Style.STROKE
            strokeWidth = (boxH * 0.06f).coerceAtLeast(3f)
        })
        val fm = tp.fontMetrics
        c.drawText(label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, tp)
        c.restore()
    }

    private fun sharePng() {
        val base = bitmap ?: return
        Thread {
            try {
                val bmp = try { withSummary(base) } catch (e: Exception) { base }   // 요약 실패해도 지도는 나가게
                val dir = File(cacheDir, "shares"); dir.mkdirs()
                val f = File(dir, "track_${System.currentTimeMillis()}.png")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
                    // [v92] 이미지를 못 받는 대상(문자·메모 등)에도 수치가 남게 본문에도 넣는다
                    putExtra(Intent.EXTRA_TEXT, stats?.let { s ->
                        val lkm = s.loadedM / 1000.0; val ekm = s.emptyM / 1000.0; val tkm = lkm + ekm
                        val occ = if (tkm > 0) (lkm / tkm * 100).toInt() else 0
                        val m = s.loadedMinutes + s.emptyMinutes
                        String.format("%s 운행 · 총 %.1fkm (실차 %.1f / 공차 %.1f) · 실차율 %d%% · %d시간 %d분\n— 콜레이더",
                            dateLabel, tkm, lkm, ekm, occ, m / 60, m % 60)
                    } ?: "오늘 운행 궤적 (콜레이더)")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(Intent.createChooser(send, "궤적 공유").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (e: Exception) { android.util.Log.e("CallRadar", "궤적 공유 실패: ${e.message}") }
        }.start()
    }

    // [테마버그] 하드코딩 다크색 → 앱 전역 테마(AppTheme) 참조. isDark 바뀌면 자동 리컴포즈.
    private val bg: Color get() = AppTheme.bg
    private val card: Color get() = AppTheme.card
    private val surface2: Color get() = AppTheme.surface2
    private val text: Color get() = AppTheme.text
    private val muted: Color get() = AppTheme.muted
    private val accent = Color(0xFFF59E0B); private val green = Color(0xFF10B981); private val blue = Color(0xFF3B82F6)

    @Composable
    private fun Screen() {
        Column(Modifier.fillMaxSize().background(bg).padding(20.dp).verticalScroll(rememberScrollState())) {
            Text("운행 궤적", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            Text("근무 중 기록된 실차·공차 경로. 이미지로 공유할 수 있어요.", fontSize = 12.sp, color = muted, modifier = Modifier.padding(bottom = 8.dp))
            // [과거날짜] 날짜 이동 — 최대 31일 전까지 저장된 궤적 조회
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { if (dayOffset > -30) { dayOffset--; load() } }) { Text("◀ 이전날", color = accent, fontSize = 14.sp) }
                Text(dateLabel, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = text, modifier = Modifier.padding(horizontal = 14.dp))
                TextButton(onClick = { if (dayOffset < 0) { dayOffset++; load() } }, enabled = dayOffset < 0) { Text("다음날 ▶", color = if (dayOffset < 0) accent else muted, fontSize = 14.sp) }
            }

            if (loading) { Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) } }
            else {
                bitmap?.let { bmp ->
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = "궤적", modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(card, RoundedCornerShape(14.dp)))
                }
                // [궤적버그] 궤적이 비었을 때 사유별 안내(권한/정밀/출근/오늘점없음)
                if (guide.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = surface2), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("ℹ️", fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
                            Text(guide, fontSize = 12.5.sp, color = text, lineHeight = 18.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                stats?.let { s ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(18.dp)) {
                            val lkm = s.loadedM / 1000.0; val ekm = s.emptyM / 1000.0; val tkm = lkm + ekm
                            val occ = if (tkm > 0) (lkm / tkm * 100).toInt() else 0
                            Text("오늘 요약", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = text, modifier = Modifier.padding(bottom = 8.dp))
                            KV("실차 거리", String.format("%.1f km", lkm), blue)
                            KV("공차 거리", String.format("%.1f km", ekm), muted)
                            KV("실차율(거리)", "${occ}%", green)
                            HorizontalDivider(color = surface2, modifier = Modifier.padding(vertical = 6.dp))
                            KV("실차 시간", "${s.loadedMinutes}분", blue)
                            KV("공차/대기 시간", "${s.emptyMinutes}분", muted)
                            Text("궤적 점 ${s.points}개 · 20초 간격 기록", fontSize = 10.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(onClick = { sharePng() }, enabled = bitmap != null, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(12.dp)) {
                    Text("🖼️ 이미지로 공유(PNG)", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { load() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) { Text("새로고침", fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun KV(k: String, v: String, color: Color) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(k, fontSize = 13.sp, color = muted)
            Text(v, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}
