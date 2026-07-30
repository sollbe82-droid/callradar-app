package com.callradar.app

// ===== FloatingTripService v1 (2026-07-13) =====
// 플로팅 오버레이 버튼: 다른 앱 위에 떠서 기사가 직접 탑승/완료 누름
// 탑승 → GPS 출발위치 스냅샷 / 완료 → GPS 도착위치 + 운행 자동생성(서버 전송)
// 접근성 자동기록과 독립. 기사 능동 조작이라 심사 안전. 백그라운드 추적 안 함(누를 때만 위치 1회)

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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

class FloatingTripService : Service() {

    private val SERVER_URL = "https://callradar-server.onrender.com"
    private lateinit var windowManager: WindowManager
    private var floatingView: TextView? = null
    private lateinit var fusedClient: FusedLocationProviderClient

    // 운행 상태
    private var isRiding = false
    private var startLat = 0.0
    private var startLng = 0.0
    private var startAddr = ""
    private var startTime = ""

    // [안전장치] 완료 후 3초 취소 대기 상태
    private var pendingConfirm = false
    private var pendingDestLat = 0.0
    private var pendingDestLng = 0.0
    private var pendingDestAddr = ""
    private val confirmHandler = android.os.Handler(Looper.getMainLooper())
    private var confirmRunnable: Runnable? = null
    private val MIN_DISTANCE_M = 100f  // 최소 이동거리(제자리 연타 방지)
    private val MAX_RIDE_MS = 3L * 60 * 60 * 1000  // [v2] 3시간 넘게 열린 운행 = '깜빡 잊고 안 끈' 스톨 → 복원·기록 안 함
    private fun parseUtc(s: String): Long = try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()); sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.parse(s)?.time ?: 0L
    } catch (e: Exception) { 0L }
    // [v2] 이 폰의 고유 기기 ID — 운행 상태에 찍어 '다른 폰 운행'을 절대 이어받지 않게 (투폰 독립 보장)
    private fun deviceId(): String = try { android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "" } catch (e: Exception) { "" }

    // [v2] 운행중 은은한 펄스(밝기 호흡) — "지금 기록 중, 끝나면 눌러요" 신호. 더보기에서 끌 수 있음.
    private var pulseOn = false
    private var pulsePhase = 0.0
    private val pulseHandler = android.os.Handler(Looper.getMainLooper())
    private var pulseRunnable: Runnable? = null

    private fun userId(): String {
        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        return prefs.getString("user_id", "") ?: ""
    }

    override fun onBind(intent: Intent?): IBinder? = null


    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        try { getSharedPreferences("callradar_prefs", MODE_PRIVATE).edit().putString("overlay_started", java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(java.util.Date())).apply() } catch (e: Exception) {}
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        // 플로팅 버튼(원형 텍스트뷰)
        val btn = TextView(this).apply {
            text = "시작"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = ovalBg("#F59E0B")
            setPadding(0, 0, 0, 0)
        }
        floatingView = btn

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val sizePx = (64 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // [v2] 기본 위치를 오른쪽·중앙으로 — 좌상단 콘텐츠(레이더 맛집 등)와 겹치지 않게. 드래그로 이동 가능.
            x = (resources.displayMetrics.widthPixels - sizePx - (16 * resources.displayMetrics.density)).toInt().coerceAtLeast(0)
            y = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        }

        // 드래그 이동 + 탭 구분
        var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        val lpHandler = android.os.Handler(Looper.getMainLooper()); var longPressed = false; var lpRun: Runnable? = null
        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    touchX = event.rawX; touchY = event.rawY; moved = false
                    longPressed = false
                    lpRun = Runnable { if (!moved) { longPressed = true; shareCall() } }
                    lpHandler.postDelayed(lpRun!!, 1000)   // [v19] 600→1000ms: 탭이 실수로 공유(길게누름)로 안 넘어가게
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt(); val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 28 || kotlin.math.abs(dy) > 28) { moved = true; lpRun?.let { lpHandler.removeCallbacks(it) } }   // [v19] 10→28px: 손가락 미세이동에도 탭 인식
                    params.x = initX + dx; params.y = initY + dy
                    windowManager.updateViewLayout(btn, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    lpRun?.let { lpHandler.removeCallbacks(it) }; if (!moved && !longPressed) onButtonTap()
                    true
                }
                else -> false
            }
        }

        try { windowManager.addView(btn, params) } catch (e: Exception) {}
        restoreRideState()
    }

    // 서비스가 죽어도 시스템이 되살리도록 (운행중 상태는 prefs에서 복원)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // [v18] 화면잠금·백그라운드 중 서비스가 죽어도 운행버튼/상태가 사라지지 않게: 운행중 포그라운드 유지 + 상태 저장
    private fun saveRideState() {
        try { getSharedPreferences("callradar_prefs", MODE_PRIVATE).edit()
            .putBoolean("ride_active", isRiding)
            .putString("ride_startTime", startTime)
            .putString("ride_startAddr", startAddr)
            .putString("ride_startLat", startLat.toString())
            .putString("ride_startLng", startLng.toString())
            .putString("ride_device", deviceId())   // [v2] 이 폰 기기 ID 각인 → 다른 폰이 이어받지 못하게
            .apply() } catch (e: Exception) {}
    }
    private fun clearRideState() {
        try { getSharedPreferences("callradar_prefs", MODE_PRIVATE).edit().putBoolean("ride_active", false).apply() } catch (e: Exception) {}
    }
    private fun restoreRideState() {
        try {
            val p = getSharedPreferences("callradar_prefs", MODE_PRIVATE)
            if (p.getBoolean("ride_active", false)) {
                val savedDev = p.getString("ride_device", "") ?: ""
                val savedStart = p.getString("ride_startTime", "") ?: ""
                val startMs = parseUtc(savedStart)
                // [v2] 복원 거부 조건: (1) 다른 폰에서 시작된 운행(기기 ID 불일치) → 투폰 독립 보장,
                //      (2) 시각 파싱 불가/3시간+ 열린 스톨 → 유령 운행·금액없는 기록 방지
                val rejectRestore = (savedDev.isNotEmpty() && savedDev != deviceId()) ||
                    startMs == 0L || System.currentTimeMillis() - startMs > MAX_RIDE_MS
                if (rejectRestore) {
                    clearRideState()
                } else {
                    isRiding = true
                    startTime = savedStart
                    startAddr = p.getString("ride_startAddr", "") ?: ""
                    startLat = p.getString("ride_startLat", "0.0")?.toDoubleOrNull() ?: 0.0
                    startLng = p.getString("ride_startLng", "0.0")?.toDoubleOrNull() ?: 0.0
                    if (startAddr.isNotBlank() && startAddr != "미상") updateButtonSmall("운행중\n$startAddr", "#10B981") else updateButton("운행중", "#10B981")
                    startPulse()               // [v2] 복원 시에도 운행중 펄스
                    startLocationForeground()  // 운행중 유지 → 잠금 중에도 서비스가 안 죽음
                }
            }
        } catch (e: Exception) {}
    }

    // 버튼 탭: 탑승 → 완료(취소대기 3초) → 확정
    private fun onButtonTap() {
        // [안전장치 3] 취소 대기중 다시 누르면 → 취소
        if (pendingConfirm) {
            pendingConfirm = false
            confirmRunnable?.let { confirmHandler.removeCallbacks(it) }
            isRiding = false
            stopPulse()
            stopLocationForeground(); clearRideState()
            updateButton("시작", "#F59E0B")
            toast("운행 취소됨")
            return
        }

        if (!isRiding) {
            // ★탑승: 버튼 즉시 "완료"로 전환 (GPS 안 기다림 = 백그라운드서도 즉각 반응)
            isRiding = true
            startTime = utcNow()
            startLat = 0.0; startLng = 0.0; startAddr = ""
            updateButton("운행중", "#10B981")
            startPulse()                // [v2] 운행중 은은한 펄스
            startLocationForeground()   // [v18] 운행 내내 포그라운드 유지 → 화면잠금에도 버튼/서비스 유지
            saveRideState()
            toast("시작 — 출발 확인 중 · 버튼 길게 누르면 공유")
            // GPS는 백그라운드에서 따로 잡아 채움 (늦게 와도 됨). 잡히면 출발지 동을 버튼에 표시
            captureLocation { lat, lng, addr ->
                startLat = lat; startLng = lng; startAddr = addr
                saveRideState()
                if (lat != 0.0 && isRiding && !pendingConfirm) {
                    // 출발지 동 있으면 "완료\n양재2동" 두 줄(글자 작게), 없으면 "완료✓"
                    if (addr.isNotBlank() && addr != "미상") updateButtonSmall("운행중\n$addr", "#10B981")
                    else updateButton("운행중", "#10B981")
                }
            }
        } else {
            // ★완료: 버튼 즉시 "취소?"로 전환 (GPS 안 기다림)
            stopPulse()                 // [v2] 운행 종료 → 펄스 멈춤
            pendingConfirm = true
            updateButton("취소", "#EF4444")
            toast("종료 — 잠시 후 기록 (누르면 취소)")
            pendingDestLat = 0.0; pendingDestLng = 0.0; pendingDestAddr = ""
            // 3초 취소창 — 그 안에 다시 누르면 취소, 아니면 확정
            confirmRunnable = Runnable {
                if (pendingConfirm) {
                    pendingConfirm = false
                    // ★확정 시점에 도착 GPS를 새로 잡음 (여유있게, 최대 10초). 잡히면 기록
                    updateButton("기록중", "#3B82F6")
                    toast("도착 위치 확인 중...")
                    captureLocation { lat, lng, addr ->
                        pendingDestLat = lat; pendingDestLng = lng; pendingDestAddr = addr
                        // [안전장치 1] 이동거리 체크 — 출발·도착 GPS 둘 다 확실할 때만
                        if (startLat != 0.0 && lat != 0.0) {
                            val dist = FloatArray(1)
                            Location.distanceBetween(startLat, startLng, lat, lng, dist)
                            if (dist[0] < MIN_DISTANCE_M) {
                                isRiding = false
                                stopLocationForeground(); clearRideState()
                                updateButton("시작", "#F59E0B")
                                toast("이동거리가 짧아 기록 안 함")
                                return@captureLocation
                            }
                        }
                        // [v2] 비정상 장시간 운행(3h+) = 깜빡 잊고 안 끈 것 → 기록 안 함(금액없는 유령 운행 방지)
                        val sMs = parseUtc(startTime)
                        if (sMs > 0L && System.currentTimeMillis() - sMs > MAX_RIDE_MS) {
                            isRiding = false
                            stopLocationForeground(); clearRideState()
                            updateButton("시작", "#F59E0B")
                            toast("운행이 너무 길어(3시간+) 기록 안 함 — 필요하면 수동으로 추가하세요")
                            return@captureLocation
                        }
                        // GPS 하나라도 없으면 체크 건너뛰고 기록(아예 안하는것보단), 있으면 거리 통과분만
                        createTrip(startLat, startLng, startAddr, startTime, lat, lng, addr)
                        isRiding = false
                        stopLocationForeground(); clearRideState()
                        updateButton("시작", "#F59E0B")
                        toast("운행 기록됨")
                    }
                }
            }
            confirmHandler.postDelayed(confirmRunnable!!, 3000)
        }
    }

    // [v2] 플로팅 버튼 롱프레스 → 콜 화면 공유. 모드는 더보기에서 선택(share_mode).
    //   screenshot: 화면 캡처 → 콜 팝업만 크롭 → 워터마크 → 이미지 공유
    //   text: 화면 캡처 → 콜 팝업 OCR(한글) → 텍스트만 공유
    //   off: 아무 것도 안 함
    //  스샷/텍스트 둘 다 화면 캡처 동의가 필요 → ScreenCapturePermissionActivity로 진입.
    //  ScreenCaptureService가 share_mode를 읽어 이미지/텍스트를 알아서 처리한다.
    private fun shareCall() {
        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val mode = prefs.getString("share_mode", "screenshot") ?: "screenshot"
        if (mode == "off") { toast("공유 꺼짐 — 더보기 > 운행 버튼에서 켜기"); return }
        try {
            ScreenCapturePermissionActivity.start(this)
        } catch (e: Exception) { toast("공유를 시작할 수 없어요") }
    }

    private fun toast(msg: String) {
        floatingView?.post { android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }

    // [v2] 운행중 펄스 시작/정지 (밝기 0.55~1.0 사인 호흡, ~1.2초 주기)
    private fun startPulse() {
        val enabled = getSharedPreferences("callradar_prefs", MODE_PRIVATE).getBoolean("floating_pulse", true)
        if (!enabled || pulseOn) return
        pulseOn = true; pulsePhase = 0.0
        pulseRunnable = object : Runnable {
            override fun run() {
                pulsePhase += 0.16
                val a = (0.55 + 0.45 * ((Math.sin(pulsePhase) + 1) / 2)).toFloat()
                floatingView?.alpha = a
                if (pulseOn) pulseHandler.postDelayed(this, 55)
            }
        }
        pulseHandler.post(pulseRunnable!!)
    }
    private fun stopPulse() {
        pulseOn = false
        pulseRunnable?.let { pulseHandler.removeCallbacks(it) }
        floatingView?.post { floatingView?.alpha = 1f }
    }

    // [v2] 원형 + 흰 외곽 테두리 배경 (상태별 색 채움)
    private fun ovalBg(colorHex: String): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor(colorHex))
            setStroke((3 * resources.displayMetrics.density).toInt(), Color.WHITE)
        }
    }

    private fun updateButton(txt: String, colorHex: String) {
        floatingView?.post {
            floatingView?.textSize = 18f   // 기본 크기 복구 (작은 표시에서 돌아올 때)
            floatingView?.setTextColor(Color.WHITE)
            floatingView?.typeface = android.graphics.Typeface.DEFAULT_BOLD
            floatingView?.text = txt
            floatingView?.background = ovalBg(colorHex)
        }
    }

    // 출발지 동까지 두 줄로 표시할 때 (글자 작게 해서 64dp 안에 들어가게)
    private fun updateButtonSmall(txt: String, colorHex: String) {
        floatingView?.post {
            floatingView?.textSize = 12f   // 두 줄 + 동이름 담기 위해 작게
            floatingView?.setTextColor(Color.WHITE)
            floatingView?.typeface = android.graphics.Typeface.DEFAULT_BOLD
            floatingView?.text = txt
            floatingView?.background = ovalBg(colorHex)
        }
    }

    // GPS 현재 위치 1회 스냅샷 + 주소 변환
    @SuppressLint("MissingPermission")
    private fun captureLocation(cb: (Double, Double, String) -> Unit) {
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            cb(0.0, 0.0, "위치권한없음"); return
        }
        // [location FGS] 위치 캡처 동안만 잠깐 location 포그라운드 승격 → 백그라운드(카카오T·티맵 위)서도 GPS 잡힘. 캡처 끝나면 즉시 해제
        startLocationForeground()
        var done = false
        val emit = { lat: Double, lng: Double ->
            if (!done) {
                done = true
                if (!isRiding) stopLocationForeground()  // [v18] 운행중엔 유지(잠금 대비), 운행 아닐 때만 해제
                thread { val addr = if (lat != 0.0) reverseGeocode(lat, lng) else ""; cb(lat, lng, addr) }
            }
        }
        // ① 먼저 마지막 알려진 위치로 빠르게 채움 (백그라운드서도 즉시 확보되는 경우 많음)
        try {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && !done) emit(loc.latitude, loc.longitude)
            }
        } catch (e: Exception) {}
        // ② 정확한 최신 위치 요청 (오면 위에서 done이라 무시됨, 없었으면 이게 채움)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMaxUpdates(1).build()
        fusedClient.requestLocationUpdates(req, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                fusedClient.removeLocationUpdates(this)
                val loc = result.lastLocation
                if (loc != null) emit(loc.latitude, loc.longitude)
            }
        }, Looper.getMainLooper())
        // ③ 10초 타임아웃 — 그래도 못 잡으면 빈 위치로 (버튼 흐름은 안 막힘)
        confirmHandler.postDelayed({ if (!done) { done = true; if (!isRiding) stopLocationForeground(); cb(0.0, 0.0, "") } }, 10000)
    }

    // ===== location 포그라운드 (위치 캡처 중에만 잠깐) =====
    private val LOC_CHANNEL_ID = "callradar_location"
    private val LOC_NOTI_ID = 3001
    private var locForegroundOn = false
    private fun startLocationForeground() {
        if (locForegroundOn) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(LOC_CHANNEL_ID, "위치 기록", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
                nm.createNotificationChannel(ch)
            }
            val pi = try {
                val i = packageManager.getLaunchIntentForPackage(packageName)
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            } catch (e: Exception) { null }
            val noti = Notification.Builder(this, LOC_CHANNEL_ID)
                .setContentTitle("운행 위치 기록 중")
                .setContentText("출발·도착 위치를 기록하고 있어요")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .apply { if (pi != null) setContentIntent(pi) }
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(LOC_NOTI_ID, noti, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(LOC_NOTI_ID, noti)
            }
            locForegroundOn = true
        } catch (e: Exception) {}
    }
    private fun stopLocationForeground() {
        if (!locForegroundOn) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (e: Exception) {}
        locForegroundOn = false
    }

    // 좌표 → 동 이름
    private fun reverseGeocode(lat: Double, lng: Double): String {
        return try {
            val geo = Geocoder(this, Locale.KOREA)
            @Suppress("DEPRECATION")
            val list = geo.getFromLocation(lat, lng, 3)  // 여러 후보를 받아 그중 동 정보가 있는 걸 찾음(Geocoder가 호출마다 다른 레벨을 줘서 1개만 받으면 구로 떨어지는 문제 완화)
            if (!list.isNullOrEmpty()) {
                fun isDong(s: String?) = !s.isNullOrBlank() && (s.endsWith("동") || s.endsWith("읍") || s.endsWith("면"))
                // 모든 결과의 모든 주소 필드를 뒤져 "동/읍/면"으로 끝나는 첫 값을 찾음
                val dong = list.asSequence()
                    .flatMap { sequenceOf(it.subLocality, it.thoroughfare, it.subThoroughfare, it.locality, it.subAdminArea) }
                    .firstOrNull { isDong(it) }
                if (dong != null) return dong
                // 동이 하나도 없으면 getFromLocation 이 준 전체 주소(getAddressLine)에서 동 토큰을 탐색
                val fromLine = list.asSequence()
                    .mapNotNull { it.getAddressLine(0) }
                    .flatMap { it.split(" ").asSequence() }
                    .firstOrNull { isDong(it) }
                if (fromLine != null) return fromLine
                // 그래도 없으면 구/시라도, 그것도 없으면 미상
                val a = list[0]
                a.subLocality ?: a.locality ?: a.subAdminArea ?: "미상"
            } else "미상"
        } catch (e: Exception) { "미상" }
    }

    private fun utcNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    // 운행 생성 → 서버 전송 (source=gps, editable=true → 기사가 나중에 수정)
    private fun createTrip(oLat: Double, oLng: Double, oAddr: String, sTime: String, dLat: Double, dLng: Double, dAddr: String) {
        val uid = userId()
        if (uid.isEmpty()) return
        // 서버는 destName 필수 — GPS/주소 못잡아 비면 기본값으로 채움(저장 실패 방지)
        val originName = if (oAddr.isBlank()) "출발(미상)" else oAddr
        val destName = if (dAddr.isBlank()) "도착(미상)" else dAddr
        thread {
            try {
                val json = JSONObject().apply {
                    put("user_id", uid)
                    put("originName", originName)
                    put("destName", destName)
                    put("platform", "길빵/예약")
                    put("payment_type", "cash")   // GPS 운행은 기본 현금(기사가 수정)
                    put("source", "gps")
                    put("origin_lat", oLat); put("origin_lng", oLng)
                    put("dest_lat", dLat); put("dest_lng", dLng)
                    put("started_at", sTime)
                    put("ended_at", utcNow())
                }
                val conn = (URL("$SERVER_URL/api/trips/manual").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true; connectTimeout = 8000; readTimeout = 8000
                }
                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.flush() }
                val code = conn.responseCode
                val body = if (code in 200..299) try { conn.inputStream.bufferedReader().readText() } catch (e: Exception) { "" } else ""
                val tripId = try { JSONObject(body).optInt("id", 0) } catch (e: Exception) { 0 }
                // [v18] 완료 후 금액 팝업 (설정 켬일 때) — 앱 안 열고 요금·플랫폼 즉시 입력
                if (tripId > 0 && getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getBoolean("quick_entry_enabled", true)) {
                    confirmHandler.post {
                        try {
                            startActivity(Intent(this, QuickEntryActivity::class.java).apply { putExtra("trip_id", tripId); putExtra("dest", destName); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { getSharedPreferences("callradar_prefs", MODE_PRIVATE).edit().putString("overlay_died", java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(java.util.Date()) + " (onDestroy)").apply() } catch (e: Exception) {}
        confirmRunnable?.let { confirmHandler.removeCallbacks(it) }
        stopPulse()
        try { floatingView?.let { windowManager.removeView(it) } } catch (e: Exception) {}
    }

    // [퇴근/앱종료] 최근앱에서 콜레이더를 스와이프로 지우면 버튼은 사라지되,
    // floating_on 설정은 유지 → 앱 다시 켜면 MainActivity onCreate에서 자동 복귀
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try { floatingView?.let { windowManager.removeView(it) } } catch (e: Exception) {}
        stopSelf()
    }
}
