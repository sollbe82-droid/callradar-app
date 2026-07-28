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
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * w
                var bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buffer)
                if (bmp.width != w) bmp = Bitmap.createBitmap(bmp, 0, 0, w, h)
                val stamped = watermark(cropForShare(bmp))
                shareImage(stamped)
            } catch (e: Exception) {
                toast("이미지 처리 실패")
            } finally {
                image.close()
                cleanup()
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
        val topF = prefs.getFloat("shot_crop_top", 0.04f).coerceIn(0f, 0.9f)
        val botF = prefs.getFloat("shot_crop_bottom", 0.52f).coerceIn(topF + 0.05f, 1f)
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

    override fun onDestroy() { cleanup(); super.onDestroy() }
}
