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
    private var lastLocTs = 0L   // [km멈춤] 직전 위치 시각 — 순간속도로 GPS점프 판정(하드 400m 컷 대체)

    private val CHANNEL_ID = "callradar_worksession"
    private val NOTI_ID = 3101
    private var lastNotiKm = -1f   // [v32] 알림에 마지막 표시한 km — 0.1km 이상 늘 때만 갱신(스팸 방지)
    private var lastTrackTs = 0L    // [v32] 궤적 점 마지막 기록 시각(저빈도 샘플링)
    private var locThread: android.os.HandlerThread? = null   // [v33] 위치 콜백·DB를 메인 스레드 밖에서 처리(ANR 방지)

    override fun onBind(intent: Intent?): IBinder? = null

    private fun prefs() = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        locThread = android.os.HandlerThread("worksession-loc").apply { start() }   // [v33] 위치 콜백 전용 백그라운드 루퍼
        // [v32] 오래된 궤적 정리(7일 이전) — 저장 누수 방지. [v33] 메인 스레드 밖에서.
        Thread { try { com.callradar.app.LocalTrackDatabase.getInstance(this).purgeBefore(System.currentTimeMillis() - 31L * 86400_000L) } catch (e: Exception) {} }.start()   // [과거날짜] 7→31일 보관(지난 날짜 궤적 조회용)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasLoc = fine || coarse
        // [크래시 방지] location 타입 FGS는 위치권한이 있어야만 시작 가능(Android 14+). 없으면 타입 없이 시작(계약 충족)→즉시 종료.
        startForegroundSafe(hasLoc)
        if (!hasLoc) { stopSelf(); return START_NOT_STICKY }
        // 재개/재시작 시 이전 좌표는 버림(정지 구간을 거리에 안 더함)
        lastLat = Double.NaN; lastLng = Double.NaN; lastLocTs = 0L
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
            fusedClient.requestLocationUpdates(req, cb, (locThread?.looper ?: Looper.getMainLooper()))   // [v33] 백그라운드 루퍼에서 콜백 → DB/거리계산 메인 스레드 밖
        } catch (e: Exception) {}
        return START_STICKY
    }

    private fun onNewLocation(loc: Location) {
        // 정확도 나쁜 값(터널·실내 튐)은 버림.
        // [궤적버그] 50m→150m: '대략적 위치'만 허용한 유저는 정확도 100m+라 예전엔 모든 점이 버려져 궤적이 통째로 비었음.
        //  150m로 완화해 근사 위치도 점이 쌓이게(궤적은 거칠어도 남게). 순간속도 필터가 GPS 점프는 계속 차단.
        if (loc.hasAccuracy() && loc.accuracy > 150f) return
        val lat = loc.latitude; val lng = loc.longitude
        val nowMs = System.currentTimeMillis()
        if (!lastLat.isNaN() && !lastLng.isNaN() && lastLocTs > 0L) {
            val out = FloatArray(1)
            Location.distanceBetween(lastLat, lastLng, lat, lng, out)
            val d = out[0]
            val dtSec = (nowMs - lastLocTs) / 1000.0
            val speed = if (dtSec > 0.0) d / dtSec else Double.MAX_VALUE   // m/s
            // [km멈춤 버그] 예전엔 절대거리 5~400m만 누적 → 업데이트 간격이 벌어지는 장거리·고속 주행에서
            //  한 구간이 400m를 넘어 '전부 GPS점프로 오판·폐기' → 누적이 멈춤(예: 60km에서 정지, 300km 달려도 그대로).
            //  이제 '순간속도'로 판정: 5m 이상 이동 + 60m/s(216km/h) 이하 + 단일구간 20km 이하면 정상 이동으로 누적.
            if (d >= 5f && speed <= 60.0 && d <= 20000f) {
                val cur = prefs().getFloat("work_distance_m", 0f)
                val nv = cur + d
                prefs().edit().putFloat("work_distance_m", nv).apply()
                // [v32] 알림 실시간 갱신 — 예전엔 시작 때 0.0km로 만든 알림을 안 바꿔서 계속 0.0으로 멈춰 있었음
                val km = nv / 1000f
                if (km - lastNotiKm >= 0.1f) { lastNotiKm = km; updateNoti(km) }
            }
        }
        // [v41 수정] 궤적 브레드크럼 — 20초→4초로 단축. 20초는 점이 너무 성겨 실차/공차 거리가 안 맞고
        //  코너·단거리 트립이 통째로 누락됐음. 위치 업데이트가 2~4초로 오므로 4초 게이트면 대부분의 점을 기록(<5초).
        val now = System.currentTimeMillis()
        if (now - lastTrackTs >= 4_000L) {
            lastTrackTs = now
            val loaded = prefs().getBoolean("ride_active", false)
            try { com.callradar.app.LocalTrackDatabase.getInstance(this).addPoint(lat, lng, now, loaded) } catch (e: Exception) {}
        }
        lastLat = lat; lastLng = lng; lastLocTs = nowMs
    }

    private fun buildNoti(km: Float): Notification {
        val pi = try {
            val i = packageManager.getLaunchIntentForPackage(packageName)
            PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } catch (e: Exception) { null }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("근무 중 — 거리 기록")
            .setContentText(String.format("이동 거리 %.1f km", km))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)   // 갱신 때 소리·진동 없이 조용히 텍스트만 바꿈
            .apply { if (pi != null) setContentIntent(pi) }
            .build()
    }

    // [v32] 누적 거리 반영해 알림 텍스트만 갱신
    private fun updateNoti(km: Float) {
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTI_ID, buildNoti(km)) } catch (e: Exception) {}
    }

    private fun startForegroundSafe(hasLoc: Boolean = true) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(CHANNEL_ID, "근무 세션", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
                nm.createNotificationChannel(ch)
            }
            val km = prefs().getFloat("work_distance_m", 0f) / 1000f
            lastNotiKm = km
            val noti = buildNoti(km)
            // 위치권한 있을 때만 location 타입 지정(없으면 타입 지정이 SecurityException → 크래시). 없으면 일반 FGS로 계약만 충족.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasLoc) {
                startForeground(NOTI_ID, noti, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTI_ID, noti)
            }
        } catch (e: Exception) {
            // [원스토어 반려수정] startForegroundService()는 반드시 startForeground()를 한 번은 호출해야 함.
            //  타입 지정 승격이 실패(SecurityException/FGS 제약)하면 여기서 일반 FGS로 폴백해 계약을 충족.
            //  이걸 안 하면 시스템이 'did not start in time' 예외로 앱을 강제 종료시킴(퇴근 전후 크래시 원인 후보).
            try {
                val km2 = prefs().getFloat("work_distance_m", 0f) / 1000f
                startForeground(NOTI_ID, buildNoti(km2))
            } catch (e2: Exception) {
                try { stopSelf() } catch (e3: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        try { callback?.let { fusedClient.removeLocationUpdates(it) } } catch (e: Exception) {}
        callback = null
        try { locThread?.quitSafely() } catch (e: Exception) {}   // [v33] 백그라운드 루퍼 정리
        locThread = null
        super.onDestroy()
    }
}
