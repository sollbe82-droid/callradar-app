package com.callradar.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*

class LocationTrackingService : Service() {

    companion object {
        private const val TAG = "CallRadar"
        private const val CHANNEL_ID = "callradar_location"
        private const val NOTIF_ID = 3
        private const val IDLE_THRESHOLD_MS = 5 * 60 * 1000L      // 5분 이상 정차
        private const val MOVE_THRESHOLD_M = 500f                   // 500m 이상 이동
        private const val IDLE_SPEED_KMH = 3f                      // 3km/h 이하 = 정차
        private const val MIN_TRIP_DURATION_MS = 3 * 60 * 1000L    // 최소 3분 이상 이동
        private const val ALERT_COOLDOWN_MS = 10 * 60 * 1000L      // 알림 쿨다운 10분

        // GPS 좌표 + 타임스탬프 (스테일 방지)
        var currentLat: Double = 0.0
        var currentLng: Double = 0.0
        var currentSpeed: Float = 0f
        var currentBearing: Float = 0f  // [지도 화살표] 진행 방향(있을 때만 갱신)
        var isMoving: Boolean = false
        var lastLocationTime: Long = 0L  // 마지막 위치 업데이트 시간
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isTracking = false

    // 길빵 감지용
    private var idleStartTime = 0L
    private var idleStartLat = 0.0
    private var idleStartLng = 0.0
    private var tripStartTime = 0L
    private var tripStartLat = 0.0
    private var tripStartLng = 0.0
    private var isInPotentialTrip = false
    private var lastAlertTime = 0L

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, createNotification())
        startLocationTracking()
        return START_STICKY
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                updateLocation(location)
            }
        }
    }

    private fun startLocationTracking() {
        if (isTracking) return
       val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 10000L
        ).apply {
            setGranularity(Granularity.GRANULARITY_FINE)
            setMaxUpdateDelayMillis(15000L)
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(
                request, locationCallback, Looper.getMainLooper()
            )
            isTracking = true
            Log.d(TAG, "✅ GPS 추적 시작")
        } catch (e: SecurityException) {
            Log.e(TAG, "위치 권한 없음: ${e.message}")
        }
    }

    private fun updateLocation(location: Location) {
        currentLat = location.latitude
        currentLng = location.longitude
        currentSpeed = location.speed * 3.6f
        if (location.hasBearing()) currentBearing = location.bearing  // [지도 화살표] 방향
        lastLocationTime = System.currentTimeMillis()  // 타임스탬프 갱신
        isMoving = currentSpeed > IDLE_SPEED_KMH

        val status = when {
            currentSpeed > 30 -> "고속이동"
            currentSpeed > IDLE_SPEED_KMH -> "이동중"
            else -> "정차"
        }
        Log.d(TAG, "📍 위치: $currentLat, $currentLng | 속도: ${currentSpeed.toInt()}km/h | $status")

         }

    private fun detectUnloggedTrip(location: Location) {
        val now = System.currentTimeMillis()
        val speedKmh = location.speed * 3.6f

        if (speedKmh <= IDLE_SPEED_KMH) {
            // 정차 중
            if (!isInPotentialTrip) {
                // 정차 시작 시점 기록
                if (idleStartTime == 0L) {
                    idleStartTime = now
                    idleStartLat = location.latitude
                    idleStartLng = location.longitude
                }
            } else {
                // 이동하다가 멈춤 → 트립 종료 판단
                val tripDuration = now - tripStartTime
                val results = FloatArray(1)
                Location.distanceBetween(
                    tripStartLat, tripStartLng,
                    location.latitude, location.longitude,
                    results
                )
                val distanceM = results[0]

                if (tripDuration >= MIN_TRIP_DURATION_MS && distanceM >= MOVE_THRESHOLD_M) {
                    // 유효한 이동 감지 → 길빵 알림
                    val durationMin = (tripDuration / 60000).toInt()
                    if (now - lastAlertTime > ALERT_COOLDOWN_MS) {
                        lastAlertTime = now
                        showUnloggedTripAlert(durationMin, distanceM.toInt())
                    }
                }
                isInPotentialTrip = false
                tripStartTime = 0L
                idleStartTime = now
                idleStartLat = location.latitude
                idleStartLng = location.longitude
            }
        } else {
            // 이동 중
            if (!isInPotentialTrip) {
                // 정차 후 이동 시작
                if (idleStartTime > 0 && now - idleStartTime >= IDLE_THRESHOLD_MS) {
                    // 5분 이상 정차했다가 움직임 → 새 트립 시작 가능성
                    isInPotentialTrip = true
                    tripStartTime = now
                    tripStartLat = location.latitude
                    tripStartLng = location.longitude
                    Log.d(TAG, "🚕 잠재적 운행 시작 감지")
                } else {
                    // 짧은 정차 후 이동 → 리셋만
                    idleStartTime = 0L
                }
            }
        }
    }

    private fun showUnloggedTripAlert(durationMin: Int, distanceM: Int) {
        val distanceKm = distanceM / 1000
        Log.d(TAG, "🔔 길빵 알림: ${durationMin}분, ${distanceKm}km")

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("openManualEntry", true)
            putExtra("tripDuration", durationMin)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "callradar_unlogged")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("🚕 방금 ${durationMin}분 이동했어요")
            .setContentText("길빵이었나요? 탭해서 기록하기")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_SOUND)
            .setContentIntent(pendingIntent)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        // 채널 생성
        val channel = NotificationChannel(
            "callradar_unlogged",
            "길빵 감지 알림",
            NotificationManager.IMPORTANCE_HIGH
        )
        nm.createNotificationChannel(channel)
        nm.notify(20, notification)
    }

    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID, "콜레이더 위치 추적",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("콜레이더")
            .setContentText("운행 기록 중")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isTracking = false
        Log.d(TAG, "GPS 추적 종료")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}