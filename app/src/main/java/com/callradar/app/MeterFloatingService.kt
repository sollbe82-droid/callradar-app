package com.callradar.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

/**
 * [v19] 요금 미터기 플로팅 백그라운드 서비스 — 내비(티맵/카카오T) 위에 요금이 떠 있게.
 *  포그라운드 위치(location) + 오버레이 요금 표시. 탭=정지·기록저장, 길게=취소(저장 안 함).
 *  요금엔진은 MeterActivity의 순수함수(calcMeterFare/rateOfRegion/nightPctNow) 재활용. 참고용 추정.
 */
class MeterFloatingService : Service() {
    companion object {
        var running = false
        fun start(ctx: Context) { ctx.startService(Intent(ctx, MeterFloatingService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, MeterFloatingService::class.java)) }
    }

    private val SERVER_URL = "https://callradar-server.onrender.com"
    private lateinit var wm: WindowManager
    private var view: TextView? = null
    private lateinit var fused: FusedLocationProviderClient
    private var callback: LocationCallback? = null

    private var rate = MeterRate()
    private var intercity = false
    private var nightPct = 0
    private var distanceM = 0.0
    private var slowSec = 0L
    private var lastLoc: Location? = null
    private var lastTickMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility", "MissingPermission")
    override fun onCreate() {
        super.onCreate()
        running = true
        val prefs = getSharedPreferences("callradar_prefs", MODE_PRIVATE)
        rate = rateOfRegion(prefs.getString("meter_region", "서울") ?: "서울")
        intercity = prefs.getBoolean("meter_intercity", false)
        nightPct = nightPctNow(rate)

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        fused = LocationServices.getFusedLocationProviderClient(this)

        val tv = TextView(this).apply {
            text = "₩0\n미터기"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#10B981"))
            setPadding(12, 8, 12, 8)
        }
        view = tv
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 30; y = 240 }

        var initX = 0; var initY = 0; var tx = 0f; var ty = 0f; var moved = false
        val lpH = android.os.Handler(Looper.getMainLooper()); var longPressed = false; var lpRun: Runnable? = null
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { initX = params.x; initY = params.y; tx = e.rawX; ty = e.rawY; moved = false; longPressed = false
                    lpRun = Runnable { if (!moved) { longPressed = true; finishMeter(false) } }; lpH.postDelayed(lpRun!!, 1000); true }
                MotionEvent.ACTION_MOVE -> { val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                    if (kotlin.math.abs(dx) > 28 || kotlin.math.abs(dy) > 28) { moved = true; lpRun?.let { lpH.removeCallbacks(it) } }
                    params.x = initX + dx; params.y = initY + dy; try { wm.updateViewLayout(tv, params) } catch (ex: Exception) {}; true }
                MotionEvent.ACTION_UP -> { lpRun?.let { lpH.removeCallbacks(it) }; if (!moved && !longPressed) finishMeter(true); true }
                else -> false
            }
        }
        try { wm.addView(tv, params) } catch (e: Exception) {}
        startLocationForeground()
        startGps()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { update(); return }
        val cb = object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                val loc = res.lastLocation ?: return
                val now = System.currentTimeMillis()
                val spd = loc.speed * 3.6f
                val prev = lastLoc
                if (prev != null && loc.accuracy <= 30f) {
                    val d = FloatArray(1); Location.distanceBetween(prev.latitude, prev.longitude, loc.latitude, loc.longitude, d)
                    val seg = d[0].toDouble()
                    if (seg in 2.0..300.0 && spd >= rate.slowKmh) distanceM += seg
                }
                if (lastTickMs > 0 && spd < rate.slowKmh) slowSec += (now - lastTickMs) / 1000
                lastTickMs = now; lastLoc = loc
                nightPct = nightPctNow(rate)
                update()
            }
        }
        callback = cb
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L).setMinUpdateIntervalMillis(1000L).build()
        try { fused.requestLocationUpdates(req, cb, Looper.getMainLooper()) } catch (e: Exception) {}
    }

    private fun fareNow(): Int = calcMeterBreakdown(distanceM, slowSec, rate, nightPct, intercity).total
    private fun update() {
        val f = fareNow(); val km = distanceM / 1000.0
        view?.post { view?.text = "₩${String.format("%,d", f)}\n${String.format("%.1f", km)}km" + (if (nightPct > 0) " 🌙" else "") }
    }

    // tap=저장 후 종료, longpress=취소(저장 안 함)
    private fun finishMeter(save: Boolean) {
        val f = fareNow()
        try { callback?.let { fused.removeLocationUpdates(it) } } catch (e: Exception) {}
        if (save && f > 0) {
            val uid = getSharedPreferences("callradar_prefs", MODE_PRIVATE).getString("user_id", "") ?: ""
            if (uid.isNotEmpty()) thread {
                try {
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); sdf.timeZone = TimeZone.getTimeZone("UTC")
                    val json = JSONObject().apply { put("user_id", uid); put("platform", "미터기"); put("originName", ""); put("destName", "미터기 운행"); put("fare", f); put("payment_type", "cash"); put("source", "manual"); put("started_at", sdf.format(Date())) }
                    val conn = (URL("$SERVER_URL/api/trips/manual").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 8000 }
                    conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }; conn.responseCode
                } catch (e: Exception) {}
            }
            toast("₩${String.format("%,d", f)} 기록 저장")
        } else toast("미터기 종료")
        stopSelf()
    }

    private fun toast(m: String) { view?.post { android.widget.Toast.makeText(applicationContext, m, android.widget.Toast.LENGTH_SHORT).show() } }

    private val CH = "callradar_location"; private val NID = 3101; private var fgOn = false
    private fun startLocationForeground() {
        if (fgOn) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.createNotificationChannel(NotificationChannel(CH, "미터기", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            val pi = try { PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) } catch (e: Exception) { null }
            val noti = Notification.Builder(this, CH).setContentTitle("요금 미터기(추정) 동작 중").setContentText("화면 위 요금 표시 · 탭하면 저장").setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).apply { if (pi != null) setContentIntent(pi) }.build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(NID, noti)
            fgOn = true
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        try { callback?.let { fused.removeLocationUpdates(it) } } catch (e: Exception) {}
        try { view?.let { wm.removeView(it) } } catch (e: Exception) {}
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) {}
    }
    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent); try { view?.let { wm.removeView(it) } } catch (e: Exception) {}; stopSelf() }
}
