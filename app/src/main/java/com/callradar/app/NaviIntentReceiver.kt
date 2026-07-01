package com.callradar.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class NaviIntentReceiver : AccessibilityService() {

    companion object {
        private const val TAG = "CallRadar"
        private const val KAKAO_TAXI = "com.kakao.taxi.driver"
        private const val UBER = "com.ubercab.driver"
        private const val TMONEYGO = "kr.co.tmoney.tia"
        private const val TMONEYGO_NAVI = "com.thinkware.inaviair.tmoney"
        private val TAXI_APPS = setOf(KAKAO_TAXI, UBER, TMONEYGO, TMONEYGO_NAVI)
        private val PLATFORM_NAMES = mapOf(
            KAKAO_TAXI to "카카오T", UBER to "우버",
            TMONEYGO to "티머니고", TMONEYGO_NAVI to "티머니고"
        )
        private const val SERVER_URL = "https://callradar-server.onrender.com"
        private val FARE_PATTERNS = listOf(
            Regex("결제\\s*요금\\s*[：:]?\\s*([0-9,]+)\\s*원"),
            Regex("미터기\\s*요금\\s*[：:]?\\s*([0-9,]+)\\s*원"),
            Regex("총\\s*요금\\s*[：:]?\\s*([0-9,]+)"),
            Regex("₩\\s*([0-9,]+)"),
            Regex("([0-9,]{4,})\\s*원")
        )
        private const val MAX_TRIP_DURATION = 5400000L
        private const val DEST_UPDATE_INTERVAL = 30000L
        private const val TRIGGER_COOLDOWN = 1500L
    }

    private var lastPlatform = "알수없음"
    private var lastNaviApp = ""
    @Volatile private var lastTripId = -1
    private var lastTaxiPlatform = "카카오T"
    @Volatile private var tripStartedAt = 0L
    @Volatile private var tripDestUpdateInFlight = false
    @Volatile private var lastLocalTripId = -1L
    @Volatile private var isSendingTrip = false
    @Volatile private var isProcessingScreen = false
    private var originLat = 0.0
    private var originLng = 0.0
    private var lastClickTime = 0L
    private var lastTriggerTime = 0L

    private val destUpdateHandler = Handler(Looper.getMainLooper())
    private val destUpdateRunnable = object : Runnable {
        override fun run() {
            if (lastTripId > 0 && tripStartedAt > 0) {
                val lat = LocationTrackingService.currentLat
                val lng = LocationTrackingService.currentLng
                if (lat != 0.0 || lng != 0.0) {
                    val dist = distanceMeters(originLat, originLng, lat, lng)
                    if (dist > 300 && lastTripId > 0) {
                        Log.d(TAG, "⏱️ 타이머 갱신 (${dist.toInt()}m)")
                        refreshTripDestination(lastTripId, lat, lng)
                    }
                }
                destUpdateHandler.postDelayed(this, DEST_UPDATE_INTERVAL)
            }
        }
    }

    private fun startDestUpdateTimer() {
        destUpdateHandler.removeCallbacks(destUpdateRunnable)
        destUpdateHandler.postDelayed(destUpdateRunnable, DEST_UPDATE_INTERVAL)
    }
    private fun stopDestUpdateTimer() { destUpdateHandler.removeCallbacks(destUpdateRunnable) }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0; val dLat = Math.toRadians(lat2-lat1); val dLng = Math.toRadians(lng2-lng1)
        val a = Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLng/2)*Math.sin(dLng/2)
        return r*2*Math.atan2(Math.sqrt(a),Math.sqrt(1-a))
    }

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            packageNames = TAXI_APPS.toTypedArray()
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        Log.d(TAG, "NaviIntentReceiver v6 연결됨")
        sendDebugLog("SERVICE", "v6 연결됨")
        Thread { Thread.sleep(5000); LocalTripDatabase.getInstance(this).syncPendingTrips(this) }.start()
        try { startForegroundService(Intent(this, LocationTrackingService::class.java)) }
        catch (e: Exception) { Log.e(TAG, "GPS 시작 실패: ${e.message}") }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in TAXI_APPS) return
        val now = System.currentTimeMillis()

        if (lastTripId > 0 && tripStartedAt > 0 && now - tripStartedAt > MAX_TRIP_DURATION) {
            Log.d(TAG, "트립 90분 초과, 강제 마감")
            sendDebugLog("FORCE_END", "#$lastTripId 90분 초과")
            finalizeCurrentTrip(0)
        }

        lastTaxiPlatform = PLATFORM_NAMES[pkg] ?: "카카오T"
        lastPlatform = lastTaxiPlatform

        // ★ 1순위: 클릭 이벤트
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedText = ((event.contentDescription?.toString() ?: "") +
                " " + (event.text?.joinToString(" ") ?: "")).trim()
            if (clickedText.isBlank()) return
            if (now - lastClickTime < 1000) return
            lastClickTime = now

            Log.d(TAG, "🖱️ 클릭: '$clickedText' ($lastPlatform)")

            if (clickedText.contains("승객탑승") || clickedText.contains("승객 탑승")) {
                sendDebugLog("CLICK_START", "$lastPlatform | $clickedText")
                Log.d(TAG, "🟢 클릭→운행 시작!")
                startTripIfNeeded()
                return
            }

            if (clickedText.contains("승객하차") || clickedText.contains("승객 하차") ||
                clickedText.contains("요금입력하기") || clickedText.contains("요금 입력하기") ||
                clickedText.contains("하차 완료") || clickedText.contains("결제요청") || clickedText.contains("결제 요청")) {
                sendDebugLog("CLICK_END", "$lastPlatform | $clickedText")
                Log.d(TAG, "🔴 클릭→운행 종료!")
                if (lastTripId > 0) {
                    val fare = tryExtractFareFromScreen()
                    finalizeCurrentTrip(fare)
                }
                return
            }
        }

        // ★ 2순위: 화면 텍스트 분석
        if (now - lastTriggerTime < TRIGGER_COOLDOWN) return
        lastTriggerTime = now
        Handler(mainLooper).postDelayed({ analyzeScreen(pkg) }, 500)
    }

    private fun analyzeScreen(pkg: String) {
        if (isProcessingScreen) return
        isProcessingScreen = true
        try {
            val root = rootInActiveWindow ?: return
            val lines = mutableListOf<String>()
            fun traverse(node: android.view.accessibility.AccessibilityNodeInfo?) {
                node ?: return
                node.text?.toString()?.trim()?.let { if (it.isNotEmpty()) lines.add(it) }
                node.contentDescription?.toString()?.trim()?.let { if (it.isNotEmpty()) lines.add(it) }
                for (i in 0 until node.childCount) traverse(node.getChild(i))
            }
            traverse(root)
            val allText = lines.joinToString("\n")

            if (allText.contains("라이더") && allText.contains("평가")) return

            // 결제 완료 화면 → 트립 마감
            if (lastTripId > 0) {
                val isCompletion = allText.contains("자동결제 완료") ||
                    allText.contains("결제 요금") || allText.contains("영수증") ||
                    allText.contains("요금 결제 요청") ||
                    allText.contains("탑승한 손님은 어떠셨나요") ||
                    allText.contains("입력하신 요금이 맞습니까") ||
                    (pkg == UBER && allText.contains("운행 완료"))
                if (isCompletion) {
                    val fare = extractFare(lines)
                    sendDebugLog("SCREEN_END", "$lastPlatform | ${fare}원 | 결제화면감지")
                    Log.d(TAG, "✅ 결제 화면 감지(백업), 요금: ${fare}원")
                    finalizeCurrentTrip(fare)
                    return
                }
            }

            // 유령기록 방지
            val isIgnoreScreen = allText.contains("콜 대기") || allText.contains("배차") ||
                allText.contains("공지") || allText.contains("미션") ||
                allText.contains("Samsung") || allText.contains("카카오톡") ||
                allText.contains("콜 잡기") || allText.contains("호출") ||
                allText.contains("홈 화면") || allText.contains("설정")
            if (isIgnoreScreen) return

            // ★ 카카오T 전용
            if (pkg == KAKAO_TAXI) {
                val kakaoActive = (allText.contains("승객탑승") || (allText.contains("길안내") && allText.contains("탑승")))
                    && !allText.contains("콜 대기") && !allText.contains("배차") && !allText.contains("콜 잡기")
                    && !allText.contains("손님 위치") && !allText.contains("운행 중단")
                if (kakaoActive && lastTripId <= 0) {
                    sendDebugLog("KAKAO_START", "화면텍스트 감지")
                    Log.d(TAG, "🟢 카카오T 화면→운행 시작!")
                    startTripIfNeeded()
                    return
                }
                if (lastTripId > 0 && (allText.contains("승객하차") || allText.contains("요금입력하기") ||
                    allText.contains("하차 완료") || allText.contains("자동결제") || allText.contains("결제 요금") ||
                    allText.contains("요금 결제 요청") || allText.contains("탑승한 손님은 어떠셨나요") ||
                    allText.contains("입력하신 요금이 맞습니까"))) {
                    val fare = extractFare(lines)
                    sendDebugLog("KAKAO_END", "${fare}원")
                    Log.d(TAG, "🔴 카카오T 화면→종료! ${fare}원")
                    finalizeCurrentTrip(fare)
                    return
                }
                if (lastTripId > 0) {
                    val lat = LocationTrackingService.currentLat; val lng = LocationTrackingService.currentLng
                    if (lat != 0.0 || lng != 0.0) {
                        val dist = distanceMeters(originLat, originLng, lat, lng)
                        if (dist > 300 && PLATFORM_NAMES[pkg] == lastTaxiPlatform) refreshTripDestination(lastTripId, lat, lng)
                    }
                }
                return
            }

            // ★ 우버/티머니: GPS 갱신만
            val isActiveCall = when (pkg) {
                UBER -> allText.contains("탑승 완료") || allText.contains("승객 탑승") ||
                    allText.contains("운행 시작") || allText.contains("요금 입력하기") ||
                    (allText.contains("목적지") && allText.contains("도착"))
                TMONEYGO, TMONEYGO_NAVI -> (allText.contains("출발지 길안내") || allText.contains("목적지 길안내") ||
                    allText.contains("승객 탑승")) && !allText.contains("밀어서 운행종료")
                else -> false
            }
            if (!isActiveCall) return

            if (lastTripId > 0) {
                val lat = LocationTrackingService.currentLat; val lng = LocationTrackingService.currentLng
                if (lat != 0.0 || lng != 0.0) {
                    val dist = distanceMeters(originLat, originLng, lat, lng)
                    if (dist > 300 && PLATFORM_NAMES[pkg] == lastTaxiPlatform) refreshTripDestination(lastTripId, lat, lng)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "화면 분석 오류: ${e.message}")
        } finally {
            isProcessingScreen = false
        }
    }

    private fun startTripIfNeeded() {
        if (lastTripId > 0) return
        val lat = LocationTrackingService.currentLat
        val lng = LocationTrackingService.currentLng
        val locationAge = System.currentTimeMillis() - LocationTrackingService.lastLocationTime
        if (lat == 0.0 && lng == 0.0) { sendDebugLog("GPS_FAIL", "GPS 없음"); Log.d(TAG, "GPS 없음"); return }
        if (locationAge > 5 * 60 * 1000L) { sendDebugLog("GPS_FAIL", "GPS 스테일 ${locationAge/1000}초"); Log.d(TAG, "GPS 스테일"); return }
        createNewTripWithGps(lat, lng)
    }

    private fun tryExtractFareFromScreen(): Int {
        try {
            val root = rootInActiveWindow ?: return 0
            val lines = mutableListOf<String>()
            fun traverse(node: android.view.accessibility.AccessibilityNodeInfo?) {
                node ?: return
                node.text?.toString()?.trim()?.let { if (it.isNotEmpty()) lines.add(it) }
                for (i in 0 until node.childCount) traverse(node.getChild(i))
            }
            traverse(root)
            return extractFare(lines)
        } catch (e: Exception) { return 0 }
    }

    private fun createNewTripWithGps(lat: Double, lng: Double) {
        if (isSendingTrip || lastTripId > 0) return
        isSendingTrip = true
        tripStartedAt = System.currentTimeMillis()
        originLat = lat; originLng = lng
        Thread {
            try {
                val oName = reverseGeocode(lat, lng) ?: ""
                val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                val userId = prefs.getString("user_id", null)
                val now = Date()
                val startedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.KOREA).format(now)
                val db = LocalTripDatabase.getInstance(this)
                val localId = db.savePending(userId, lastPlatform, oName, oName, lat, lng, lat, lng, startedAt)
                lastLocalTripId = localId
                try {
                    val json = JSONObject().apply {
                        put("user_id", userId); put("platform", lastPlatform); put("naviApp", lastNaviApp)
                        put("depLat", lat); put("depLng", lng); put("destName", oName)
                        put("destLat", lat); put("destLng", lng); put("originName", oName)
                        put("time", SimpleDateFormat("HH:mm", Locale.KOREA).format(now))
                        put("dayOfWeek", SimpleDateFormat("E", Locale.KOREA).format(now))
                        put("date", SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(now))
                        put("timestamp", tripStartedAt)
                    }
                    val conn = (URL("$SERVER_URL/api/trips").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"; setRequestProperty("Content-Type", "application/json")
                        doOutput = true; connectTimeout = 30000; readTimeout = 30000
                    }
                    conn.outputStream.write(json.toString().toByteArray())
                    val resJson = JSONObject(conn.inputStream.bufferedReader().readText())
                    lastTripId = resJson.optInt("id", -1)
                    if (lastTripId > 0) {
                        db.markSynced(localId, lastTripId)
                        sendDebugLog("TRIP_START", "#$lastTripId | $oName | $lastPlatform")
                        Log.d(TAG, "🚕 트립 생성: #$lastTripId | $oName | $lastPlatform")
                    }
                    conn.disconnect()
                } catch (e: Exception) { Log.e(TAG, "서버 전송 실패: ${e.message}"); lastTripId = -1 }
                Handler(Looper.getMainLooper()).post { startDestUpdateTimer() }
            } catch (e: Exception) { Log.e(TAG, "트립 생성 실패: ${e.message}") }
            finally { isSendingTrip = false }
        }.start()
    }

    private fun refreshTripDestination(tripId: Int, lat: Double, lng: Double) {
        if (tripDestUpdateInFlight) return
        tripDestUpdateInFlight = true
        Thread {
            try {
                val destName = reverseGeocode(lat, lng)
                if (destName.isNullOrEmpty()) return@Thread
                val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                val userId = prefs.getString("user_id", null)
                val json = JSONObject().apply { put("user_id", userId); put("destination", destName); put("dest_lat", lat); put("dest_lng", lng) }
                val conn = (URL("$SERVER_URL/api/trips/$tripId").openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 30000; readTimeout = 30000
                }
                conn.outputStream.write(json.toString().toByteArray()); conn.responseCode
                Log.d(TAG, "📍 목적지 갱신: #$tripId -> $destName"); conn.disconnect()
            } catch (e: Exception) { Log.e(TAG, "목적지 갱신 실패: ${e.message}") }
            finally { tripDestUpdateInFlight = false }
        }.start()
    }

    private fun finalizeCurrentTrip(fare: Int) {
        val tripId = lastTripId; if (tripId <= 0) return
        lastTripId = -1
        stopDestUpdateTimer()
        val lat = LocationTrackingService.currentLat; val lng = LocationTrackingService.currentLng
        Thread {
            try {
                val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                val userId = prefs.getString("user_id", null)
                val json = JSONObject().apply {
                    put("user_id", userId)
                    if (fare > 0) put("fare", fare)
                    if (lat != 0.0 || lng != 0.0) {
                        val dist = distanceMeters(originLat, originLng, lat, lng)
                        if (dist > 300) {
                            val destName = reverseGeocode(lat, lng)
                            if (!destName.isNullOrEmpty()) { put("destination", destName); put("dest_lat", lat); put("dest_lng", lng) }
                        }
                    }
                }
                val conn = (URL("$SERVER_URL/api/trips/$tripId").openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 30000; readTimeout = 30000
                }
                conn.outputStream.write(json.toString().toByteArray()); conn.responseCode
                sendDebugLog("TRIP_END", "#$tripId | ${fare}원")
                Log.d(TAG, "✅ 트립 마감: #$tripId (요금: ${fare}원)"); conn.disconnect()
                if (lastLocalTripId > 0) { val destName = reverseGeocode(lat, lng) ?: ""; LocalTripDatabase.getInstance(this).updateDestination(lastLocalTripId, destName, lat, lng, fare) }
            } catch (e: Exception) { Log.e(TAG, "트립 마감 실패: ${e.message}") }
            finally { synchronized(this) { lastLocalTripId = -1; tripStartedAt = 0L; originLat = 0.0; originLng = 0.0 } }
        }.start()
    }

    private fun sendDebugLog(event: String, detail: String) {
        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", null) ?: return
        Thread {
            try {
                val json = JSONObject().apply { put("user_id", userId); put("event", event); put("detail", detail) }
                val conn = (URL("$SERVER_URL/api/debug/log").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 5000
                }
                conn.outputStream.write(json.toString().toByteArray()); conn.responseCode; conn.disconnect()
            } catch (e: Exception) { }
        }.start()
    }

    private fun reverseGeocode(lat: Double, lng: Double): String? {
        return try {
            val conn = (URL("https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lng&zoom=16&addressdetails=1&accept-language=ko").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 10000; setRequestProperty("User-Agent", "CallRadar/1.0")
            }
            val raw = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            val json = JSONObject(raw); val addr = json.optJSONObject("address")
            val district = addr?.optString("borough", addr.optString("suburb", addr.optString("quarter", ""))) ?: ""
            val neighbourhood = addr?.optString("neighbourhood", addr.optString("residential", "")) ?: ""
            val city = addr?.optString("city", addr.optString("county", "")) ?: ""
            listOf(district, neighbourhood).filter { it.isNotEmpty() }.distinct().joinToString(" ").ifEmpty { city }
        } catch (e: Exception) { Log.e(TAG, "역지오코딩 실패: ${e.message}"); null }
    }

    private fun extractFare(lines: List<String>): Int {
        val allText = lines.joinToString(" ")
        for (pattern in FARE_PATTERNS) {
            val match = pattern.find(allText)?.groupValues?.get(1)
            if (!match.isNullOrEmpty()) { val amount = match.replace(",", "").toIntOrNull() ?: 0; if (amount in 1000..500000) return amount }
        }
        return 0
    }

    override fun onInterrupt() { Log.d(TAG, "NaviIntentReceiver 중단") }
    override fun onDestroy() { stopDestUpdateTimer(); super.onDestroy() }
}
