package com.callradar.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.util.Locale

/**
 * 도착 예정 알림 서비스
 *
 * 작동 방식:
 * 1. NaviIntentReceiver가 목적지 감지 → 이 서비스 시작
 * 2. GPS로 목적지까지 거리 실시간 계산
 * 3. 도착 5분 전 → 시스템 알림 + TTS 음성 안내
 * 4. 기사가 알림 탭 → MainActivity AI 추천 화면 열림
 * 5. 백그라운드에서 항상 작동
 */
class ArrivalNotificationService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "CallRadar"
        const val CHANNEL_AI = "callradar_ai_alert"
        const val CHANNEL_FG = "callradar_foreground"
        const val NOTIF_FG_ID = 10
        const val NOTIF_AI_ID = 11

        // Intent 키
        const val KEY_DEST_NAME = "destName"
        const val KEY_DEST_LAT = "destLat"
        const val KEY_DEST_LNG = "destLng"

        // 추천 핫스팟 (실제로는 서버에서 받아옴)
        val HOTSPOTS = listOf(
            HotSpot("가산디지털단지", 37.4796, 126.8820, 78, "장거리 뜰 수 있음"),
            HotSpot("구로디지털단지", 37.4851, 126.8990, 65, "IT기업 밀집"),
            HotSpot("강남역", 37.4979, 127.0276, 87, "항상 핫스팟"),
            HotSpot("여의도", 37.5219, 126.9245, 72, "금융권 퇴근 콜"),
            HotSpot("홍대입구", 37.5571, 126.9238, 68, "야간 수요"),
        )
    }

    data class HotSpot(
        val name: String,
        val lat: Double,
        val lng: Double,
        val callRate: Int,
        val reason: String
    )

    // TTS 엔진
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // GPS
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // 목적지 정보
    private var destName = ""
    private var destLat = 0.0
    private var destLng = 0.0

    // 알림 상태
    private var notifiedAt5Min = false
    private var notifiedAtArrival = false

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 목적지 정보 받기
        destName = intent?.getStringExtra(KEY_DEST_NAME) ?: ""
        destLat  = intent?.getDoubleExtra(KEY_DEST_LAT, 0.0) ?: 0.0
        destLng  = intent?.getDoubleExtra(KEY_DEST_LNG, 0.0) ?: 0.0

        Log.d(TAG, "🎯 도착알림 서비스 시작: $destName ($destLat, $destLng)")

        // 포그라운드 서비스로 실행 (백그라운드 유지)
        startForeground(NOTIF_FG_ID, buildForegroundNotification())

        // GPS 트래킹 시작
        startLocationTracking()

        return START_STICKY
    }

    // ── GPS 트래킹 ────────────────────────────────────────────
    private fun startLocationTracking() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                checkArrival(loc)
            }
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 10000L // 10초마다
        ).setMinUpdateDistanceMeters(50f).build()

        try {
            fusedLocationClient.requestLocationUpdates(
                request, locationCallback, Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "위치 권한 없음")
        }
    }

    // ── 도착 여부 체크 ────────────────────────────────────────
    private fun checkArrival(currentLoc: Location) {
        if (destLat == 0.0 || destLng == 0.0) return

        val results = FloatArray(1)
        Location.distanceBetween(
            currentLoc.latitude, currentLoc.longitude,
            destLat, destLng,
            results
        )
        val distanceM = results[0]
        val speedKmh = currentLoc.speed * 3.6f

        // 예상 도착 시간 계산 (분)
        val etaMin = if (speedKmh > 5) (distanceM / (speedKmh * 1000 / 60)).toInt() else 99

        Log.d(TAG, "📍 목적지까지: ${distanceM.toInt()}m / 예상 ${etaMin}분 / 속도 ${speedKmh.toInt()}km/h")

        when {
            // 도착 5분 전
            etaMin in 3..6 && !notifiedAt5Min -> {
                notifiedAt5Min = true
                onNearArrival(distanceM)
            }
            // 도착 (200m 이내)
            distanceM < 200 && !notifiedAtArrival -> {
                notifiedAtArrival = true
                onArrived()
            }
        }
    }

    // ── 도착 5분 전 알림 (현재 비활성화: 핫스팟 데이터 정확도 부족) ──
    private fun onNearArrival(distanceM: Float) {
        Log.d(TAG, "도착 5분 전 (알림 비활성화 상태)")
        notifiedAt5Min = true
    }

    // ── 도착 알림 ─────────────────────────────────────────────
    private fun onArrived() {
        Log.d(TAG, "도착: $destName")

        speakAfterDelay("$destName 도착했습니다.", 300)

        showSimpleArrivalNotification()

        // 일정 시간 후 서비스 종료
        android.os.Handler(Looper.getMainLooper()).postDelayed({
            stopSelf()
        }, 30000)
    }

    // ── 단순 도착 알림 (핫스팟 추천 없음) ────────────────────
    private fun showSimpleArrivalNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_AI)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("✅ $destName 도착!")
            .setContentText("운행 기록이 저장됐어요")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_AI_ID, notification)
        Log.d(TAG, "도착 알림 표시: $destName")
    }

    // ── TTS 음성 멘트 빌드 ────────────────────────────────────
    private fun buildTtsScript(
        dest: String,
        best: HotSpot,
        second: HotSpot?
    ): String {
        val sb = StringBuilder()
        sb.append("${dest} 도착 예정입니다. ")

        if (best.callRate >= 70) {
            sb.append("${best.name}에서 장거리 뜰 수 있습니다. ")
            sb.append("콜 확률 ${best.callRate}퍼센트입니다. ")
        } else {
            sb.append("현재 콜 확률이 낮아요. ")
            sb.append("${best.name}으로 이동을 추천합니다. ")
        }

        second?.let {
            sb.append("없으면 ${it.name}에서 ")
            if (it.callRate >= 60) sb.append("장거리 또는 ")
            sb.append("콜 노리세요.")
        }

        return sb.toString()
    }

    // ── 시스템 알림 표시 ──────────────────────────────────────
    private fun showAiNotification(
        title: String,
        message: String,
        spots: List<HotSpot>
    ) {
        // 탭하면 MainActivity AI 화면으로 이동
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("openTab", "ai")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 큰 알림 (스타일)
        val bigTextStyle = NotificationCompat.BigTextStyle()
            .bigText(
                spots.joinToString("\n") { spot ->
                    "• ${spot.name} 콜 ${spot.callRate}% - ${spot.reason}"
                }
            )

        val notification = NotificationCompat.Builder(this, CHANNEL_AI)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(bigTextStyle)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            // 액션 버튼
            .addAction(
                android.R.drawable.ic_media_play,
                "이동하기",
                pendingIntent
            )
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_AI_ID, notification)

        Log.d(TAG, "🔔 AI 알림 표시: $title")
    }

    // ── TTS ───────────────────────────────────────────────────
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.KOREAN)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA
            Log.d(TAG, "TTS 초기화 완료: $ttsReady")
        }
    }

    private fun speakAfterDelay(text: String, delayMs: Long = 0) {
        android.os.Handler(Looper.getMainLooper()).postDelayed({
            if (ttsReady) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "callradar_tts")
                Log.d(TAG, "🔊 TTS: $text")
            }
        }, delayMs)
    }

    // ── 알림 채널 생성 ────────────────────────────────────────
    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // AI 알림 채널 (중요도 높음)
        NotificationChannel(
            CHANNEL_AI,
            "콜레이더 AI 알림",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "도착 예정 콜 추천 알림"
            enableVibration(true)
            nm.createNotificationChannel(this)
        }

        // 포그라운드 채널 (낮음)
        NotificationChannel(
            CHANNEL_FG,
            "콜레이더 실행 중",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "콜레이더 백그라운드 실행"
            nm.createNotificationChannel(this)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_FG)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("콜레이더 ⚡")
            .setContentText("$destName 도착 예정 · AI 안내 대기 중")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d(TAG, "ArrivalNotificationService 종료")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}