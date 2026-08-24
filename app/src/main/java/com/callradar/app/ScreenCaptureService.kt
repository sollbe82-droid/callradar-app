package com.callradar.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * [v18] MediaProjection 화면캡처 서비스 (FGS type=mediaProjection).
 *  1프레임만 캡처 → 콜레이더 워터마크 → 이미지 공유(오픈방/공유시트) + 브랜드 문구 클립보드.
 *  · 유저 개시(동의창 통과 후) · 캡처 즉시 종료 · 백그라운드 상시감시 아님 → 심사 안전.
 *  · 나중 자동화의 금액캡처(전표 OCR)도 같은 MediaProjection 권한 위에 얹힘(발판).
 */
class ScreenCaptureService : Service() {

    private val CH = "callradar_capture"
    private val NOTI_ID = 7002
    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    // [v29] Android 14: createVirtualDisplay는 projection당 1회만 허용(2회째 예외→재동의).
    //  → 디스플레이를 근무 내내 1개만 살려두고, 캡처마다 setSurface로 새 프레임만 받아 재동의를 없앤다.
    private var capW = 0; private var capH = 0; private var capDpi = 0
    @Volatile private var pendingPurpose: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CAPTURE = "com.callradar.app.CAPTURE"
        const val ACTION_STOP = "com.callradar.app.STOP_CAP"
        // [v24] 근무세션 동안 화면권한(projection) 유지 여부. true면 종료마다 '동의창 없이' 캡처.
        @Volatile var sessionAlive = false
        // 유지된 projection으로 즉시 캡처(동의 없음). 세션 죽었으면 아무 일 없음(호출부가 동의 경로로).
        fun captureNow(ctx: Context, purpose: String) {
            ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).edit().putString("capture_purpose", purpose).apply()
            val i = Intent(ctx, ScreenCaptureService::class.java).setAction(ACTION_CAPTURE)
            try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i) } catch (e: Exception) {}
        }
        /**
         * [v91] 화면 캡처 → 크롭 → 워터마크 → 공유. 어디서든 이 한 줄만 부르면 된다.
         *
         *  근무 중이라 화면권한이 살아 있으면 동의창 없이 바로 찍고,
         *  아니면 동의창을 띄운다(동의하면 그 자리에서 이어서 찍힘).
         *  전부 유저가 눌러서 시작하고 결과를 눈으로 보는 흐름이라 스토어 심사 범위 안이다.
         */
        fun shareShot(ctx: Context) {
            if (sessionAlive) { captureNow(ctx, "share"); return }
            ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                .edit().putString("capture_purpose", "share").apply()
            ScreenCapturePermissionActivity.start(ctx)
        }

        // 퇴근 시 projection 완전 해제
        fun stopSession(ctx: Context) {
            val wasAlive = sessionAlive
            sessionAlive = false
            // 활성 projection이 없으면 서비스를 깨우지 않음 (mediaProjection FGS 승격 크래시 방지)
            if (!wasAlive) return
            val i = Intent(ctx, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            try { ctx.startService(i) } catch (e: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ACTION_STOP은 포그라운드 승격 없이 즉시 정리 (projection 없을 때 mediaProjection FGS 크래시 방지)
        if (intent?.action == ACTION_STOP) { releaseProjection(); stopSelfSafe(); return START_NOT_STICKY }
        startAsForeground()
        when (intent?.action) {
            ACTION_STOP -> { releaseProjection(); stopSelfSafe(); return START_NOT_STICKY }
            ACTION_CAPTURE -> {
                // 살아있는 디스플레이에 setSurface로 새 프레임만 받음 (createVirtualDisplay 재호출 없음 → 재동의 없음)
                if (projection != null && vDisplay != null) {
                    val purpose = readAndClearPurpose().ifEmpty { "endfare" }
                    requestCapture(purpose)
                } else { sessionAlive = false; stopSelfSafe() }
                return START_STICKY
            }
            else -> {
                val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
                val data = intent?.getParcelableExtra<Intent>("data")
                if (resultCode == 0 || data == null) { stopSelfSafe(); return START_NOT_STICKY }
                try {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projection = mpm.getMediaProjection(resultCode, data)
                    projection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() { releaseProjection() }
                    }, handler)
                    ensureDisplay()   // [v29] createVirtualDisplay 1회만 — 근무 내내 유지
                    // 출근 확립 시엔 purpose 없음(세션만 확립). 종료가 동의를 부른 경우엔 purpose 있음 → 즉시 캡처.
                    val purpose = readAndClearPurpose()
                    // [v91] 'share'는 동의창이 닫히기를 잠깐 기다린 뒤 찍는다.
                    //  권한 액티비티가 Translucent라 뒤 화면(카카오T 등)이 그대로 살아 있어서,
                    //  0.6초만 기다리면 원하던 화면이 그대로 잡힌다.
                    //  (안내만 하고 다시 누르게 하면 손이 한 번 더 간다 — 유저가 그걸 불편하다고 했다)
                    if (purpose == "share") {
                        handler.postDelayed({ requestCapture("share") }, 600)
                    } else if (purpose.isNotEmpty()) requestCapture(purpose)
                } catch (e: Exception) { toast("화면 읽기를 시작할 수 없어요"); stopSelfSafe() }
                return START_STICKY
            }
        }
    }

    private fun readAndClearPurpose(): String {
        val p = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getString("capture_purpose", "") ?: ""
        if (p.isNotEmpty()) getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).edit().remove("capture_purpose").apply()
        return p
    }

    // [v29] VirtualDisplay를 근무당 1회만 생성(Android 14 규칙). 이후 캡처는 setSurface로만.
    private fun ensureDisplay() {
        if (vDisplay != null) return
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        capW = metrics.widthPixels; capH = metrics.heightPixels; capDpi = metrics.densityDpi
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ r -> drainFrame(r) }, handler)
        vDisplay = projection?.createVirtualDisplay(
            "callradar_cap", capW, capH, capDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, handler
        )
        sessionAlive = true
    }

    // [v29] 재동의 없이 새 프레임 1장 요청 — 새 ImageReader로 표면 교체(setSurface) → 현재 화면이 즉시 렌더됨.
    private fun requestCapture(purpose: String) {
        pendingPurpose = purpose
        val vd = vDisplay ?: return
        try {
            val nr = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
            nr.setOnImageAvailableListener({ r -> drainFrame(r) }, handler)
            val old = reader
            reader = nr
            vd.setSurface(nr.surface)   // 표면 교체 → 새 프레임 푸시(정적 화면도 갱신)
            try { old?.close() } catch (e: Exception) {}
        } catch (e: Exception) {
            // 폴백: 기존 리더에 버퍼된 최신 프레임 시도
            try { drainFrame(reader) } catch (e2: Exception) {}
        }
    }

    // 프레임 수신 — 캡처 대기 중이면 처리, 아니면 버려서 파이프라인만 유지(디스플레이는 계속 살림)
    private fun drainFrame(r: ImageReader?) {
        r ?: return
        val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        val p = pendingPurpose
        if (p.isNullOrEmpty()) { try { image.close() } catch (e: Exception) {}; return }
        pendingPurpose = null   // 단일 스레드(handler) → 한 프레임만 소비
        var out: Bitmap? = null
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * capW
            var bmp = Bitmap.createBitmap(capW + rowPadding / pixelStride, capH, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            if (bmp.width != capW) bmp = Bitmap.createBitmap(bmp, 0, 0, capW, capH)
            out = bmp
        } catch (e: Exception) { }
        try { image.close() } catch (e: Exception) {}
        val bmp = out ?: run { toast("화면을 읽지 못했어요"); return }
        when (p) {
            "endfare" -> parseFareAndStore(bmp)
            "platform" -> detectPlatformAndStore(bmp)
            // [v91] 화면 공유 — 이게 연결이 빠져 있어서 캡처 기능이 통째로 죽어 있었다.
            //  detectCropShare는 구현돼 있었는데 아무 purpose도 그걸 부르지 않았다.
            "share" -> detectCropShare(bmp)
            else -> { /* 확립용/기타 — 처리 없음, 디스플레이 유지 */ }
        }
        // ★디스플레이/projection 해제하지 않음 — 근무 내내 재사용(재동의 없음)
    }

    /**
     * 공유용 크롭 — 플랫폼 콜 팝업(상단 카드)만 남기고 잘라 가볍게.
     * 프리셋(높이 프랙션)으로 저장·조정: shot_crop_top / shot_crop_bottom (기본 카카오T용).
     * 플랫폼마다 위치가 달라 프리셋을 바꿔 대응(추후 앱별 자동선택). shot_crop_on=false면 전체 화면.
     */
    // [v24] 플랫폼별 콜 화면 기본 크롭(실제 콜 화면 기준). 카카오=상단 카드, 티머니=중간 카드, 우버=중상단.
    //  유저가 설정에서 조절 안 해도 자동으로 맞게. (설정값 있으면 그게 우선)
    private fun defaultCrop(plat: String): Pair<Float, Float> = when {
        plat.contains("카카오") || plat.contains("kakao", true) -> 0.07f to 0.45f
        plat.contains("티머니") || plat.contains("tmoney", true) || plat.contains("온다") -> 0.40f to 0.80f
        plat.contains("우버") || plat.contains("uber", true) -> 0.08f to 0.55f
        else -> 0.05f to 0.52f
    }

    private fun cropForShare(src: Bitmap): Bitmap {
        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        // 플랫폼 자동선택: 설정된 값 > 마지막 쓴 플랫폼 > 카카오T
        val plat = prefs.getString("shot_platform", null) ?: prefs.getString("last_platform", "카카오T") ?: "카카오T"
        return cropForShareWith(src, plat)
    }

    // 지정 플랫폼 기준 크롭 (OCR 자동판별 결과에 사용)
    private fun cropForShareWith(src: Bitmap, plat: String): Bitmap {
        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        // [v91] 기본값을 끔으로 바꿨다. 기사님들은 톡방에 화면을 통째로 올린다.
        //  게다가 기존 프리셋(카카오 상단 7~45%)은 정작 자랑거리인 탑승·하차 정보를 잘라냈다.
        //  손님 이름 같은 게 걸리는 화면에서만 설정에서 켜면 된다.
        if (!prefs.getBoolean("shot_crop_on", false)) return src
        val (defTop, defBot) = defaultCrop(plat)
        val topF = prefs.getFloat("shot_crop_top_$plat", defTop).coerceIn(0f, 0.9f)
        val botF = prefs.getFloat("shot_crop_bottom_$plat", defBot).coerceIn(topF + 0.05f, 1f)
        val top = (src.height * topF).toInt().coerceIn(0, src.height - 1)
        val bottom = (src.height * botF).toInt().coerceIn(top + 1, src.height)
        return try { Bitmap.createBitmap(src, 0, top, src.width, bottom - top) } catch (e: Exception) { src }
    }

    // [v24] OCR 텍스트로 플랫폼 자동판별 (콜 화면에 앱 이름/특징 텍스트가 있음)
    private fun detectPlatform(text: String): String {
        val t = text.lowercase()
        return when {
            text.contains("티머니") || t.contains("tmoney") -> "티머니GO"
            text.contains("카카오") || t.contains("kakao") -> "카카오T"
            text.contains("우버") || t.contains("uber") -> "우버"
            else -> getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getString("last_platform", "카카오T") ?: "카카오T"
        }
    }

    // [v25] 시작 화면 OCR → 플랫폼 판별 → pending_platform 저장 (createTrip이 읽어 자동기록). 확실할 때만 저장.
    private fun detectPlatformAndStore(bmp: Bitmap) {
        try {
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions.Builder().build())
            recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { vt ->
                    val text = vt.text; val tl = text.lowercase()
                    val plat = when {
                        text.contains("티머니") || tl.contains("tmoney") -> "티머니GO"
                        text.contains("카카오") || tl.contains("kakao") -> "카카오T"
                        text.contains("우버") || tl.contains("uber") -> "우버"
                        else -> null
                    }
                    if (plat != null) getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).edit()
                        .putString("pending_platform", plat).putLong("pending_platform_ts", System.currentTimeMillis()).apply()
                    // [v29] 디스플레이 유지 — 근무 세션 동안 재사용(재동의 없음)
                }
                .addOnFailureListener { }
        } catch (e: Exception) { }
    }

    // [v24] 스샷 공유: 전체 프레임 OCR → 플랫폼 자동판별 → 맞는 크롭 → 워터마크 → 공유
    private fun detectCropShare(full: Bitmap) {
        try {
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions.Builder().build())
            recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(full, 0))
                .addOnSuccessListener { vt ->
                    val plat = detectPlatform(vt.text)
                    shareImage(watermark(cropForShareWith(full, plat)))
                    finishCapture()
                }
                .addOnFailureListener { shareImage(watermark(cropForShare(full))); finishCapture() }
        } catch (e: Exception) { shareImage(watermark(cropForShare(full))); finishCapture() }
    }

    // [v24] OCR 텍스트에서 출발지/목적지 추출 — 자동화 인식 엔진의 시작(시작=출발/목적지, 종료=금액).
    //  카카오/티머니 콜 화면은 '출발'/'도착'(또는 '목적지') 라벨이 있어 그 뒤/다음 줄을 주소로 잡음.
    private fun extractRoute(text: String): String {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        var origin = ""; var dest = ""
        for (i in lines.indices) {
            val l = lines[i]
            if (origin.isBlank() && (l == "출발" || l.startsWith("출발지") || l.startsWith("출발 "))) {
                origin = l.removePrefix("출발지").removePrefix("출발").trim().ifBlank { lines.getOrElse(i + 1) { "" } }
            }
            if (dest.isBlank() && (l == "도착" || l.startsWith("도착") || l.startsWith("목적지"))) {
                dest = l.removePrefix("목적지").removePrefix("도착").trim().ifBlank { lines.getOrElse(i + 1) { "" } }
            }
        }
        return buildString {
            if (origin.isNotBlank()) append("🚕 출발: $origin\n")
            if (dest.isNotBlank()) append("📍 도착: $dest")
        }.trim()
    }

    /**
     * 콜레이더 브랜드 워터마크 — 하단에 큼직한 네모박스, 안에 이름만.
     *
     * 카페 매출 인증글에 이 이미지가 올라가면 기사님들이 볼 때마다 앱이 같이 노출된다.
     * 그래서 설명 문구를 늘어놓기보다 이름 하나를 크게 박는 편이 눈에 남는다.
     * (이모지는 기기·앱마다 렌더가 달라 깨질 수 있어 글자만 쓴다)
     */
    private fun watermark(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val W = out.width.toFloat(); val H = out.height.toFloat()

        val gold = Color.rgb(245, 158, 11)
        val label = "콜레이더"

        // 기사님들은 크롭 없이 화면 통째로 톡방에 올린다. 하단 바를 깔면 원본을 덮어버려서
        // 도장처럼 비스듬히 찍는다. 자리는 우하단 — 콜 정보(목적지·요금)를 가리지 않는 유일한 여백이다.
        // (가운데 찍으면 정작 자랑하려던 목적지 글자가 묻힌다)
        val tp = Paint().apply {
            color = gold; isAntiAlias = true; isFakeBoldText = true
            // [실기기 확인] 0.082은 1080px에서 도장이 콜카드 두 개를 덮을 만큼 커졌다.
            //  톡방 썸네일에서 읽히는 선은 유지하면서 콜 정보를 안 가리는 크기로 낮춤.
            textSize = (W * 0.055f).coerceAtLeast(34f)
            letterSpacing = 0.14f
            textAlign = Paint.Align.CENTER
        }
        val textW = tp.measureText(label)
        val padX = tp.textSize * 0.52f; val padY = tp.textSize * 0.34f
        val boxW = textW + padX * 2
        val boxH = tp.textSize + padY * 2

        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val cx = W * prefs.getFloat("wm_x", 0.70f)
        val cy = H * prefs.getFloat("wm_y", 0.79f)
        val deg = prefs.getFloat("wm_deg", -30f)

        c.save()
        c.rotate(deg, cx, cy)
        val r = android.graphics.RectF(cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy + boxH / 2f)
        val radius = boxH * 0.24f
        // 불투명 박스 — 밝은 화면이든 어두운 화면이든 글자가 항상 뜬다
        c.drawRoundRect(r, radius, radius, Paint().apply { color = Color.rgb(10, 14, 26); isAntiAlias = true })
        c.drawRoundRect(r, radius, radius, Paint().apply {
            color = gold; style = Paint.Style.STROKE
            strokeWidth = (boxH * 0.06f).coerceAtLeast(3f); isAntiAlias = true
        })
        // 세로 중앙 정렬 — baseline은 글자 중심에서 (ascent+descent)/2 만큼 올린다
        val fm = tp.fontMetrics
        c.drawText(label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, tp)
        c.restore()
        return out
    }

    /**
     * [v91] 찍으면 갤러리에 저장만 하고 끝낸다.
     *
     *  전에는 찍자마자 공유 시트를 띄웠는데, 그러면 올릴 데를 매번 골라야 해서 번거로웠다.
     *  갤럭시 스샷처럼 일단 저장해두고, 올리고 싶을 때 카페든 톡방이든 사진 첨부로
     *  꺼내 쓰는 게 이미 몸에 밴 흐름이라 손이 덜 간다.
     *  공유가 급하면 알림의 '공유' 버튼을 한 번 누르면 된다.
     */
    private fun shareImage(bmp: Bitmap) {
        try {
            val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            val promo = (prefs.getString("share_promo", "") ?: "").trim()
            val text = "🚕 콜레이더로 공유\n📻 택시기사 수입관리·실시간 콜·공항정보" + (if (promo.isNotEmpty()) "\n$promo" else "")

            // 갤러리에 저장 (Android 10+는 권한 없이 MediaStore로 바로 쓸 수 있다)
            val galleryUri = saveToGallery(bmp)

            // 공유용 파일은 따로 둔다 — 갤러리 Uri를 그대로 넘기면 앱에 따라 권한 문제가 난다
            val dir = File(cacheDir, "shares").apply { mkdirs() }
            val f = File(dir, "callradar_share.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)

            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("콜레이더", text))
            } catch (e: Exception) {}

            notifyCaptured(uri, text)
            toast(if (galleryUri != null) "갤러리에 저장했어요" else "캡처했어요 — 알림에서 공유")
        } catch (e: Exception) {
            toast("캡처 저장 실패")
        }
    }

    /**
     * 갤러리(사진/CallRadar)에 저장. Android 10+는 MediaStore RELATIVE_PATH로 권한 없이 쓴다.
     *  9 이하만 외부저장소 권한이 필요한데, 없으면 조용히 실패시키고 공유 경로로만 간다.
     */
    private fun saveToGallery(bmp: Bitmap): android.net.Uri? {
        return try {
            val name = "callradar_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.KOREA)
                .format(java.util.Date()) + ".png"
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES + "/CallRadar")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val cr = contentResolver
            val uri = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cv.clear(); cv.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                cr.update(uri, cv, null, null)
            }
            uri
        } catch (e: Exception) { null }
    }

    /** 캡처 알림 — 탭하면 공유 시트. 안 누르면 그냥 갤러리에 남는다(강제 안 함). */
    private fun notifyCaptured(shareUri: android.net.Uri, text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val chId = "callradar_capture"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(android.app.NotificationChannel(
                    chId, "화면 캡처", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "콜레이더 공유").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = android.app.PendingIntent.getActivity(this, 91, chooser, flags)
            val n = androidx.core.app.NotificationCompat.Builder(this, chId)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("캡처 저장됨")
                .setContentText("갤러리에 저장했어요 · 탭하면 공유")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_share, "공유", pi)
                .build()
            nm.notify(7003, n)
        } catch (e: Exception) {}
    }

    /** [v2] 텍스트 공유 — 크롭한 콜 팝업을 한글 OCR로 읽어 글자만 공유(스샷 대신). */
    private fun ocrAndShare(bmp: Bitmap) {
        try {
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions.Builder().build())
            val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)
            recognizer.process(image)
                .addOnSuccessListener { vt -> shareText(vt.text); finishCapture() }
                .addOnFailureListener { shareText(""); finishCapture() }
        } catch (e: Exception) { toast("글자 인식 실패"); finishCapture() }
    }

    private fun shareText(ocr: String) {
        try {
            val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            val promo = (prefs.getString("share_promo", "") ?: "").trim()
            // OCR 결과 정리: 빈 줄 제거
            val route = extractRoute(ocr)   // [v24] 출발/목적지만 뽑기
            val cleaned = ocr.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            val body = if (route.isNotBlank()) route else if (cleaned.isNotEmpty()) cleaned else "🚕 콜레이더 콜 공유"
            val brand = "\n\n📻 콜레이더 — 택시기사 수입관리·실시간 콜·공항정보" + (if (promo.isNotEmpty()) "\n$promo" else "")
            val text = body + brand
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("콜레이더", text))
            } catch (e: Exception) {}
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
            startActivity(Intent.createChooser(send, "콜레이더 공유").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            toast(if (cleaned.isNotEmpty()) "텍스트 공유" else "글자를 못 읽어 기본 문구로 공유")
        } catch (e: Exception) { toast("공유 실패") }
    }

    // VirtualDisplay/Reader만 해제(projection은 세션 유지) — 다음 캡처를 위해
    private fun releaseDisplay() {
        try { vDisplay?.release() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        vDisplay = null; reader = null
    }
    // projection까지 완전 해제 (세션 종료)
    private fun releaseProjection() {
        releaseDisplay()
        try { projection?.stop() } catch (e: Exception) {}
        projection = null; sessionAlive = false
    }
    /**
     * 1회 캡처 마무리 — 아무것도 놓지 않는다. 세션은 퇴근할 때만(stopSession) 끊는다.
     *
     * [v91 수정] 스샷 누를 때마다 동의창이 다시 뜨던 원인이 여기였다. 둘이 겹쳐 있었다.
     *
     *  ① releaseDisplay()로 VirtualDisplay를 없앴다.
     *     안드로이드 14부터 createVirtualDisplay는 projection당 1회만 허용된다.
     *     그래서 다음 캡처의 ensureDisplay()가 실패 → 세션이 죽고 → 동의창 재요청.
     *     (ensureDisplay 주석에 "1회만"이라 적어두고 여기서 그걸 어기고 있었다)
     *
     *  ② inShift를 prefs work_start로 판정해서, 근무 중이 아니면 projection을 통째로 버렸다.
     *     캡처는 근무와 무관하게 쓰는 기능인데 근무 여부로 세션 수명을 정한 게 잘못이다.
     *
     *  이제 디스플레이·projection 모두 유지한다. requestCapture가 setSurface로만
     *  새 프레임을 받으므로 재동의가 필요 없다.
     */
    private fun finishCapture() {
        sessionAlive = (projection != null)
    }
    private fun cleanup() { releaseProjection() }

    private fun stopSelfSafe() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (e: Exception) {}
        stopSelf()
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH, "화면 공유", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val noti: Notification = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle("콜레이더")
            .setContentText("운행 요금 화면을 읽는 중…")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()
        // Android 14+에선 활성 projection 없이 mediaProjection FGS 승격 시 SecurityException → 반드시 방어
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTI_ID, noti, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTI_ID, noti)
            }
        } catch (e: Throwable) { /* 승격 실패(projection 없음/FGS 제약) → 무시, 호출부가 정리 */ }
    }

    private fun toast(m: String) { handler.post { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() } }

    /** [v24] 종료 시 전체 화면 OCR → 택시 요금만 추출해 pending_fare 저장 (createTrip이 읽어감) */
    private fun parseFareAndStore(bmp: Bitmap) {
        try {
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions.Builder().build())
            val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)
            recognizer.process(image)
                .addOnSuccessListener { vt ->
                    val raw = vt.text
                    val fare = extractFare(raw)
                    val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putInt("pending_fare", fare)
                        .putString("pending_fare_raw", raw.take(1500))
                        .putLong("pending_fare_ts", System.currentTimeMillis())
                        .apply()
                    // 학습용 원문 로컬 누적(원문|추출값) — 추후 서버 업로드로 규칙 자동개선
                    try {
                        val d = File(filesDir, "fare_learn").apply { mkdirs() }
                        FileOutputStream(File(d, "log.tsv"), true).use {
                            it.write(("${System.currentTimeMillis()}\t$fare\t" + raw.replace("\n", " ").take(1200) + "\n").toByteArray())
                        }
                    } catch (e: Exception) {}
                    if (fare > 0) toast("금액 인식: ${"%,d".format(fare)}원 — 기록에 자동 입력")
                    else toast("금액을 못 읽었어요 — 기록에서 직접 입력하세요")
                    // [v29] 디스플레이 유지 — 근무 세션 동안 재사용(재동의 없음)
                }
                .addOnFailureListener { toast("금액 인식 실패") }
        } catch (e: Exception) { toast("금액 인식 실패") }
    }

    /** OCR 텍스트에서 택시 요금 추출 — 키워드(합계/요금/결제…) 라인 우선, 없으면 최댓값 */
    private fun extractFare(text: String): Int {
        fun nums(s: String) = Regex("[0-9][0-9,]{2,}").findAll(s)
            .mapNotNull { it.value.replace(",", "").toIntOrNull() }
            .filter { it in 1000..2000000 }.toList()
        val lines = text.split("\n")
        for (kw in listOf("합계", "총요금", "총 요금", "받을", "청구", "결제", "요금", "금액")) {
            for (ln in lines) if (ln.contains(kw)) {
                val m = nums(ln); if (m.isNotEmpty()) return m.max()
            }
        }
        val all = nums(text)
        return if (all.isEmpty()) 0 else all.max()
    }

    override fun onDestroy() { cleanup(); super.onDestroy() }
}
