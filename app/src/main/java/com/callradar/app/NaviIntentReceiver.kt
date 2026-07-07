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
            Regex("미터기\\s*요금\\s+([0-9,]+)"),
            Regex("총\\s*요금\\s*[：:]?\\s*([0-9,]+)"),
            Regex("₩\\s*([0-9,]+)"),
            Regex("([0-9,]{4,})\\s*원")
        )
        private const val MAX_TRIP_DURATION = 5400000L
        private const val DEST_UPDATE_INTERVAL = 30000L
    }

    private var lastPlatform = "알수없음"
    private var lastNaviApp = ""
    private var lastSentDest = ""
    private var lastSentTime = 0L
    private var lastTriggerTime = 0L
    @Volatile private var lastTripId = -1
    private var lastTaxiPlatform = "카카오T"
    private var tripPlatform = ""  // 현재 트립이 시작된 플랫폼
    private var lastDetectedFare = 0  // 우버 금액 캐싱
    @Volatile private var tripStartedAt = 0L
    @Volatile private var tripDestUpdateInFlight = false
    @Volatile private var lastLocalTripId = -1L
    @Volatile private var isProcessingTaxiScreen = false
    @Volatile private var isSendingTrip = false
    private var clickHandledUntil = 0L
    private var originLat = 0.0
    private var originLng = 0.0
    private val TRIGGER_COOLDOWN = 1000L
    private val CLICK_SUPPRESS_WINDOW = 2000L

    // 목적지 자동 갱신 타이머
    private val destUpdateHandler = Handler(Looper.getMainLooper())
    private val destUpdateRunnable = object : Runnable {
        override fun run() {
            if (lastTripId > 0 && tripStartedAt > 0) {
                val lat = LocationTrackingService.currentLat
                val lng = LocationTrackingService.currentLng
                if (lat != 0.0 || lng != 0.0) {
                    val dist = distanceMeters(originLat, originLng, lat, lng)
                    if (dist > 300) {
                        Log.d(TAG, "⏱️ 타이머 목적지 갱신 (출발지에서 ${dist.toInt()}m)")
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
        Log.d(TAG, "⏱️ 목적지 갱신 타이머 시작 (${DEST_UPDATE_INTERVAL/1000}초 간격)")
    }

    private fun stopDestUpdateTimer() {
        destUpdateHandler.removeCallbacks(destUpdateRunnable)
        Log.d(TAG, "⏱️ 목적지 갱신 타이머 중지")
    }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat/2)*Math.sin(dLat/2) + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLng/2)*Math.sin(dLng/2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))
    }

    // [v3.1 추가] 디버그 로그 서버 전송
    private fun sendDebugLog(event: String, detail: String) {
        Thread {
            try {
                val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                val userId = prefs.getString("user_id", null) ?: return@Thread
                val json = JSONObject().apply {
                    put("user_id", userId)
                    put("event", event)
                    put("detail", detail)
                }
                val conn = (URL("$SERVER_URL/api/debug/log").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true; connectTimeout = 5000; readTimeout = 5000
                }
                conn.outputStream.write(json.toString().toByteArray())
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) { /* 무시 */ }
        }.start()
    }

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED
            packageNames = TAXI_APPS.toTypedArray()
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        sendDebugLog("SERVICE", "v3.1 연결됨")
        Log.d(TAG, "NaviIntentReceiver v3.1 연결됨 (택시앱 전용)")
        Thread {
            Thread.sleep(5000)
            LocalTripDatabase.getInstance(this).syncPendingTrips(this)
        }.start()
        try {
            startForegroundService(Intent(this, LocationTrackingService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "GPS 시작 실패: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in TAXI_APPS) return
        val now = System.currentTimeMillis()

        if (lastTripId > 0 && tripStartedAt > 0 && now - tripStartedAt > MAX_TRIP_DURATION) {
            Log.d(TAG, "트립 90분 초과, 강제 마감 처리")
            finalizeCurrentTrip(0)
        }

        lastTaxiPlatform = PLATFORM_NAMES[pkg] ?: "카카오T"
        lastPlatform = lastTaxiPlatform

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedText = event.contentDescription?.toString()
                ?: event.text?.firstOrNull()?.toString() ?: ""
            Log.d(TAG, "클릭 감지: $clickedText")
            // [v3.1 수정] 우버 "운행 완료" 클릭 → 즉시 금액 읽기 (딜레이 없이)
            if (clickedText.contains("운행 완료") || clickedText.contains("운행완료")) {
                Log.d(TAG, "운행 완료 클릭! 즉시 금액 읽기")
                if (lastTripId > 0) {
                    try {
                        val root = rootInActiveWindow
                        if (root != null) {
                            val fareLines = mutableListOf<String>()
                            fun t(n: android.view.accessibility.AccessibilityNodeInfo?) { n ?: return; n.text?.toString()?.trim()?.let { if (it.isNotEmpty()) fareLines.add(it) }; for (i in 0 until n.childCount) t(n.getChild(i)) }
                            t(root)
                            val fare = extractFare(fareLines)
                            sendDebugLog("CLICK_END", "$lastPlatform | $clickedText | ${fare}원")
                            finalizeCurrentTrip(fare)
                        }
                    } catch (e: Exception) { Log.e(TAG, "운행완료 금액 읽기 실패: ${e.message}") }
                }
                return
            }
            if (clickedText.contains("길안내") || clickedText.contains("탑승") || clickedText.contains("손님")) {
                Log.d(TAG, "길안내/탑승/손님 버튼 클릭! 즉시 파싱")
                sendDebugLog("CLICK", "$lastPlatform | $clickedText")
                clickHandledUntil = now + CLICK_SUPPRESS_WINDOW
                lastTriggerTime = now
                Handler(mainLooper).postDelayed({ extractTaxiInfo(pkg) }, 300)
                return
            }
        }

        if (now < clickHandledUntil) return
        if (now - lastTriggerTime < TRIGGER_COOLDOWN) return
        lastTriggerTime = now
        Handler(mainLooper).postDelayed({ extractTaxiInfo(pkg) }, 500)
    }

    private fun extractTaxiInfo(pkg: String) {
        if (isProcessingTaxiScreen) return
        isProcessingTaxiScreen = true
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
            Log.d(TAG, "택시앱($lastPlatform) 화면:\n${allText.take(500)}")

            if (lastTripId <= 0 && allText.contains("라이더") && allText.contains("평가")) return

            // 운행 중인데 대기 화면 감지 = 취소 완료
            if (lastTripId > 0) {
                val isCancelledToIdle = when (pkg) {
                    TMONEYGO, TMONEYGO_NAVI -> allText.contains("콜 리스트") && !allText.contains("출발지 길안내") && !allText.contains("목적지 길안내") && !allText.contains("승객 탑승")
                    KAKAO_TAXI -> allText.contains("콜 대기") || allText.contains("퇴근하기")
                    else -> false
                }
                if (isCancelledToIdle && System.currentTimeMillis() - tripStartedAt > 60000) {
                    Log.d(TAG, "⚠️ 운행 중 대기화면 → 취소 감지")
                    sendDebugLog("CANCEL_END", "#$lastTripId | $lastPlatform | 대기화면복귀")
                    deleteCurrentTrip()
                    return
                }
            }

            // 완료/결제 신호
            val isCompletionSignal = when (pkg) {
                UBER -> allText.contains("영수증") ||
                    allText.contains("결제 완료") || allText.contains("운행이 완료") ||
                    ((allText.contains("운행 완료") || allText.contains("운행완료")) && extractFare(lines) > 0) ||
                    (allText.contains("라이더") && allText.contains("평가해 주세요"))
                TMONEYGO, TMONEYGO_NAVI -> allText.contains("자동결제 완료") ||
                    allText.contains("결제 요금")
                else -> allText.contains("자동결제 완료") ||
                    allText.contains("결제 요금") ||
                    allText.contains("입력하신 요금이 맞습니까") ||
                    allText.contains("탑승한 손님은 어떠셨나요") ||
                    allText.contains("손님이 직접결제 하셨나요")
            }

            if (isCompletionSignal) {
                if (lastTripId > 0) {
                    val fare = extractFare(lines)
                    Log.d(TAG, "✅ 운행 종료 신호 ($lastPlatform), 마감 (요금: ${fare}원)")
                    sendDebugLog("TRIP_END", "#$lastTripId | ${fare}원")
                    sendDebugLog("END_SCREEN", allText.take(300))
                    finalizeCurrentTrip(fare)
                }
                return
            }

            // 활성 콜 화면 판단 — v3 원본 그대로 + "손님 탑승" 추가
            val isActiveCallScreen = when (pkg) {
                UBER -> allText.contains("탑승 완료") || allText.contains("승객 탑승") ||
                    allText.contains("운행 시작") || allText.contains("요금 입력하기") ||
                    (allText.contains("목적지") && allText.contains("도착"))
                TMONEYGO, TMONEYGO_NAVI -> (allText.contains("출발지 길안내") || allText.contains("목적지 길안내"))
                    && !allText.contains("밀어서 운행종료")
                    && !allText.contains("공지") && !allText.contains("미션")
                    && !allText.contains("Samsung") && !allText.contains("카카오톡")
                // [v3.1 수정] "손님 탑승"/"손님탑승" 추가
                else -> (allText.contains("길안내") && allText.contains("탑승"))
                    || allText.contains("손님 탑승") || allText.contains("손님탑승")
                    && !allText.contains("밀어서 운행종료")
                    && !allText.contains("콜 대기") && !allText.contains("배차")
            }

            if (!isActiveCallScreen) {
                Log.d(TAG, "콜 화면 아님 -> 무시")
                return
            }

            val curLat = LocationTrackingService.currentLat
            val curLng = LocationTrackingService.currentLng
            val locationAge = System.currentTimeMillis() - LocationTrackingService.lastLocationTime
            if (curLat == 0.0 && curLng == 0.0) {
                Log.d(TAG, "GPS 아직 없음")
                sendDebugLog("GPS_FAIL", "좌표없음")
                return
            }
            if (locationAge > 5 * 60 * 1000L) {
                Log.d(TAG, "GPS 스테일 (${locationAge/1000}초)")
                sendDebugLog("GPS_FAIL", "스테일 ${locationAge/1000}초")
                return
            }

            if (lastTripId <= 0) {
                tripPlatform = lastPlatform
                sendDebugLog("TRIP_START", "$lastPlatform | lat=$curLat lng=$curLng")
                createNewTripWithGps(curLat, curLng)
            } else if (lastPlatform != tripPlatform && System.currentTimeMillis() - tripStartedAt > 30000) {
                // 다른 플랫폼 활성 화면 → 이전 트립 강제 종료 + 새 트립
                Log.d(TAG, "⚠️ 플랫폼 변경: $tripPlatform → $lastPlatform, 이전 트립 강제 종료")
                sendDebugLog("FORCE_END", "#$lastTripId | $tripPlatform→$lastPlatform")
                finalizeCurrentTrip(0)
                Thread.sleep(500)
                tripPlatform = lastPlatform
                sendDebugLog("TRIP_START", "$lastPlatform | lat=$curLat lng=$curLng")
                createNewTripWithGps(curLat, curLng)
            } else {
                val dist = distanceMeters(originLat, originLng, curLat, curLng)
                if (dist > 300) {
                    refreshTripDestination(lastTripId, curLat, curLng)
                }
                // 우버 금액 캐싱: 화면에 ₩ 금액 보이면 기억
                if (pkg == UBER && lastTripId > 0) {
                    val fare = extractFare(lines)
                    if (fare > 0) lastDetectedFare = fare
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "택시앱 오류: ${e.message}")
        } finally {
            isProcessingTaxiScreen = false
        }
    }

    private fun createNewTripWithGps(lat: Double, lng: Double) {
        if (isSendingTrip) return
        if (lastTripId > 0) {
            Log.d(TAG, "⚠️ 이전 트립 #$lastTripId 미종료, 강제 마감")
            sendDebugLog("FORCE_END", "#$lastTripId | 새콜시작으로 강제종료")
            finalizeCurrentTrip(0)
            Thread.sleep(500)
        }
        isSendingTrip = true
        tripStartedAt = System.currentTimeMillis()
        originLat = lat
        originLng = lng
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
                        put("user_id", userId); put("platform", lastPlatform)
                        put("naviApp", lastNaviApp)
                        put("depLat", lat); put("depLng", lng)
                        put("destName", oName); put("destLat", lat); put("destLng", lng)
                        put("originName", oName)
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
                        Log.d(TAG, "🚕 새 트립: #$lastTripId | $oName | $lastPlatform")
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "서버 전송 실패: ${e.message}")
                    lastTripId = -1
                }
                lastSentDest = oName
                lastSentTime = System.currentTimeMillis()
                // 타이머 시작
                Handler(Looper.getMainLooper()).post { startDestUpdateTimer() }
            } catch (e: Exception) {
                Log.e(TAG, "트립 생성 실패: ${e.message}")
            } finally {
                isSendingTrip = false
            }
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
                val json = JSONObject().apply {
                    put("user_id", userId); put("destination", destName)
                    put("dest_lat", lat); put("dest_lng", lng)
                }
                val conn = (URL("$SERVER_URL/api/trips/$tripId").openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json")
                    doOutput = true; connectTimeout = 30000; readTimeout = 30000
                }
                conn.outputStream.write(json.toString().toByteArray())
                conn.responseCode
                Log.d(TAG, "📍 목적지 갱신: #$tripId -> $destName")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "목적지 갱신 실패: ${e.message}")
            } finally {
                tripDestUpdateInFlight = false
            }
        }.start()
    }

    // 취소 시 트립 삭제 (0원 유령기록 방지)
    private fun deleteCurrentTrip() {
        val tripId = lastTripId
        if (tripId <= 0) return
        lastTripId = -1
        lastDetectedFare = 0
        stopDestUpdateTimer()
        Thread {
            try {
                val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                val userId = prefs.getString("user_id", null)
                val json = JSONObject().apply { put("user_id", userId) }
                val conn = (URL("$SERVER_URL/api/trips/$tripId").openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"; setRequestProperty("Content-Type", "application/json")
                    doOutput = true; connectTimeout = 10000; readTimeout = 10000
                }
                conn.outputStream.write(json.toString().toByteArray()); conn.responseCode
                Log.d(TAG, "🗑️ 취소 트립 삭제: #$tripId"); conn.disconnect()
            } catch (e: Exception) { Log.e(TAG, "트립 삭제 실패: ${e.message}") }
            finally { synchronized(this) { lastTripId = -1; lastLocalTripId = -1; lastSentDest = ""; lastSentTime = 0L; tripStartedAt = 0L; originLat = 0.0; originLng = 0.0; tripPlatform = "" } }
        }.start()
    }

    private fun finalizeCurrentTrip(fare: Int) {
        val tripId = lastTripId
        if (tripId <= 0) return
        val actualFare = if (fare > 0) fare else lastDetectedFare
        lastTripId = -1
        lastDetectedFare = 0
        stopDestUpdateTimer()
        val lat = LocationTrackingService.currentLat
        val lng = LocationTrackingService.currentLng
        Thread {
            try {
                val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                val userId = prefs.getString("user_id", null)
                val json = JSONObject().apply {
                    put("user_id", userId)
                    if (actualFare > 0) put("fare", actualFare)
                    if (lat != 0.0 || lng != 0.0) {
                        val dist = distanceMeters(originLat, originLng, lat, lng)
                        if (dist > 300) {
                            val destName = reverseGeocode(lat, lng)
                            if (!destName.isNullOrEmpty()) {
                                put("destination", destName); put("dest_lat", lat); put("dest_lng", lng)
                            }
                        }
                    }
                }
                val conn = (URL("$SERVER_URL/api/trips/$tripId").openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json")
                    doOutput = true; connectTimeout = 30000; readTimeout = 30000
                }
                conn.outputStream.write(json.toString().toByteArray())
                conn.responseCode
                Log.d(TAG, "✅ 트립 마감: #$tripId (요금: ${actualFare}원)")
                conn.disconnect()
                if (lastLocalTripId > 0) {
                    val destName = reverseGeocode(lat, lng) ?: ""
                    LocalTripDatabase.getInstance(this).updateDestination(lastLocalTripId, destName, lat, lng, fare)
                }
            } catch (e: Exception) {
                Log.e(TAG, "트립 마감 실패: ${e.message}")
            } finally {
                synchronized(this) {
                    lastTripId = -1; lastLocalTripId = -1
                    lastSentDest = ""; lastSentTime = 0L; tripStartedAt = 0L
                    originLat = 0.0; originLng = 0.0; tripPlatform = ""
                }
            }
        }.start()
    }

    // [v3.1 수정] Nominatim 직접 호출 (서버 경유 제거)
    private fun reverseGeocode(lat: Double, lng: Double): String? {
        return try {
            val conn = (URL("https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lng&zoom=16&addressdetails=1&accept-language=ko").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 10000
                setRequestProperty("User-Agent", "CallRadar/1.0")
            }
            val raw = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val json = JSONObject(raw)
            val addr = json.optJSONObject("address")
            val district = addr?.optString("borough", addr.optString("suburb", addr.optString("quarter", ""))) ?: ""
            val neighbourhood = addr?.optString("neighbourhood", addr.optString("residential", "")) ?: ""
            val city = addr?.optString("city", addr.optString("county", "")) ?: ""
            listOf(district, neighbourhood).filter { it.isNotEmpty() }.distinct().joinToString(" ").ifEmpty { city }
        } catch (e: Exception) {
            Log.e(TAG, "역지오코딩 실패: ${e.message}")
            null
        }
    }

    private fun extractFare(lines: List<String>): Int {
        val filteredLines = lines.filter { !it.contains("지급") && !it.contains("미션") && !it.contains("포인트") }
        val allText = filteredLines.joinToString(" ")
        var maxFare = 0
        for (pattern in FARE_PATTERNS) {
            val matches = pattern.findAll(allText)
            for (m in matches) {
                val amount = m.groupValues[1].replace(",", "").toIntOrNull() ?: 0
                if (amount in 1000..500000 && amount > maxFare) maxFare = amount
            }
        }
        return maxFare
    }

    override fun onInterrupt() { Log.d(TAG, "NaviIntentReceiver 중단") }
    override fun onDestroy() { stopDestUpdateTimer(); super.onDestroy() }
}
