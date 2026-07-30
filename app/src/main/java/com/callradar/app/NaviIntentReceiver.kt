// ===== NaviIntentReceiver v3.1x2 (2026-07-10) =====
// v3.1x2: ①우버 미터입력 화면 통행료 제외 (라벨 다음 첫 금액=미터요금), FARE_CACHE 원문 동봉
//         ②타임아웃 90분→6시간 (장거리+네비전환 시 유령트립 방지, 마감 시 캐시요금 사용)
// v3.1x: ①우버 요금 허용목록(isUberFareScreen) - 사진 확인된 두 화면에서만 요금 인식
//           (홈/지도/평가 상단의 오늘 누적수입 ₩104,848 등을 요금으로 잡던 버그)
//        ②카카오 "손님이 직접결제 하셨나요" 화면은 요금>0 일 때만 완료
//           (미터기 미연동 기사는 0원 상태로 떠서 0원 마감되던 버그)
//        ③운행 중 플랫폼 전환 금지 (FORCE_END 제거)
//           (카카오T+티머니고 동시 실행 시 트립이 쪼개지고 금액 유실되던 버그)
//        ④activeTripId 공유상태 - 택시투데이 알림이 "플랫폼콜 진행중인지"로 판단
//           (알림이 TRIP_END보다 먼저 와서 정상 카카오콜 금액을 길빵이 가져가던 버그)
// v3.1w: 우버 "미터 요금만 입력" 화면에서 요금 캐싱 → 자동결제(수금화면 없음) 요금 검출
// v3.1v: ①즉시취소 감지(미탑승 시 60초→5초) - 유령트립 생성 차단
//        ②finalizeCurrentTrip에 ended_at 전송 - 정상종료 표시(택시투데이 매칭 근거)
//        ③버전문자열 v3.1→v3.1v (로그 혼란 해결)
// v3.1u: 우버 신버전 직접결제 대응 - 버튼 "운행완료"→"콜 완료" 변경, "수금" 확인화면 추가 감지
// v3.1t: 우버 "최종 금액"(예상치) 제거 - 입력후 화면(₩10600+운행완료)에서 실제요금만 잡기
// v3.1s: 우버 평가화면/홈화면 누적수입(홈 ₩34200 오늘)을 요금으로 오인하던 버그 수정 - 평가화면 파싱 스킵
// v3.1r: 통행요금 줄 파싱 제외 (미터기/결제요금만) - 통행요금 혼선 방지
// v3.1q: 카카오 금액파싱 개선 - 결제요금/미터기요금 뒤 "원" 없어도 매칭(접근성 노드분리 대응), 금액캐싱 전플랫폼 확대
// v3.1o: extractFare에서 거리(m/km)·시간(분) 제거 → 우버 103362m를 요금으로 오인하던 버그 해결 (금액캐싱도 정상화)
// v3.1n: 탑승감지 3플랫폼 확대 - 클릭(카카오/티머니) + 화면텍스트(우버 탑승완료/승객탑승)
// v3.1l: 이전 확정본
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
            Regex("결제\\s*요금\\s*[：:]?\\s*([0-9,]+)"),
            Regex("미터기\\s*요금\\s*[：:]?\\s*([0-9,]+)"),
            Regex("총\\s*요금\\s*[：:]?\\s*([0-9,]+)"),
            Regex("₩\\s*([0-9,]+)"),
            Regex("([0-9,]{4,})\\s*원")
        )
        private const val MAX_TRIP_DURATION = 21600000L  // 6시간 (장거리+정체+네비전환 대응)
        private const val DEST_UPDATE_INTERVAL = 30000L

        // [v3.1x] 택시투데이 알림 서비스가 참조하는 "현재 진행 중인 플랫폼 콜" 상태
        //  - activeTripId > 0  : 플랫폼 콜 진행 중 → 결제 알림은 그 트립 것
        //  - activeTripId <= 0 : 플랫폼 콜 없음 → 결제 알림은 길빵/예약
        // 대기시간 길이와 무관. ended_at/시간창에 의존하지 않음.
        @Volatile var activeTripId: Int = -1
        @Volatile var activeTripStartedAt: Long = 0L
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
    @Volatile private var passengerBoarded = false  // 손님 탑승 여부 - true면 장거리/정체여도 취소 안함
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
                val conn = (URL("$SERVER_URL/api/debug/log").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
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
        sendDebugLog("SERVICE", "v3.1x2 연결됨")
        Log.d(TAG, "NaviIntentReceiver v3.1x2 연결됨 (택시앱 전용)")
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

        // [v3.1x2] 타임아웃 90분 → 6시간 안전상한
        // 원인: 강남→인천공항/지방 장거리는 정체 시 2시간 넘고, 기사가 우버→네이버네비→우버로
        //       화면 전환하면 그 사이 우버 화면이 안 떠서 이벤트 공백이 길어진다.
        //       90분 타임아웃이 진행 중인 #884를 죽이자, 우버 복귀 화면이 lastTripId<=0 이 되어
        //       새 콜(유령 #886 운서동→운서동)로 오인됐다.
        // 해결: 상한을 6시간으로. 그 안이면 운행 중으로 보고 트립 유지 → 복귀해도 유령 안 생김.
        //       마감 시엔 캐시된 요금(lastDetectedFare)을 살린다(0원 마감 방지).
        if (lastTripId > 0 && tripStartedAt > 0 && now - tripStartedAt > MAX_TRIP_DURATION) {
            Log.d(TAG, "트립 6시간 초과, 강제 마감 처리")
            finalizeCurrentTrip(if (lastDetectedFare > 0) lastDetectedFare else 0)
        }

        lastTaxiPlatform = PLATFORM_NAMES[pkg] ?: "카카오T"
        lastPlatform = lastTaxiPlatform

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedText = event.contentDescription?.toString()
                ?: event.text?.firstOrNull()?.toString() ?: ""
            Log.d(TAG, "클릭 감지: $clickedText")
            // [v3.1t] 우버 완료 버튼 클릭 → 즉시 금액 읽기
            // 구버전: "일반 콜 운행완료" / 신버전: "일반 콜 완료"(수금화면) 둘 다 감지
            if (clickedText.contains("운행 완료") || clickedText.contains("운행완료") ||
                (clickedText.contains("콜") && clickedText.contains("완료"))) {
                Log.d(TAG, "우버 완료 클릭! 즉시 금액 읽기")
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
                    } catch (e: Exception) { Log.e(TAG, "완료 금액 읽기 실패: ${e.message}") }
                }
                return
            }
            if (clickedText.contains("길안내") || clickedText.contains("탑승") || clickedText.contains("손님")) {
                Log.d(TAG, "길안내/탑승/손님 버튼 클릭! 즉시 파싱")
                sendDebugLog("CLICK", "$lastPlatform | $clickedText")
                // 손님 탑승 클릭 시 → 이 운행은 실제 운행 (장거리/정체여도 취소 방지)
                if (clickedText.contains("탑승")) { passengerBoarded = true; Log.d(TAG, "✅ 손님 탑승 → 취소방지 활성") }
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
                // [v3.1v] 손님 미탑승 시 즉시취소 감지 (기존 60초 → 5초)
                // 카카오T는 콜 수락 직후 대기화면이 안 뜨므로 오탐 위험 낮음
                // 손님 탑승한 경우는 아래 passengerBoarded 분기에서 보호됨
                val minCancelMs = if (passengerBoarded) 60000L else 5000L
                if (isCancelledToIdle && System.currentTimeMillis() - tripStartedAt > minCancelMs) {
                    if (passengerBoarded) {
                        // 손님 탑승한 운행은 대기화면 스쳐도 취소 안함 (인천공항/지방/정체 대응)
                        Log.d(TAG, "대기화면 감지했으나 손님 탑승상태 → 취소 무시")
                    } else {
                        Log.d(TAG, "⚠️ 운행 중 대기화면 → 취소 감지")
                        sendDebugLog("CANCEL_END", "#$lastTripId | $lastPlatform | 대기화면복귀(미탑승)")
                        deleteCurrentTrip()
                        return
                    }
                }
            }

            // [v3.1x2] 우버 "미터 요금만 입력" 화면 - 미터요금 캐싱, 통행료 제외
            // 화면 레이아웃: "미터 요금만 입력" 라벨 + 미터요금, 통행료는 별도(있을 때만 표시)
            //   - 통행료 있는 운행: 미터요금 + 통행료(작음) 둘 다 보임 → 최댓값=미터요금
            //   - 통행료 없는 운행: 미터요금만 → 그대로 잡힘
            //   - 입력 중엔 중간값/통행료가 클 수 있으나, 매 프레임 덮어써서 완료 시 미터요금이 최종 캐시됨
            // 라벨 "다음" 금액들만 봐서 홈화면 누적수입(라벨 없음)은 자동 배제.
            if (pkg == UBER && !allText.contains("수금") && !allText.contains("콜 완료")
                && (allText.contains("미터 요금만 입력") || allText.contains("확인하고 계속하기"))) {
                var meterFare = 0
                val labelIdx = lines.indexOfFirst { it.contains("미터 요금만 입력") }
                if (labelIdx >= 0) {
                    for (j in (labelIdx + 1) until lines.size) {
                        val m = Regex("([0-9,]{2,})").find(lines[j].replace("₩", "").trim())
                        val v = m?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0
                        if (v in 1000..500000 && v > meterFare) meterFare = v  // 최댓값 = 미터요금(통행료보다 큼)
                    }
                }
                if (meterFare == 0) meterFare = extractFare(lines, pkg)  // 라벨 못 찾으면 폴백
                if (meterFare > 0) {
                    lastDetectedFare = meterFare
                    Log.d(TAG, "💰 우버 미터요금 캐싱: ${meterFare}원")
                    // 검증용: 화면 원문 동봉 (통행료 유무·배치 확인용)
                    sendDebugLog("FARE_CACHE", "우버 미터요금 | ${meterFare}원 | " + allText.take(100))
                }
                return  // 아직 완료 아님
            }

            // 완료/결제 신호
            val isCompletionSignal = when (pkg) {
                UBER -> allText.contains("영수증") ||
                    allText.contains("결제 완료") || allText.contains("운행이 완료") ||
                    ((allText.contains("운행 완료") || allText.contains("운행완료")) && extractFare(lines, pkg) > 0) ||
                    ((allText.contains("콜 완료") || allText.contains("수금")) && extractFare(lines, pkg) > 0) ||
                    (allText.contains("라이더") && allText.contains("평가해 주세요"))
                TMONEYGO, TMONEYGO_NAVI -> allText.contains("자동결제 완료") ||
                    (allText.contains("결제 요금") && extractFare(lines, pkg) > 0)
                else -> allText.contains("자동결제 완료") ||
                    (allText.contains("결제 요금") && extractFare(lines, pkg) > 0) ||
                    allText.contains("입력하신 요금이 맞습니까") ||
                    allText.contains("탑승한 손님은 어떠셨나요") ||
                    // [v3.1x] "손님이 직접결제 하셨나요?" 화면은 기사가 미터기 금액을 넣기 전에도 뜬다.
                    // (미터기 미연동 기사: "미터기 요금 0 / 통행 요금 0 / 요금 입력" 상태로 표시)
                    // 이때 완료로 마감하면 0원 트립이 되고, 이후 금액을 입력해도 반영되지 않는다.
                    // → 실제 요금이 잡혔을 때만 완료로 인정한다.
                    (allText.contains("손님이 직접결제 하셨나요") && extractFare(lines, pkg) > 0)
            }

            if (isCompletionSignal) {
                if (lastTripId > 0) {
                    val fare = extractFare(lines, pkg)
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

            // 화면 텍스트로도 탑승 감지 (우버 등 클릭 대신 화면표시 방식 커버) - 모든 플랫폼 취소방지
            if (!passengerBoarded && lastTripId > 0 && (
                    allText.contains("탑승 완료") || allText.contains("승객 탑승") ||
                    allText.contains("손님 탑승") || allText.contains("손님탑승") ||
                    (allText.contains("목적지") && allText.contains("도착")))) {
                passengerBoarded = true
                Log.d(TAG, "✅ 화면에서 탑승 감지 → 취소방지 활성")
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
            } else {
                // [v3.1x] 운행 중 플랫폼 전환 금지
                // 기사는 카카오T/티머니고(/우버)를 동시에 켜두고 먼저 잡히는 콜을 받음.
                // → 운행 중 반대편 앱의 대기화면이 스치는 일이 흔함.
                // 예전 코드는 이때 FORCE_END + finalizeCurrentTrip(0) 으로 트립을 0원 마감하고
                // 새 트립을 만들어 한 운행이 둘로 갈리고 금액이 유실됐다(티머니 금액 미입력 원인).
                // 진짜 새 콜은 완료신호(TRIP_END)로 lastTripId=-1 이 되어 위 분기에서 자연히 생성되고,
                // 완료를 놓쳐도 MAX_TRIP_DURATION(90분) 타임아웃이 있다.
                if (lastPlatform != tripPlatform) {
                    Log.d(TAG, "다른 플랫폼 화면 스침: $tripPlatform ← $lastPlatform (트립 유지)")
                }

                val dist = distanceMeters(originLat, originLng, curLat, curLng)
                if (dist > 300) {
                    refreshTripDestination(lastTripId, curLat, curLng)
                }
                // 금액 캐싱: 화면에 요금 보이면 기억 (우버·카카오 등 완료화면 전환 대비)
                if (lastTripId > 0) {
                    val fare = extractFare(lines, pkg)
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
        if (tripStartedAt > 0 && System.currentTimeMillis() - tripStartedAt < 5000) return  // 5초 내 중복 방지
        if (lastTripId > 0) {
            Log.d(TAG, "⚠️ 이전 트립 #$lastTripId 미종료, 강제 마감")
            sendDebugLog("FORCE_END", "#$lastTripId | 새콜시작으로 강제종료")
            finalizeCurrentTrip(0)
            Thread.sleep(500)
        }
        isSendingTrip = true
        tripStartedAt = System.currentTimeMillis()
        passengerBoarded = false  // 새 운행 시작 → 탑승 플래그 리셋
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
                    val conn = (URL("$SERVER_URL/api/trips").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                        requestMethod = "POST"; setRequestProperty("Content-Type", "application/json")
                        doOutput = true; connectTimeout = 30000; readTimeout = 30000
                    }
                    conn.outputStream.write(json.toString().toByteArray())
                    val resJson = JSONObject(conn.inputStream.bufferedReader().readText())
                    lastTripId = resJson.optInt("id", -1)
                    activeTripId = lastTripId; activeTripStartedAt = System.currentTimeMillis()  // [v3.1x]
                    if (lastTripId > 0) {
                        db.markSynced(localId, lastTripId)
                        Log.d(TAG, "🚕 새 트립: #$lastTripId | $oName | $lastPlatform")
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "서버 전송 실패: ${e.message}")
                    lastTripId = -1; activeTripId = -1  // [v3.1x]
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
                val conn = (URL("$SERVER_URL/api/trips/$tripId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
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
        lastTripId = -1; activeTripId = -1  // [v3.1x] 취소 → 이후 결제알림은 길빵으로
        lastDetectedFare = 0
        stopDestUpdateTimer()
        Thread {
            try {
                val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                val userId = prefs.getString("user_id", null)
                val json = JSONObject().apply { put("user_id", userId) }
                val conn = (URL("$SERVER_URL/api/trips/$tripId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                    requestMethod = "DELETE"; setRequestProperty("Content-Type", "application/json")
                    doOutput = true; connectTimeout = 10000; readTimeout = 10000
                }
                conn.outputStream.write(json.toString().toByteArray()); conn.responseCode
                Log.d(TAG, "🗑️ 취소 트립 삭제: #$tripId"); conn.disconnect()
            } catch (e: Exception) { Log.e(TAG, "트립 삭제 실패: ${e.message}") }
            finally { synchronized(this) { lastTripId = -1; activeTripId = -1; lastLocalTripId = -1; lastSentDest = ""; lastSentTime = 0L; tripStartedAt = 0L; passengerBoarded = false; originLat = 0.0; originLng = 0.0; tripPlatform = "" } }
        }.start()
    }

    private fun finalizeCurrentTrip(fare: Int) {
        val tripId = lastTripId
        if (tripId <= 0) return
        val actualFare = if (fare > 0) fare else lastDetectedFare
        lastTripId = -1; activeTripId = -1  // [v3.1x] 정상 종료
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
                    // [v3.1v] 정상 종료 표시 - 택시투데이 금액 매칭이 유령트립(취소)을 배제하는 근거
                    put("ended_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.format(java.util.Date()))
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
                val conn = (URL("$SERVER_URL/api/trips/$tripId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
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
                    lastTripId = -1; activeTripId = -1; lastLocalTripId = -1
                    lastSentDest = ""; lastSentTime = 0L; tripStartedAt = 0L
                    originLat = 0.0; originLng = 0.0; tripPlatform = ""
                }
            }
        }.start()
    }

    // [v3.1 수정] Nominatim 직접 호출 (서버 경유 제거)
    private fun reverseGeocode(lat: Double, lng: Double): String? {
        return try {
            val conn = (URL("https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lng&zoom=16&addressdetails=1&accept-language=ko").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
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

    /**
     * [v3.1x] 우버 "진짜 요금 화면" 판별 (허용 목록 방식)
     * 우버 앱은 대기/지도/평가 화면 상단에 그날 누적수입(₩104,848 등)을 항상 띄운다.
     * 차단 목록으로 거르면 새 화면이 뜰 때마다 뚫리므로, 요금을 읽어도 되는 화면만 명시한다.
     *
     * 실제 요금이 표시되는 화면은 두 개뿐 (사용자 확인):
     *   1) "미터 요금만 입력" + ₩금액 + "확인하고 계속하기"   (자동/직접결제 공통)
     *   2) "수금" + ₩금액 + "일반 콜 완료"                    (직접결제만)
     * 그 외(홈/지도/평가/오늘수입/마지막운행/합산)는 전부 요금이 아니다.
     */
    private fun isUberFareScreen(allText: String): Boolean {
        val meterInput = allText.contains("미터 요금만 입력") || allText.contains("확인하고 계속하기")
        val collect = allText.contains("수금") || allText.contains("콜 완료") ||
            allText.contains("운행 완료") || allText.contains("운행완료")
        return meterInput || collect
    }

    private fun extractFare(lines: List<String>): Int = extractFare(lines, null)

    private fun extractFare(lines: List<String>, pkg: String?): Int {
        val allTextRaw = lines.joinToString(" ")

        // [v3.1x] 우버는 지정된 요금 화면에서만 금액을 읽는다 (허용 목록)
        // 대기화면 지도 상단의 "오늘 수입 / 마지막 운행 / 오늘의 합산" 등은 특정 시점에만 떠서
        // 차단 목록으로는 놓치기 쉽다 → 아예 요금 화면이 아니면 0.
        if (pkg == UBER && !isUberFareScreen(allTextRaw)) return 0

        // 평가화면/홈화면 누적수입 방어 (우버 외 플랫폼 및 pkg 미지정 호출 대비)
        val isRatingOrHome = (allTextRaw.contains("라이더") && allTextRaw.contains("평가")) ||
            (allTextRaw.contains("별점") && allTextRaw.contains("탭하세요")) ||
            (allTextRaw.contains("홈") && allTextRaw.contains("오늘")) ||
            allTextRaw.contains("수입 동향") || allTextRaw.contains("Uber Pro")
        if (isRatingOrHome) return 0

        // "통행 요금" 줄 제거 - 파싱 혼선 방지 (미터기/결제 요금만 사용)
        val filteredLines = lines.filter {
            !it.contains("지급") && !it.contains("미션") && !it.contains("포인트") && !it.contains("통행")
        }
        // 거리(103,362m / 66.4km)·시간(57분)·좌표 등을 요금으로 오인하지 않도록 제거
        val cleaned = filteredLines.joinToString(" ")
            .replace(Regex("통행\\s*요금\\s*[0-9,]*"), " ")
            .replace(Regex("최종\\s*금액\\s*[₩\\s]*[0-9,]*"), " ")
            .replace(Regex("[0-9,.]+\\s*km"), " ")
            .replace(Regex("[0-9,]+\\s*m(?![0-9])"), " ")
            .replace(Regex("[0-9]+\\s*분"), " ")
            .replace(Regex("[0-9]+\\s*시간"), " ")
        var maxFare = 0
        for (pattern in FARE_PATTERNS) {
            val matches = pattern.findAll(cleaned)
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
