package com.callradar.app

// ===== WorkSessionService (2026-07-23) =====
// 근무 세션 거리 미터: 출근 중 GPS로 이동거리를 누적한다. (핸들링 스타일)
// - 위치 포그라운드(FGS type location, 이미 승인된 권한 재사용 → 무심사)
// - 거리는 prefs "work_distance_m"(미터, Float)에 계속 누적 → 앱이 죽어도 이어짐(자가복구)
// - 일시정지=서비스 중지(누적 멈춤), 재개=서비스 시작(이어서 누적), 퇴근=중지
// - 백그라운드 추적은 근무 세션 동안만. 종료하면 완전히 멈춤.

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*

class WorkSessionService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private var callback: LocationCallback? = null
    private var lastLat = Double.NaN
    private var lastLng = Double.NaN

    private val CHANNEL_ID = "callradar_worksession"
    private val NOTI_ID = 3101

    override fun onBind(intent: Intent?): IBinder? = null

    private fun prefs() = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafe()
        // 재개/재시작 시 이전 좌표는 버림(정지 구간을 거리에 안 더함)
        lastLat = Double.NaN; lastLng = Double.NaN
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { return START_STICKY }
        try {
            callback?.let { fusedClient.removeLocationUpdates(it) }
            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    onNewLocation(loc)
                }
            }
            callback = cb
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
                .setMinUpdateIntervalMillis(2000L)
                .setMinUpdateDistanceMeters(5f)
                .build()
            fusedClient.requestLocationUpdates(req, cb, Looper.getMainLooper())
        } catch (e: Exception) {}
        return START_STICKY
    }

    private fun onNewLocation(loc: Location) {
        // 정확도 나쁜 값(터널·실내 튐)은 버림
        if (loc.hasAccuracy() && loc.accuracy > 50f) return
        val lat = loc.latitude; val lng = loc.longitude
        if (!lastLat.isNaN() && !lastLng.isNaN()) {
            val out = FloatArray(1)
            Location.distanceBetween(lastLat, lastLng, lat, lng, out)
            val d = out[0]
            // 5m 미만(제자리 노이즈) 무시, 400m 초과(GPS 순간 점프) 무시
            if (d in 5f..400f) {
                val cur = prefs().getFloat("work_distance_m", 0f)
                prefs().edit().putFloat("work_distance_m", cur + d).apply()
            }
        }
        lastLat = lat; lastLng = lng
    }

    private fun startForegroundSafe() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(CHANNEL_ID, "근무 세션", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
                nm.createNotificationChannel(ch)
            }
            val pi = try {
                val i = packageManager.getLaunchIntentForPackage(packageName)
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            } catch (e: Exception) { null }
            val km = prefs().getFloat("work_distance_m", 0f) / 1000f
            val noti = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("근무 중 — 거리 기록")
                .setContentText(String.format("이동 거리 %.1f km", km))
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .apply { if (pi != null) setContentIntent(pi) }
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTI_ID, noti, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTI_ID, noti)
            }
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        try { callback?.let { fusedClient.removeLocationUpdates(it) } } catch (e: Exception) {}
        callback = null
        super.onDestroy()
    }
}
