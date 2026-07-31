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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        if (resultCode == 0 || data == null) { stopSelfSafe(); return START_NOT_STICKY }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(resultCode, data)
            // API 34+ 필수 콜백
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { cleanup() }
            }, handler)
            captureOneFrame()
        } catch (e: Exception) {
            toast("화면 캡처 실패")
            stopSelfSafe()
        }
        return START_NOT_STICKY
    }

    private fun captureOneFrame() {
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vDisplay = projection?.createVirtualDisplay(
            "callradar_cap", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, handler
        )

        reader?.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val cropped: Bitmap
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * w
                var bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buffer)
                if (bmp.width != w) bmp = Bitmap.createBitmap(bmp, 0, 0, w, h)
                // [v24] 종료 금액 자동파싱 모드: 전체 프레임 OCR → 금액만 추출 (크롭/공유 없음)
                val purpose0 = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getString("capture_purpose", "") ?: ""
                if (purpose0 == "endfare") {
                    getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).edit().remove("capture_purpose").apply()
                    val full = bmp.copy(Bitmap.Config.ARGB_8888, false)
                    image.close(); cleanup()
                    parseFareAndStore(full)
                    return@setOnImageAvailableListener
                }
                // 미리보기용 원본(크롭 전) 저장 — 공유 설정에서 크롭 조절에 사용
                try { val d = File(cacheDir, "shares").apply { mkdirs() }; FileOutputStream(File(d, "last_full.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 85, it) } } catch (e: Exception) {}
                cropped = cropForShare(bmp)   // 독립 비트맵 복사본 → 아래서 projection 해제해도 유지
            } catch (e: Exception) {
                toast("이미지 처리 실패")
                image.close(); cleanup(); stopSelfSafe()
                return@setOnImageAvailableListener
            }
            image.close()
            cleanup()   // projection/display 해제 (cropped는 독립 복사본)
            val mode = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getString("share_mode", "screenshot") ?: "screenshot"
            if (mode == "text") {
                ocrAndShare(cropped)   // 한글 OCR 비동기 → 콜백에서 종료
            } else {
                shareImage(watermark(cropped))
                stopSelfSafe()
            }
        }, handler)
    }

    /**
     * 공유용 크롭 — 플랫폼 콜 팝업(상단 카드)만 남기고 잘라 가볍게.
     * 프리셋(높이 프랙션)으로 저장·조정: shot_crop_top / shot_crop_bottom (기본 카카오T용).
     * 플랫폼마다 위치가 달라 프리셋을 바꿔 대응(추후 앱별 자동선택). shot_crop_on=false면 전체 화면.
     */
    private fun cropForShare(src: Bitmap): Bitmap {
        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("shot_crop_on", true)) return src
        val plat = prefs.getString("shot_platform", "카카오T") ?: "카카오T"
        val topF = prefs.getFloat("shot_crop_top_$plat", 0.04f).coerceIn(0f, 0.9f)
        val botF = prefs.getFloat("shot_crop_bottom_$plat", 0.52f).coerceIn(topF + 0.05f, 1f)
        val top = (src.height * topF).toInt().coerceIn(0, src.height - 1)
        val bottom = (src.height * botF).toInt().coerceIn(top + 1, src.height)
        return try { Bitmap.createBitmap(src, 0, top, src.width, bottom - top) } catch (e: Exception) { src }
    }

    /** 콜레이더 브랜드 워터마크 (하단 반투명 바) — 어디에 공유되든 우리 이름이 박힘 */
    private fun watermark(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val barH = (out.height * 0.06f).coerceAtLeast(70f)
        val bg = Paint().apply { color = Color.argb(180, 10, 14, 26) }
        c.drawRect(0f, out.height - barH, out.width.toFloat(), out.height.toFloat(), bg)
        val tp = Paint().apply {
            color = Color.rgb(245, 158, 11); isAntiAlias = true
            textSize = barH * 0.42f; isFakeBoldText = true
        }
        val tp2 = Paint().apply {
            color = Color.WHITE; isAntiAlias = true; textSize = barH * 0.30f
        }
        val y = out.height - barH / 2f
        c.drawText("📻 콜레이더", 24f, y - 4f, tp)
        c.drawText("택시기사 수입관리 · 실시간 콜 · 공항정보", 24f, y + tp2.textSize, tp2)
        return out
    }

    private fun shareImage(bmp: Bitmap) {
        try {
            val dir = File(cacheDir, "shares").apply { mkdirs() }
            val f = File(dir, "callradar_share.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)

            // 브랜드 문구는 클립보드에도 (방에 붙여넣기 쉽게)
            val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            val promo = (prefs.getString("share_promo", "") ?: "").trim()
            val room = (prefs.getString("share_room_url", "") ?: "").trim()
            val text = "🚕 콜레이더로 공유\n📻 택시기사 수입관리·실시간 콜·공항정보" + (if (promo.isNotEmpty()) "\n$promo" else "")
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("콜레이더", text))
            } catch (e: Exception) {}

            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "콜레이더 공유").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            startActivity(chooser)
            if (room.isNotEmpty()) toast("문구 복사됨 — 사진 첨부 후 붙여넣기") else toast("공유할 앱을 선택하세요")
        } catch (e: Exception) {
            toast("공유 실패")
        }
    }

    /** [v2] 텍스트 공유 — 크롭한 콜 팝업을 한글 OCR로 읽어 글자만 공유(스샷 대신). */
    private fun ocrAndShare(bmp: Bitmap) {
        try {
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions.Builder().build())
            val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)
            recognizer.process(image)
                .addOnSuccessListener { vt -> shareText(vt.text); stopSelfSafe() }
                .addOnFailureListener { shareText(""); stopSelfSafe() }
        } catch (e: Exception) { toast("글자 인식 실패"); stopSelfSafe() }
    }

    private fun shareText(ocr: String) {
        try {
            val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            val promo = (prefs.getString("share_promo", "") ?: "").trim()
            // OCR 결과 정리: 빈 줄 제거
            val cleaned = ocr.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            val brand = "\n\n📻 콜레이더 — 택시기사 수입관리·실시간 콜·공항정보" + (if (promo.isNotEmpty()) "\n$promo" else "")
            val text = (if (cleaned.isNotEmpty()) cleaned else "🚕 콜레이더 콜 공유") + brand
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("콜레이더", text))
            } catch (e: Exception) {}
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
            startActivity(Intent.createChooser(send, "콜레이더 공유").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            toast(if (cleaned.isNotEmpty()) "텍스트 공유" else "글자를 못 읽어 기본 문구로 공유")
        } catch (e: Exception) { toast("공유 실패") }
    }

    private fun cleanup() {
        try { vDisplay?.release() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        vDisplay = null; reader = null; projection = null
    }

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
            .setContentText("화면을 캡처해 공유하는 중…")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, noti, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTI_ID, noti)
        }
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
                    stopSelfSafe()
                }
                .addOnFailureListener { toast("금액 인식 실패"); stopSelfSafe() }
        } catch (e: Exception) { toast("금액 인식 실패"); stopSelfSafe() }
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
