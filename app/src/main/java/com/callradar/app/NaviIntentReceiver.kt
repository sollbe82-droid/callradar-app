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
        private val SERVER_URL = com.callradar.app.Endpoint.base
        private val FARE_PATTERNS = listOf(
            Regex("결제\\s*요금\\s*[：:]?\\s*([0-9,]+)"),
            Regex("미터기\\s*요금\\s*[：:]?\\s*([0-9,]+)"),
            Regex("총\\s*요금\\s*[：:]?\\s*([0-9,]+)"),
            Regex("₩\\s*([0-9,]+)"),
            Regex("([0-9,]{4,})\\s*원")
        )
        private const val MAX_TRIP_DURATION = 21600000L  // 6시간 (장거리+정체+네비전환 대응)
        /** 이 시간을 넘겨 달린 트립은 대기화면이 보여도 **취소로 보지 않는다**.
         *  서버 실측(60일 872건): 진짜 콜취소의 90.7%가 10분 안에 일어난다. 뒤 꼬리 9.3%는 오탐이었다. */
        private const val LATE_CANCEL_MS = 600000L       // 10분
        /** 생존 흔적 기록 간격. 이벤트마다 prefs 를 쓰면 I/O 가 폭주한다. */
        private const val ALIVE_WRITE_INTERVAL = 5 * 60 * 1000L
        private const val DEST_UPDATE_INTERVAL = 30000L

        // [v3.1x] 택시투데이 알림 서비스가 참조하는 "현재 진행 중인 플랫폼 콜" 상태
        //  - activeTripId > 0  : 플랫폼 콜 진행 중 → 결제 알림은 그 트립 것
        //  - activeTripId <= 0 : 플랫폼 콜 없음 → 결제 알림은 길빵/예약
        // 대기시간 길이와 무관. ended_at/시간창에 의존하지 않음.
        @Volatile var activeTripId: Int = -1
        @Volatile var activeTripStartedAt: Long = 0L
        // [궤적 실차/공차] 자동기록 트립에서 손님 탑승 상태. WorkSessionService가 읽어 GPS점을 실차(true)/공차로 태깅.
        //   activeTripId>0 && autoBoarded=true 인 구간만 실차. (드라이브 투 픽업·트립간 이동은 공차)
        @Volatile var autoBoarded: Boolean = false
        // [유저592] 우버 결제수단 — 완료 화면에서 판정한 값을 마감 때 함께 전송.
        //  "자동 결제" 배지가 보이면 auto, "미터 요금만 입력" 흐름이면 기사 수령(card/현금).
        @Volatile var uberPayType: String? = null
        // [v53 #124] 플로팅 배지에서 수동 취소를 걸 수 있도록 서비스 인스턴스 참조.
        @Volatile var instance: NaviIntentReceiver? = null
    }

    private var lastPlatform = "알수없음"
    private var lastNaviApp = ""
    private var lastSentDest = ""
    private var lastSentTime = 0L
    private var lastTriggerTime = 0L
    @Volatile private var lastTripId = -1
    // [티머니 취소] '승객 신고 사유' 창을 본 시각. [신고] 클릭이 이 창의 것인지 판단하는 데 쓴다.
    @Volatile private var cancelDialogSeenAt = 0L
    private var lastTaxiPlatform = "카카오T"
    private var tripPlatform = ""  // 현재 트립이 시작된 플랫폼
    private var lastDetectedFare = 0  // 우버 금액 캐싱
    private var lastUberWrittenFare = 0  // [우버0원수정] 요금 본 순간 서버에 쓴 값 추적(중복 PUT 방지)
    @Volatile private var tripStartedAt = 0L
    @Volatile private var passengerBoarded = false  // 손님 탑승 여부 - true면 장거리/정체여도 취소 안함

    /** 위 판정을 트립당 한 번만 로그로 남기기 위한 표시(프레임마다 찍히면 로그가 폭주한다). */
    @Volatile private var cancelSkipTripId = -1
    @Volatile private var lastAliveWrite = 0L   // 생존 흔적을 마지막으로 쓴 시각(메모리 캐시)
    @Volatile private var lastTollTripId = -1  // [v57] 통행료 중복기록 방지 — 트립당 1회만
    // 우버는 통행료를 '미터 요금만 입력' 화면에 띄우고, 그 화면은 종료 신호보다 먼저 지나간다.
    //  본 순간 여기 담아뒀다가 마감할 때 쓴다. 새 운행이 시작되면 0으로 되돌린다.
    @Volatile private var pendingToll = 0
    @Volatile private var boardedAtSent = false  // [#4 실차율] 탑승 순간 boarded_at 1회 전송했는지(근접 픽업도 실차시간 정확)
    @Volatile private var carryBoardToNextTrip = false  // [R1] 유령트립 마감 후 이어진 새 트립에 탑승상태 이어주기
    @Volatile private var forceNewTripOnNextScan = false // [R1] 새 탑승 감지 → 다음 스캔에서 유령 마감+새 트립(기존 force-end 경로 재사용, 레이스 방지)
    @Volatile private var originRestamped = false    // [정확도] 탑승 순간 출발지를 픽업지점으로 1회 재설정했는지
    // [#4116 미터기 수정결제] 마감 직후 '최종 확인 금액'이 다르게 뜨면 갱신하기 위해 최근 마감 트립을 잠깐 기억.
    @Volatile private var recentFinalTripId = -1
    @Volatile private var recentFinalFare = 0
    @Volatile private var recentFinalAt = 0L
    @Volatile private var tripDestUpdateInFlight = false
    // [v50 화면주소] 네비 헤더에서 긁은 실제 주소(POI/도로명). GPS 지오코딩보다 정확 → 우선 사용.
    //   탑승 전 화면 = 픽업(출발) 주소, 탑승 후 화면 = 목적지 주소.
    @Volatile private var screenAddrPickup = ""
    @Volatile private var screenAddrDest = ""
    @Volatile private var lastLoggedScreenAddr = ""
    @Volatile private var lastLocalTripId = -1L
    // [v100 우버 연속배차] 미터 입력창이 떠 있는 동안은 새 트립을 만들지 않는다.
    //  그 창은 2초 안에 여러 번 다시 그려져서, 마감 직후 같은 자리에 헛 트립이 생긴다.
    @Volatile private var uberMeterScreenUntil = 0L
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
        /* [서버 주소 확정] 이 경로는 MainActivity 없이 깨어난다(부팅·알람·접근성·알림).
         *  여기서 안 부르면 이 진입점의 요청이 기본값(운영)으로 나가 검증 환경이 무의미해진다. */
        com.callradar.app.Endpoint.init(this)
        instance = this   // [v53 #124] 플로팅 수동취소용 인스턴스 등록
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
        val appVer = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        sendDebugLog("SERVICE", "v3.1x2 연결됨 | 앱 $appVer")
        reportPreviousOutage()   // ★ 직전 세션이 '설정 꺼짐'이었나 '프로세스 사망'이었나를 여기서 보고
        // [103-2] 업데이트·강제종료로 프로세스가 죽었다면 진행 중이던 운행을 먼저 이어받는다.
        //  이걸 아래 '재연결 복구 스캔'보다 먼저 해야 한다 — 그 스캔은 activeTripId<=0 이면
        //  운행을 '새 콜'로 보고 새로 만들어버려서, 기록이 갈리고 출발지가 도중부터 찍힌다.
        restoreTripState()
        Log.d(TAG, "NaviIntentReceiver v3.1x2 연결됨 (택시앱 전용) | 앱 $appVer")
        Thread {
            Thread.sleep(5000)
            LocalTripDatabase.getInstance(this).syncPendingTrips(this)
        }.start()
        try {
            // [관리자 게이트] 관리자 해금 기기에서만 위치서비스 시작(비관리자는 자동기록 대기, GPS도 안 켬)
            // [재부팅 유령실행 수정 #103] 접근성은 재부팅 시 OS가 자동 재연결됨 → 여기서 무조건 GPS·배지를 켜면
            //  콜도 안 잡았는데 앱이 '자동 실행'된 것처럼 보임(배터리·오해 유발). 근무 중(work_start>0)일 때만
            //  즉시 복구(OS가 접근성을 죽였다 살린 경우)하고, 그 외(재부팅 등)엔 첫 택시앱 이벤트에서 lazy 시작.
            val working = getSharedPreferences("callradar_prefs", MODE_PRIVATE).getLong("work_start", 0L) > 0L
            if (isAdmin() && autoOn() && working) {
                startForegroundService(Intent(this, LocationTrackingService::class.java)); locStarted = true
                // [자동기록 배지] 플로팅 버튼과 무관하게, 자동기록 켜지면 배지 서비스도 띄워 '자동기록 중/대기'가 항상 보이게.
                try { startService(Intent(this, com.callradar.app.FloatingTripService::class.java)) } catch (e: Exception) {}
                // [v50 배지 자가복구] OS가 접근성을 죽였다 살린 경우, 진행 중이던 운행 화면을 즉시 재스캔해
                //   트립을 이어받아 배지를 다시 켠다(놓쳐서 카드알림으로만 잡히던 것 방지). 활성 콜 화면일 때만 동작.
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val root = rootInActiveWindow
                        val pkg = root?.packageName?.toString()
                        if (pkg != null && TAXI_APPS.contains(pkg) && activeTripId <= 0) {
                            lastTaxiPlatform = PLATFORM_NAMES[pkg] ?: "카카오T"; lastPlatform = lastTaxiPlatform
                            sendDebugLog("SERVICE", "재연결 복구 스캔 | $pkg")
                            extractTaxiInfo(pkg)
                        }
                    } catch (e: Exception) {}
                }, 2000)
            }
            // [v93] 로그에 '왜' 안 켰는지 정확히 남긴다.
            //  예전 문구는 조건이 셋인데 둘만 말해서(관리자·토글), 실제로는 '미출근'이라 안 켠 건데도
            //  "관리자 미해금 또는 OFF"로 찍혔다. 자동출근이 왜 안 되는지 추적하느라 헛돌았다.
            else Log.d(TAG, "위치서비스 미시작 — admin=${isAdmin()} auto=${autoOn()} working=$working (셋 다 참이어야 즉시 시작. 아니면 첫 택시앱 이벤트에서 lazy 시작)")
        } catch (e: Exception) {
            Log.e(TAG, "GPS 시작 실패: ${e.message}")
        }
    }

    // [관리자 게이트] 자동기록은 관리자 해금(is_admin) 기기에서만. 오픈 배포지만 일반 유저는 접근성 켜도 무동작.
    // [v44 Fix C] 관리자(is_admin)뿐 아니라 부여받은 권한(acct_entitled)도 자동기록 허용 — 테스트 기사 권한자 반영.
    private fun isAdmin(): Boolean = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).let { it.getBoolean("is_admin", false) || it.getBoolean("acct_entitled", false) || it.getBoolean("auto_free_open", false) }
    // [자동기록 시작/종료] 앱 인앱 토글. 접근성은 켜둔 채 이 값으로 실제 기록 on/off (앱이 접근성 자체를 못 끔).
    // [근본해결] free_open(전원 개방) 켜지면 별도 토글 없이도 자동기록 ON — "접근성만 켜고 토글 안 켜서 안 되는" 함정 제거.
    // [v56] 자동기록 on/off는 토글(auto_record_on)이 유일 기준. 유저가 토글을 만진 적 있으면(auto_record_touched) auto_free_open이 켜져 있어도 OFF면 진짜 OFF.
    //  아직 안 만진 유저는 기존대로 자격(auto_free_open)으로 기본 동작 → 회귀 방지.
    private fun autoOn(): Boolean = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).let {
        if (it.getBoolean("auto_record_touched", false)) it.getBoolean("auto_record_on", false)
        else it.getBoolean("auto_record_on", false) || it.getBoolean("auto_free_open", false)
    }
    @Volatile private var locStarted = false   // [관리자 게이트] GPS 서비스 지연 시작 여부

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        touchAlive()   // ★ 게이트보다 **먼저**. 게이트에 막혀도 '서비스는 살아 있었다'는 사실은 남아야 한다.
        if (!isAdmin() || !autoOn()) return   // [게이트] 관리자 아니거나 자동기록 OFF면: 자동 파싱·트립생성 전부 스킵
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in TAXI_APPS) return
        // [관리자 게이트] 접근성 켠 뒤에 관리자 해금한 경우 + [#103] 재부팅 대기 상태에서 첫 택시앱 이벤트 → 여기서 1회 지연 시작.
        if (!locStarted) { try {
            startForegroundService(Intent(this, LocationTrackingService::class.java)); locStarted = true
            try { startService(Intent(this, com.callradar.app.FloatingTripService::class.java)) } catch (e: Exception) {}   // 배지도 이때 함께
        } catch (e: Exception) {} }
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

        // [유저제보 103] "운행 중에 우버나 티머니 들어가면 자동기록이 끊긴다"
        //  원인: 여기서 화면에 뜬 앱으로 lastPlatform 을 **매 이벤트 덮어썼다.**
        //  기사는 카카오T·우버·티머니를 동시에 켜두고 먼저 잡히는 콜을 받는다.
        //  카카오 운행 중에 티머니를 열어보면 lastPlatform 이 '티머니고'로 바뀌고,
        //  그 뒤 티머니의 '콜 리스트'(=대기화면)가 진행 중인 **카카오 트립을 취소**시켰다.
        //  우버 홈('온라인 상태입니다')은 카카오 트립을 **완료로 마감**시켰다.
        //  → 운행 중에는 트립의 플랫폼을 고정한다. 화면에 뜬 앱은 lastTaxiPlatform 에만 둔다.
        lastTaxiPlatform = PLATFORM_NAMES[pkg] ?: "카카오T"
        if (lastTripId <= 0 || tripPlatform.isEmpty()) lastPlatform = lastTaxiPlatform

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedText = event.contentDescription?.toString()
                ?: event.text?.firstOrNull()?.toString() ?: ""
            Log.d(TAG, "클릭 감지: $clickedText")
            // [v3.1t] 우버 완료 버튼 클릭 → 즉시 금액 읽기
            // 구버전: "일반 콜 운행완료" / 신버전: "일반 콜 완료"(수금화면) 둘 다 감지
            if (clickedText.contains("운행 완료") || clickedText.contains("운행완료") ||
                (clickedText.contains("콜") && clickedText.contains("완료"))) {
                Log.d(TAG, "우버 완료 클릭! 즉시 금액 읽기")
                //  [103] 다른 앱에서 누른 '완료'는 내 트립의 완료가 아니다.
                if (lastTripId > 0 && screenOwnsTrip(pkg)) {
                    try {
                        val root = rootInActiveWindow
                        if (root != null) {
                            val fareLines = mutableListOf<String>()
                            fun t(n: android.view.accessibility.AccessibilityNodeInfo?) { n ?: return; n.text?.toString()?.trim()?.let { if (it.isNotEmpty()) fareLines.add(it) }; for (i in 0 until n.childCount) t(n.getChild(i)) }
                            t(root)
                            // [v99 #12559] 이 경로는 pkg 를 안 넘겨서 우버 하한이 안 걸리고 있었다(수정 반쪽).
                            //  pkg 를 넘기면 isUberFareScreen 허용목록까지 같이 켜져 기존 동작이 바뀌므로,
                            //  하한만 호출부에서 적용한다.
                            val fare = if (pkg == UBER) uberFloor(extractFare(fareLines)) else extractFare(fareLines)
                            sendDebugLog("CLICK_END", "$lastPlatform | $clickedText | ${fare}원")
                            finalizeCurrentTrip(fare)
                        }
                    } catch (e: Exception) { Log.e(TAG, "완료 금액 읽기 실패: ${e.message}") }
                }
                return
            }
            // ── [유저제보] 티머니 콜 취소를 못 잡는다 ────────────────────────────
            //  티머니고의 취소는 '콜 취소' → **'승객 신고 사유' 창**(승객 없음/승객 취소/…) → [신고] 다.
            //  지금까지는 '콜 리스트'(대기화면)로 돌아오는 것만 보고 있었는데,
            //  이 신고 창을 거치는 동안 화면 텍스트가 달라서 취소를 놓쳤다.
            //  창이 뜬 것만으로는 취소가 아니다(기사가 [취소]로 닫을 수 있다).
            //  **[신고] 버튼 클릭**이라는 확실한 신호에서만 트립을 지운다.
            if (clickedText.trim() == "신고" && cancelDialogSeenAt > 0 &&
                now - cancelDialogSeenAt < 120000L && lastTripId > 0 && screenOwnsTrip(pkg)) {
                Log.d(TAG, "🚫 티머니 승객신고(콜취소) 확정 → 트립 취소")
                sendDebugLog("CANCEL_REPORT", "#$lastTripId | $lastPlatform | 승객 신고로 콜 취소")
                cancelDialogSeenAt = 0L
                deleteCurrentTrip()
                return
            }
            // [취소후 새콜] '이 손님 다시 만나지 않기'(승객 차단)는 '손님'이 들어가지만 탑승/길안내가 아님 → 제외.
            //   이걸 클릭으로 오인하면 감지 억제창이 걸려 취소 직후 새 콜을 못 잡는다.
            if ((clickedText.contains("길안내") || clickedText.contains("탑승") || clickedText.contains("손님"))
                && !clickedText.contains("만나지") && !clickedText.contains("다시 만나") && !clickedText.contains("차단")) {
                Log.d(TAG, "길안내/탑승/손님 버튼 클릭! 즉시 파싱")
                if (!clickedText.contains("길안내")) sendDebugLog("CLICK", "$lastPlatform | $clickedText")  // [perf] 길안내 연타는 로그 안 함(과다 네트워크 제거), 탑승/손님만
                // 손님 탑승 클릭 시 → 이 운행은 실제 운행 (장거리/정체여도 취소 방지)
                // [정확도] 실제 '탑승' 클릭 순간 = 진짜 픽업 지점. 출발지를 여기로 다시 찍어 콜수락~픽업 빈이동을 출발지에서 제외.
                if (clickedText.contains("탑승")) {
                    // [R1 유령트립 자동회복] 이전 트립이 이미 탑승완료로 열려있는데(종료 놓침) 또 '손님 탑승'이 눌리면
                    //   = 새 손님/새 콜. 유령 트립을 캐시요금으로 마감하고 새 트립이 생기게 한다(그냥 두면 새 콜이 유령에 합쳐짐).
                    //   시간이 아니라 '새 탑승'이라는 양성 신호로만 동작 → 장거리(공항) 운행 오인 종료 없음.
                    //   30초 가드: 접근성이 같은 클릭을 중복 발생시켜 방금 시작한 트립을 마감하는 것 방지.
                    if (lastTripId > 0 && passengerBoarded && System.currentTimeMillis() - tripStartedAt > 30000L) {
                        Log.d(TAG, "🔁 새 손님 탑승인데 이전 트립 #$lastTripId 미마감 → 다음 스캔에 유령 마감+새 트립")
                        sendDebugLog("STALL_RECOVER", "#$lastTripId | 새 탑승 감지 → 유령 마감 예약")
                        forceNewTripOnNextScan = true
                        carryBoardToNextTrip = true
                    }
                    passengerBoarded = true; autoBoarded = true; Log.d(TAG, "✅ 손님 탑승(클릭) → 취소방지 활성 + 출발지 재설정")
                    restampOriginAtBoarding()
                    saveTripState()   // [103-2] 탑승상태·재설정 출발지를 디스크에
                }
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

    /**
     * [유저제보 103] 이 화면(pkg)이 **지금 진행 중인 트립의 앱**인가?
     *
     * 기사는 카카오T·우버·티머니를 동시에 켜두고 먼저 잡히는 콜을 받는다.
     * 그래서 카카오 운행 중에 우버·티머니를 열어보는 건 아주 흔한 일이다.
     * 그런데 남의 앱 대기화면은 진행 중인 내 트립에 대해 **아무 정보도 주지 않는다.**
     *   · 티머니 '콜 리스트'  → 카카오 트립을 취소(삭제)시켰다
     *   · 우버 홈 '온라인 상태입니다' → 카카오 트립을 완료로 마감시켰다
     * 둘 다 "운행 중에 다른 앱 들어가면 자동기록이 끊긴다"로 나타난다.
     *
     * tripPlatform 이 비어 있으면(구버전 상태 복원 등) 판단할 근거가 없으므로 막지 않는다.
     * 막는 쪽으로 기울면 정상 마감까지 놓쳐 유령트립이 되므로, 확실할 때만 막는다.
     */
    private fun screenOwnsTrip(pkg: String): Boolean {
        if (lastTripId <= 0 || tripPlatform.isEmpty()) return true
        return (PLATFORM_NAMES[pkg] ?: "") == tripPlatform
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

            // [v53] 화면주소 파싱 제거 — 가맹 화면 라벨('출발/도착')을 POI로 오긁는 회귀(#101).
            //   주소는 전부 GPS 역지오코딩(탑승·완료 순간)만 사용한다. extractScreenAddress는 더 이상 호출하지 않음.

            // [티머니 취소] 승객 신고 사유 창 감지 — 창 자체는 취소가 아니고, [신고] 클릭의 근거로만 쓴다.
            if (allText.contains("승객 신고 사유") || allText.contains("승객 없음") ||
                (allText.contains("부당 요구") && allText.contains("다른 승객 탑승"))) {
                cancelDialogSeenAt = System.currentTimeMillis()
                sendDebugLog("CANCEL_DIALOG", "$lastPlatform | 승객 신고 사유 창 감지")
            }
            if (lastTripId <= 0 && allText.contains("라이더") && allText.contains("평가")) return

            // 운행 중인데 대기 화면 감지 = 취소 완료
            //  [103] 단, **그 트립을 만든 앱의 대기화면일 때만** 취소로 본다.
            //   카카오 운행 중에 티머니 '콜 리스트'를 열어본 것은 카카오 콜 취소가 아니다.
            if (lastTripId > 0 && screenOwnsTrip(pkg)) {
                val isCancelledToIdle = when (pkg) {
                    TMONEYGO, TMONEYGO_NAVI -> allText.contains("콜 리스트") && !allText.contains("출발지 길안내") && !allText.contains("목적지 길안내") && !allText.contains("승객 탑승")
                    KAKAO_TAXI -> allText.contains("콜 대기") || allText.contains("퇴근하기")
                    else -> false
                }
                // [v3.1v] 손님 미탑승 시 즉시취소 감지 (기존 60초 → 5초)
                // 카카오T는 콜 수락 직후 대기화면이 안 뜨므로 오탐 위험 낮음
                // 손님 탑승한 경우는 아래 passengerBoarded 분기에서 보호됨
                val minCancelMs = if (passengerBoarded) 60000L else 5000L
                val elapsedMs = System.currentTimeMillis() - tripStartedAt
                if (isCancelledToIdle && elapsedMs > minCancelMs) {
                    if (passengerBoarded) {
                        // 손님 탑승한 운행은 대기화면 스쳐도 취소 안함 (인천공항/지방/정체 대응)
                        Log.d(TAG, "대기화면 감지했으나 손님 탑승상태 → 취소 무시")
                    } else if (elapsedMs > LATE_CANCEL_MS) {
                        /* ★★ 오래 달린 트립은 **지우지 않는다** (2026-08-31 유저 108 제보)
                         *
                         *  제보: "의정부 카카오 자동결제였는데 콜잡고 운행중 떠 있다 어느순간 사라졌습니다."
                         *  누락이 아니라 **잡혔다가 지워진 것**이었다. 탑승 신호는 '손님 탑승' 클릭이나
                         *  '밀어서 운행종료' 화면글자로만 서는데, 장거리 편도에서 내비만 보고 달리면 둘 다 안 잡힌다.
                         *  그 상태에서 **복귀콜을 찾으려고 카카오를 여는 순간** 대기화면이 보여 운행이 지워졌다.
                         *
                         *  서버 실측(60일 872건)이 문턱을 정해줬다 — 취소의 **90.7%가 10분 안에** 일어난다.
                         *    1분미만 51.6% · 1-3분 18.8% · 3-5분 9.6% · 5-10분 10.7% ┃ 그 뒤 꼬리 9.3%
                         *  그 꼬리 81건이 오탐이었다(최장 **233분**짜리도 있었다. 9명이 당했다).
                         *
                         *  ★ 지우는 건 확실할 때만. 애매하면 남긴다.
                         *    남은 유령은 0원이라 서버가 다음 콜에 auto_closed 로 닫고 통계에서 빠진다(정관).
                         *    반대로 지워버린 74,600원은 되돌릴 방법이 없다. 손해가 한쪽으로 훨씬 크다. */
                        if (cancelSkipTripId != lastTripId) {
                            cancelSkipTripId = lastTripId      // 프레임마다 찍히지 않게 한 번만
                            Log.d(TAG, "대기화면 감지했으나 ${elapsedMs/60000}분 경과 → 취소 아님(운행 유지)")
                            sendDebugLog("CANCEL_SKIP", "#$lastTripId | $lastPlatform | ${elapsedMs/60000}분 경과 → 삭제 안 함")
                        }
                    } else {
                        Log.d(TAG, "⚠️ 운행 중 대기화면 → 취소 감지")
                        sendDebugLog("CANCEL_END", "#$lastTripId | $lastPlatform | 대기화면복귀(미탑승) | ${elapsedMs/1000}초")
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
                        // [v99 #12559] 하한 1000 → 3000. 이 화면은 기사가 **타이핑하는 중**인 입력칸이라
                        //  21,500을 치는 동안 2,150 같은 중간값이 그대로 잡혀 서버에 굳는다.
                        //  실측(유저#833, 47분 서울 운행): FARE_CACHE 2150 하나만 오고 최종 프레임은 아예 안 왔다
                        //  → "매 프레임 덮어쓰니 마지막엔 맞아진다"는 전제가 성립하지 않는다(다이얼로그가 닫히며 이벤트 끊김).
                        //  미터요금은 기본요금(전국 3,800~4,800)보다 낮을 수 없으므로 3000 미만은 중간값으로 본다.
                        //  (v100: 그 복구 경로는 누적값을 긁어 요금을 덮어써서 제거했다 — 유저 592 제보.)
                        if (v in 3000..500000 && v > meterFare) meterFare = v  // 최댓값 = 미터요금(통행료보다 큼)
                    }
                }
                // 라벨 못 찾으면 폴백. 여기도 같은 입력 화면이므로 중간값 하한을 건다.
                if (meterFare == 0) meterFare = uberFloor(extractFare(lines, pkg))
                // [유저592 제보] 우버 현금 운행이 '자동결제'로 기록되던 문제.
                //  실제 화면 구분(스크린샷 확인):
                //   · 자동결제 → 금액칸 아래 "자동 결제" 배지 + "일반 콜 운행완료" 버튼
                //   · 직접결제(현금·카드) → "미터 요금만 입력" 제목 + "확인하고 계속하기" 버튼
                //  '자동 결제' 문구가 없으면 기사가 직접 받는 운행이므로 card로 둔다(현금도 여기 포함).
                run {
                    val flat = allText.replace(" ", "")
                    uberPayType = if (flat.contains("자동결제")) "auto" else "card"
                }
                // [통행료] 우버는 통행료를 '요금 입력' 화면에 띄운다(종료 화면이 아니다).
                //  종료 신호가 올 때는 이미 이 화면을 지나갔으므로, 여기서 봐두지 않으면 놓친다.
                //  통행료 없는 운행이면 "발생하지 않았습니다"라 0이 나오고 캐시는 그대로 0이다.
                extractToll(allText).let { tv -> if (tv > 0) pendingToll = tv }
                if (meterFare > 0) {
                    lastDetectedFare = meterFare
                    // [우버0원수정] 요금을 '본 순간' 서버 트립에 바로 기록 → 완료 감지가 어긋나도 요금 유실 안 됨.
                    //   (finalize는 fare>0일 때만 덮으므로 이 값이 0으로 지워지지 않는다.)
                    if (lastTripId > 0 && meterFare != lastUberWrittenFare) { lastUberWrittenFare = meterFare; updateTripFare(lastTripId, meterFare) }
                    Log.d(TAG, "💰 우버 미터요금 캐싱: ${meterFare}원")
                    sendDebugLog("FARE_CACHE", "우버 미터요금 | ${meterFare}원 | " + allText.take(100))

                    /* ★★ [v100] 우버 연속배차 — 미터 입력창을 **완료로 본다** (유저 592 제보).
                     *
                     *  대표 설명: 우버는 **도착 전에 다음 콜을 미리 배정**한다. 기사는 미리 받아두고,
                     *  하던 운행을 종료·결제한 뒤, 미리 받은 콜의 손님을 태우러 간다.
                     *  → **미터 입력창은 언제나 직전 운행이 끝난 뒤에만 뜬다.**
                     *
                     *  예전엔 여기서 `return // 아직 완료 아님` 하고 **우버 홈·평가 화면**을 기다렸다.
                     *  그런데 연속배차에선 홈으로 안 돌아가고 바로 다음 콜로 간다 → 트립이 안 닫히고,
                     *  다음 콜의 미터가 같은 트립에 계속 덮어썼다.
                     *
                     *  실측(592, 8/29 UTC): #13604 한 트립에 세 콜이 들어갔다.
                     *    18:23 8,200원 ("다음 콜 수락 완료" 표시) → 18:43 8,400원 → 18:57 8,800원
                     *    DB 최종: 46분 · 8,800원 한 건. **실제 25,400원 중 16,600원이 사라졌다.**
                     *
                     *  카카오는 '손님 탑승' 클릭이 있어 R1(새 탑승 → 이전 트립 마감)이 돌지만
                     *  **우버엔 그 버튼이 없어 R1이 한 번도 안 돈다.** 그래서 우버만 이 증상이 난다.
                     *
                     *  중간값 위험은 이미 두 겹으로 막혀 있다 — 하한 3,000(#12559 대응)과
                     *  300초 수정창(`recentFinalTripId`)이 최종 확인 금액으로 되잡는다. */
                    if (lastTripId > 0 && screenOwnsTrip(pkg)) {
                        sendDebugLog("UBER_METER_END", "#$lastTripId | ${meterFare}원 | 미터입력창=완료(연속배차 대응)")
                        uberMeterScreenUntil = System.currentTimeMillis() + 90_000L   // 아래 참조
                        finalizeCurrentTrip(meterFare)
                    }
                }
                /* 이 창이 2초 안에 여러 번 다시 그려진다(실측: 18:23:02·03·04 세 번).
                 * 첫 이벤트에서 마감하면 나머지가 `lastTripId<=0` 을 보고 **그 자리에서 헛 트립**을 만든다.
                 * 그래서 창이 떠 있는 동안은 새 트립 생성을 막는다(아래 생성 지점에서 확인). */
                uberMeterScreenUntil = System.currentTimeMillis() + 90_000L
                return
            }

            // [#4116 미터기 수정결제] 방금 마감한 트립이 있는데, 최종 확인화면 금액이 다르면 그 값으로 갱신.
            //  예: '손님이 직접결제 하셨나요? 미터기 4,800'으로 마감 → 기사가 '입력하신 요금이 맞습니까? 7,800' 수정결제 → 7,800으로 갱신.
            if (recentFinalTripId > 0 && System.currentTimeMillis() - recentFinalAt < 300000L) {   // [우버0원] 복구창 300초 — 이 블록은 '수정결제 재확인' 용이다(홈 긁기는 v100에서 제거)
                val hasFinal = allText.contains("입력하신 요금이 맞습니까") || allText.contains("자동결제 완료") || allText.contains("결제요청")
                val finFare = extractFare(lines, pkg)
                if (hasFinal && finFare > 0 && finFare != recentFinalFare) {
                    val tid = recentFinalTripId; recentFinalFare = finFare
                    Log.d(TAG, "💱 수정결제 감지: #$tid 요금 갱신 → ${finFare}원")
                    sendDebugLog("FARE_FIX", "#$tid | ${finFare}원 (수정결제)")
                    updateTripFare(tid, finFare)
                }
                /* [v100 제거] '우버 홈 마지막 운행 ₩X' 복구 경로를 없앴다.
                 *
                 *  유저 592 제보(2026-08-30): **8,700원짜리가 저절로 36,800원으로 바뀌었다.**
                 *  36,800 은 그날 우버 매출 합계였다 — 개별 요금이 아니라 **누적값을 긁은 것**이다.
                 *
                 *  원인: "마지막 운행" 이라는 글자의 **윗줄**(lines[idx-1])을 요금으로 썼다.
                 *  우버 홈에서 그 자리에 누적 수입이 오면 그대로 들어가고, 범위(1000~500000)
                 *  안이라 걸러지지도 않는다. 그리고 **조용히 덮어쓴다.**
                 *
                 *  v99 에서 우버 하한을 3,000 으로 올리며 recentFinalFare == 0 으로 남는 경우가
                 *  늘어 이 경로가 더 자주 돌았다. 원래 있던 버그인데 노출을 키운 게 v99 다.
                 *
                 *  왜 고치지 않고 없애나 — 정관 원칙: **틀린 숫자를 조용히 넣는 것보다 빈 칸이 낫다.**
                 *  기사는 왜 바뀌었는지 모르고, 못 알아채면 매출이 틀어진 채로 남는다.
                 *  0원 트립은 **카드 승인 알림 캡처**가 채운다(실제 결제액이라 화면 파싱보다 정확하다). */
            }

            // 완료/결제 신호
            //  [103] 남의 앱 화면은 내 트립을 마감시킬 수 없다.
            //   특히 우버 홈('온라인 상태입니다' + '마지막 운행')은 우버를 안 쓰는 순간에도 떠서,
            //   진행 중인 카카오·티머니 트립을 60초 뒤 마감해버렸다.
            val isCompletionSignal = if (!screenOwnsTrip(pkg)) false else when (pkg) {
                UBER -> allText.contains("영수증") ||
                    allText.contains("결제 완료") || allText.contains("운행이 완료") ||
                    ((allText.contains("운행 완료") || allText.contains("운행완료")) && extractFare(lines, pkg) > 0) ||
                    ((allText.contains("콜 완료") || allText.contains("수금")) && extractFare(lines, pkg) > 0) ||
                    (allText.contains("라이더") && allText.contains("평가해 주세요")) ||
                    // [우버 스톨 회복] 라이더평가 없이 idle 홈("온라인 상태입니다")으로 복귀 = 운행 끝났는데 종료 놓침.
                    //   활성 트립화면 아니고, 60초+ 지난 트립만 → 짧은 오탐/장거리 오인 방지. 요금은 캐시/이미 기록된 값으로 마감.
                    (allText.contains("온라인 상태입니다") && allText.contains("안전 도구 키트") && (allText.contains("마지막 운행") || allText.contains("수입 동향") || allText.contains("오늘 중"))
                        && !allText.contains("미터 요금만 입력") && !allText.contains("승객 탑승") && !allText.contains("운행 시작") && !allText.contains("요금 입력하기")
                        && System.currentTimeMillis() - tripStartedAt > 60000L)
                TMONEYGO, TMONEYGO_NAVI -> allText.contains("자동결제 완료") ||
                    // [티머니고] 완료화면이 결제방식별로 여러 버전 — 금액 미표시 → 마감만(ended_at). 금액은 카드알림이 채움.
                    //   ①카드결제기 결제 ②도착완료(미터기 지불 후) 두 변주 모두 잡는다.
                    allText.contains("카드결제기에서 요금 결제") ||
                    (allText.contains("도착완료") && allText.contains("미터기 지불")) ||
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
                    // [로그정확도] 완료화면(우버 라이더평가 등)엔 금액이 안 떠서 fare=0이 되지만
                    //   실제 저장은 캐시된 요금(lastDetectedFare)으로 마감된다 → 로그도 실제 저장값을 찍는다.
                    val loggedFare = if (fare > 0) fare else lastDetectedFare
                    Log.d(TAG, "✅ 운행 종료 신호 ($lastPlatform), 마감 (요금: ${loggedFare}원)")
                    sendDebugLog("TRIP_END", "#$lastTripId | ${loggedFare}원")
                    sendDebugLog("END_SCREEN", allText.take(300))
                    // 통행료는 기사가 대신 낸 돈이라 매출이 아니다. 운행 기록에 별도로 붙여둔다.
                    //  (매출은 미터요금만. 지출 장부 반영은 서버가 처리한다)
                    //  종료 화면에 없으면 앞선 요금입력 화면에서 봐둔 값을 쓴다.
                    val toll = extractToll(allText).let { if (it > 0) it else pendingToll }
                    if (toll > 0 && lastTripId != lastTollTripId) {
                        lastTollTripId = lastTripId
                        sendDebugLog("TOLL", "#$lastTripId | ${toll}원")
                        putTripToll(lastTripId, toll)
                    }
                    finalizeCurrentTrip(fare)
                }
                return
            }

            // 활성 콜 화면 판단 — v3 원본 그대로 + "손님 탑승" 추가
            val isActiveCallScreen = when (pkg) {
                UBER -> allText.contains("탑승 완료") || allText.contains("승객 탑승") ||
                    allText.contains("운행 시작") || allText.contains("요금 입력하기")   // [v53 #124-2] '목적지+도착' 단독조건 제거 — 우버 내비 잔여알림('목적지 도착/안내종료') 유령콜 방지
                TMONEYGO, TMONEYGO_NAVI -> (allText.contains("출발지 길안내") || allText.contains("목적지 길안내"))
                    && !allText.contains("밀어서 운행종료")
                    && !allText.contains("공지") && !allText.contains("미션")
                    && !allText.contains("Samsung") && !allText.contains("카카오톡")
                // [v3.1 수정] "손님 탑승"/"손님탑승" 추가
                else -> ((allText.contains("길안내") && allText.contains("탑승"))
                    || allText.contains("손님 탑승") || allText.contains("손님탑승"))
                    && !allText.contains("밀어서 운행종료")
                    && !allText.contains("콜 대기") && !allText.contains("배차")
                    && !allText.contains("콜멈춤") && !allText.contains("수락")   // [#7 오탐] 가맹 콜노출/미수락(콜멈춤·수락 버튼) 화면은 트립 아님
                    && !allText.contains("자동배차") && !allText.contains("목적지 부스터") && !allText.contains("자동노출")   // [#7-2] 가맹 자동배차 콜카드(목적지부스터/자동노출) 오탐 제외
            }

            if (!isActiveCallScreen) {
                Log.d(TAG, "콜 화면 아님 -> 무시")
                return
            }

            // 화면 텍스트로 탑승 감지 (우버 등 클릭 대신 화면표시 방식 커버).
            // [정확도 수정] 카카오T "손님 탑승"은 픽업 가는 중에도 화면에 뜨는 '버튼 글자'라 오탐 → 제외.
            //   [정확도 수정2] '승객 탑승'도 티머니고가 픽업 가는 중 버튼으로 띄워서 오탐 → 화면글자에서 제외.
            //   카카오T '손님 탑승'/티머니고 '승객 탑승'은 실제 '클릭'(위 분기)에서만 탑승 처리.
            //   여기선 이미 목적지행 운행에 진입한 확실한 신호만: 우버 '탑승 완료', 카카오T '밀어서 운행종료'.
            if (!passengerBoarded && lastTripId > 0 && (
                    allText.contains("탑승 완료") ||
                    allText.contains("밀어서 운행종료"))) {
                passengerBoarded = true; autoBoarded = true
                Log.d(TAG, "✅ 화면에서 탑승 감지(운행상태) → 취소방지 활성 + 출발지 재설정")
                restampOriginAtBoarding()
            }

            // [v93] 미출근 상태에선 LocationTrackingService가 꺼져 있다(신고한 대로 근무 중에만 수집).
            //  그런데 자동출근은 바로 이 상황 — 미출근인데 콜이 잡힌 순간 — 을 위해 있는 기능이다.
            //  좌표가 없거나 낡았으면 여기서 서비스를 깨우고 즉시 1회 위치를 받아 출발지를 메운다.
            //  (아래 '좌표없음/스테일 → return' 방어는 그대로 둔다. 유령 트립을 막는 장치라 없애면 안 된다.)
            if (LocationTrackingService.currentLat == 0.0 ||
                System.currentTimeMillis() - LocationTrackingService.lastLocationTime > 5 * 60 * 1000L) {
                ensureWorkStarted()          // 근무를 먼저 켠다 → 아래 서비스가 게이트에 걸리지 않는다
                wakeLocationForCall()
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

            // [v100] 우버 미터 입력창 직후엔 새 트립을 만들지 않는다(창 재렌더로 헛 트립 방지).
            //  다음 콜은 기사가 픽업하러 이동해 화면이 바뀐 뒤에 정상적으로 생성된다.
            if (System.currentTimeMillis() < uberMeterScreenUntil && allText.contains("미터 요금만 입력")) {
                return
            }
            if (lastTripId <= 0 || forceNewTripOnNextScan) {
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
            // [R1] 유령 자동회복이면 캐시요금으로 마감(추후 결제화면 뜨면 FARE_FIX가 보정), 아니면 기존대로 0
            val carryFare = if (forceNewTripOnNextScan) lastDetectedFare else 0
            Log.d(TAG, "⚠️ 이전 트립 #$lastTripId 미종료, 강제 마감")
            sendDebugLog("FORCE_END", "#$lastTripId | 새콜시작으로 강제종료(${carryFare}원)")
            finalizeCurrentTrip(carryFare)
            Thread.sleep(500)
        }
        forceNewTripOnNextScan = false  // [R1] 소비(한 번만)
        isSendingTrip = true
        tripStartedAt = System.currentTimeMillis()
        passengerBoarded = false; autoBoarded = false; boardedAtSent = false  // 새 운행 시작 → 탑승 플래그 리셋(공차부터)
        uberPayType = null   // [유저592] 새 운행 → 이전 콜의 결제수단 판정이 남지 않게 초기화
        pendingToll = 0      // [통행료] 앞 운행의 통행료가 다음 운행에 딸려붙지 않게 초기화
        if (carryBoardToNextTrip) { passengerBoarded = true; autoBoarded = true; carryBoardToNextTrip = false }  // [R1] 유령 마감 후 이어진 새 트립은 이미 탑승상태 → 취소방지 유지
        if (lastPlatform == UBER) { passengerBoarded = true; autoBoarded = true }  // [v53 #124] 우버는 배차=이미 운행(픽업신호 없음) → 즉시 실차 태깅(회색 궤적/실차거리 누락 방지)
        originRestamped = false   // 새 운행 → 출발지 재설정 대기
        lastUberWrittenFare = 0   // [우버0원수정] 새 트립 → 서버기록값 추적 초기화
        recentFinalTripId = -1    // [#4116] 새 운행 시작 → 이전 마감 요금갱신 추적 종료(교차오염 방지)
        ensureWorkStarted()       // [근무 자동출근] 자동기록만 써도 근무세션이 시작/재개되게
        originLat = lat
        originLng = lng
        Thread {
            try {
                // [v53] 화면주소 파싱 제거 — 트립 생성 위치 GPS 역지오코딩(탑승 순간 restamp로 픽업 확정).
                val oName = reverseGeocode(lat, lng) ?: ""
                val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                val userId = prefs.getString("user_id", null)
                // [자동기록 배지] 출발/현재 동을 prefs에 저장 → 플로팅이 읽어 '출발동→현재동' 표시(추가 네트워크 0).
                prefs.edit().putString("auto_origin_dong", oName).putString("auto_cur_dong", oName).apply()
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
                        saveTripState()   // [103-2] 앱이 죽어도 이 운행을 이어받을 수 있게
                        Log.d(TAG, "🚕 새 트립: #$lastTripId | $oName | $lastPlatform")
                        sendDebugLog("TRIP_START", "#$lastTripId | $lastPlatform | 출발 $oName")
                        if (lastPlatform == UBER && !boardedAtSent) { boardedAtSent = true; markBoardedAtNow(lastTripId) }  // [v53 #124] 우버 실차 시작시각 즉시 기록(실차율 정확도)
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
                // [v53] 화면주소 파싱 제거 — GPS 역지오코딩만.
                val destName = reverseGeocode(lat, lng)
                val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                val userId = prefs.getString("user_id", null)
                // [자동기록 배지] 현재 위치 동 갱신(30초 주기) → 플로팅이 실시간 표시.
                if (!destName.isNullOrEmpty()) prefs.edit().putString("auto_cur_dong", destName).apply()
                // [v92] 지오코딩이 실패해도 좌표는 보낸다. 이름을 못 얻은 것과
                //  '어디서 내렸는지 모른다'는 건 다른 얘기다. (마감 로직과 동일한 수정)
                val json = JSONObject().apply {
                    put("user_id", userId)
                    if (!destName.isNullOrEmpty()) put("destination", destName)
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
                // [귀로내비] 목적지 확정 → 복귀 전망 알림(트립당 1회, 좌표 기준 — 동명이역 방지).
                //  이름을 못 얻었으면 알릴 내용이 없으니 생략한다(좌표 저장은 위에서 이미 끝냄).
                if (!destName.isNullOrEmpty()) announceReturnOutlook(tripId, destName, lat, lng)
            } catch (e: Exception) {
                Log.e(TAG, "목적지 갱신 실패: ${e.message}")
            } finally {
                tripDestUpdateInFlight = false
            }
        }.start()
    }

    // [귀로내비] 목적지 확정 시 복귀 전망을 알림으로 — "도착지 다음 콜 평균 N분 · 근처 더 나은 곳".
    //  트립당 1회만(재갱신 시 중복 방지). 서버 기여 게이트(locked)면 조용히 생략. 실패는 무시(운행 방해 금지).
    private val outlookAnnounced = HashSet<Int>()
    private fun announceReturnOutlook(tripId: Int, destName: String, dLat: Double = Double.NaN, dLng: Double = Double.NaN) {
        if (tripId <= 0 || destName.isBlank()) return
        synchronized(outlookAnnounced) { if (!outlookAnnounced.add(tripId)) return }
        Thread {
            try {
                val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                val userId = prefs.getString("user_id", "") ?: return@Thread
                if (userId.isEmpty()) return@Thread
                val dong = destName.trim().split(" ")[0]
                // [동명이역] 목적지 좌표 전달 → 서버가 좌표 3km 기준으로 통계(신사동 강남/은평 혼선 방지)
                val coordQ = if (!dLat.isNaN() && !dLng.isNaN()) "&lat=$dLat&lng=$dLng" else ""
                val body = (URL("$SERVER_URL/api/return-outlook/$userId?dest=" + java.net.URLEncoder.encode(dong, "UTF-8") + coordQ).openConnection()
                    .apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection)
                    .apply { connectTimeout = 8000; readTimeout = 20000 }.inputStream.bufferedReader().readText()
                val o = org.json.JSONObject(body)
                if (o.optBoolean("locked")) return@Thread
                val gap = o.optDouble("avgGap", -1.0)
                val nb = o.optJSONArray("nearby")
                // [유저지시⑩] 이 동+시간대 데이터 없으면 어설픈 수치 금지 — 근처(3km) 실측 추천이 있으면 그것만, 그것도 없으면 침묵.
                var txt: String
                if (gap > 0) {
                    txt = "$dong 도착 후 다음 콜까지 평균 ${gap.toInt()}분"
                    if (nb != null && nb.length() > 0) {
                        val e = nb.getJSONObject(0)
                        if (e.optDouble("gap", 99.0) < gap - 3) txt += " · 더 나은 곳: ${e.optString("name")} ${e.optDouble("gap",0.0).toInt()}분(${e.optDouble("km",0.0)}km)"
                    }
                } else if (nb != null && nb.length() > 0) {
                    val e = nb.getJSONObject(0)
                    txt = "$dong 데이터 없음 · 근처 추천: ${e.optString("name")} ${e.optDouble("gap",0.0).toInt()}분(${e.optDouble("km",0.0)}km)"
                    if (nb.length() > 1) { val e2 = nb.getJSONObject(1); txt += " / ${e2.optString("name")} ${e2.optDouble("gap",0.0).toInt()}분" }
                } else return@Thread
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                    nm.createNotificationChannel(android.app.NotificationChannel("callradar_outlook", "귀로 전망", android.app.NotificationManager.IMPORTANCE_DEFAULT))
                    val n = android.app.Notification.Builder(this, "callradar_outlook")
                        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                        .setContentTitle("🧭 귀로 전망")
                        .setContentText(txt)
                        .setStyle(android.app.Notification.BigTextStyle().bigText(txt))
                        .setAutoCancel(true)
                        .build()
                    nm.notify(7777, n)
                }
                Log.d(TAG, "🧭 귀로 전망 알림: $txt")
            } catch (e: Exception) { Log.d(TAG, "귀로 전망 생략: ${e.message}") }
        }.start()
    }

    // [#4 실차율] 탑승 시각(boarded_at)만 즉시 서버에 기록 — 출발지 재설정(거리 조건)과 무관하게 실차 시작시각 확보.
    private fun markBoardedAtNow(tripId: Int) {
        Thread {
            try {
                val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                val userId = prefs.getString("user_id", null)
                val json = JSONObject().apply {
                    put("user_id", userId)
                    put("boarded_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
                }
                val conn = (URL("$SERVER_URL/api/trips/$tripId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                    requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 12000; readTimeout = 12000
                }
                conn.outputStream.write(json.toString().toByteArray()); conn.responseCode; conn.disconnect()
                Log.d(TAG, "🕒 boarded_at 즉시기록: #$tripId")
            } catch (e: Exception) {}
        }.start()
    }

    // [정확도] 실제 손님 탑승 순간 = 진짜 픽업 지점. 출발지(좌표+이름)를 여기로 1회 재설정.
    //   콜수락~탑승 사이 빈 이동이 출발지로 잘못 찍히던 문제(#4) 해결. 실차 궤적/배지의 출발동도 픽업 기준이 됨.
    private fun restampOriginAtBoarding() {
        if (lastTripId <= 0) return
        // [#4 실차율] 탑승 순간 boarded_at을 즉시 1회 전송 — 픽업이 코앞(<120m)이라 아래 출발지 재설정이 보류돼도
        //   실차 시작시각은 반드시 남게(예전엔 120m 이동해야만 boarded_at이 나가 근접픽업은 실차=0으로 왜곡).
        if (!boardedAtSent) { boardedAtSent = true; markBoardedAtNow(lastTripId) }
        if (originRestamped) return
        val lat = LocationTrackingService.currentLat
        val lng = LocationTrackingService.currentLng
        if (lat == 0.0 && lng == 0.0) return   // GPS 아직 준비 안 됨 → 다음 프레임에 재시도(플래그 안 세움)
        // [준비(pending) 모드 / 다중플랫폼 겹침]
        //  우버·카카오는 '미리받기'로 앞 운행 도착 전에 다음 콜이 배정됨. 앞 운행 끝난 자리(하차 지점)에
        //  이미 떠 있던 '탑승' 버튼이 눌려도, 기사는 아직 새 픽업으로 안 갔다. 그때 출발지를 확정하면
        //  '직전 하차 자리'가 출발지로 잘못 찍힌다(#4120/#4122). → 트립 생성지점에서 충분히 이동(≈새 픽업까지
        //  실제로 감)했을 때만 출발지 확정. 아직이면 보류하고 다음 탑승 신호에 재시도한다.
        //  (originLat/Lng는 확정 전까진 '트립 생성 위치'이므로 그로부터의 이동거리로 판단. 단독 콜은 픽업까지
        //   보통 이동하므로 정상 동작하고, 픽업이 코앞이면 이동<120m라 생성위치=픽업이라 문제없다.)
        if (distanceMeters(originLat, originLng, lat, lng) < 120.0) return  // 아직 새 픽업 도착 전 → 보류(재시도)
        originRestamped = true
        originLat = lat; originLng = lng
        val tripId = lastTripId
        Thread {
            try {
                // [v53] 화면주소 파싱 제거 — 탑승 순간 GPS = 진짜 픽업지점.
                val oName = reverseGeocode(lat, lng) ?: ""
                val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                // 배지 출발동도 픽업 기준으로 교정
                prefs.edit().putString("auto_origin_dong", oName).apply()
                val userId = prefs.getString("user_id", null)
                val json = JSONObject().apply {
                    put("user_id", userId)
                    if (oName.isNotBlank()) put("origin", oName)
                    put("origin_lat", lat); put("origin_lng", lng)
                    // [실차시간 2단계] 탑승 시각 기록 → 실차 = 탑승~하차
                    put("boarded_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
                }
                val conn = (URL("$SERVER_URL/api/trips/$tripId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                    requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json")
                    doOutput = true; connectTimeout = 15000; readTimeout = 15000
                }
                conn.outputStream.write(json.toString().toByteArray())
                conn.responseCode
                Log.d(TAG, "📍 출발지 픽업지점으로 재설정: #$tripId -> $oName")
                sendDebugLog("BOARDING", "#$tripId | 탑승 픽업 $oName")
                conn.disconnect()
            } catch (e: Exception) { Log.e(TAG, "출발지 재설정 실패: ${e.message}") }
        }.start()
    }

    /**
     * [v93] 콜이 잡혔는데 좌표가 없거나 낡았을 때 — 위치를 깨운다.
     *
     * v92까지는 자동기록이 켜져 있으면 근무와 무관하게 24시간 GPS를 켜뒀다. 그래서 좌표가 늘 있었지만,
     * 퇴근한 뒤에도 비번날에도 계속 위치를 봤다. 위치기반서비스사업 신고서에는
     * "근무(운행) 상태인 동안에 한함"으로 신고해놓고 실제로는 그렇지 않았다.
     *
     * 이제 미출근이면 LocationTrackingService가 스스로 내려간다. 대신 콜이 잡히는 바로 그 순간
     * 여기서 다시 깨우고, 상시 추적을 기다리지 않도록 1회성 위치를 별도로 한 방 받아둔다.
     * 서비스가 첫 좌표를 주기까지 몇 초 걸리는데 그 사이 콜을 놓치면 안 되기 때문이다.
     *
     * 좌표를 못 받으면 호출부의 '좌표없음/스테일 → return' 방어에 걸려 트립이 안 만들어진다.
     * 그게 맞다 — 출발지 없는 유령 트립을 만드는 것보다 낫다.
     */
    private fun wakeLocationForCall() {
        try { startForegroundService(Intent(this, LocationTrackingService::class.java)) } catch (e: Exception) {}
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
            fused.getCurrentLocation(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null
            ).addOnSuccessListener { loc ->
                if (loc != null) {
                    LocationTrackingService.currentLat = loc.latitude
                    LocationTrackingService.currentLng = loc.longitude
                    LocationTrackingService.lastLocationTime = System.currentTimeMillis()
                    Log.d(TAG, "📍 콜 감지 → 즉시 위치 취득: ${loc.latitude}, ${loc.longitude}")
                }
            }
        } catch (e: Exception) { Log.e(TAG, "즉시 위치 실패: ${e.message}") }
    }

    // [근무 자동출근] 자동기록으로만 운행하는 기사는 '출근'이 안 눌려 근무세션이 방치됨(시간·시급·궤적 어긋남).
    //   → 자동기록 트립이 생기면 근무세션을 자동 시작/재개하고, 무한누적 방지로 자동마감 기본값(15h)을 건다.
    private fun ensureWorkStarted() {
        try {
            val p = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val ws = p.getLong("work_start", 0L)
            val ps = p.getLong("work_pause_start", 0L)
            var pushWs = ws; var pushPt = p.getLong("work_paused_total", 0L); var pushPs = ps
            when {
                ws == 0L -> {  // 미출근 → 자동 출근
                    pushWs = now; pushPt = 0L; pushPs = 0L
                    p.edit().putLong("work_start", now).putLong("work_paused_total", 0L).putLong("work_pause_start", 0L)
                        .putInt("work_start_fare", p.getInt("work_day_start_fare", 0))
                        /* ★ [거리게이트 선행조건] 자동출근은 지금까지 meter_local 을 안 넣었다.
                         *  WorkSessionService 가 '근무 중일 때만 누적'으로 바뀌면서(2026-10-05)
                         *  이 값이 없으면 자동기록만 쓰는 기사의 거리가 통째로 0이 된다.
                         *  자동출근을 띄운 폰이 곧 미터 소유자다. */
                        .putBoolean("meter_local", true).apply()
                    // [자동마감 기본값] 미설정일 때만. 기사가 '꺼짐'을 고른 것(work_max_hours_set)은 뒤집지 않는다.
                    if (!p.getBoolean("work_max_hours_set", false) && p.getInt("work_max_hours", 0) == 0)
                        p.edit().putInt("work_max_hours", 15).apply()
                    // [자동마감 직후 재출근 추적] 마감이 '구간 분할' 로 동작하는 것을 집계가 알 수 있게.
                    try { com.callradar.app.WorkAutoEnd.noteChainedAutoStart(this, now) } catch (e: Exception) {}
                    try { com.callradar.app.WorkSegments.open(this, now) } catch (e: Exception) {}   // [v93] 자동출근도 구간을 남긴다
                }
                // [v93] ps > 0L(일시정지 → 자동 재개) 분기 삭제.
                //  자동기록은 오탐이 난다(플랫폼 대기화면 스침, 즉시취소 콜 등). 트립이 '생겼다'는 것만으로
                //  기사가 눌러둔 일시정지를 풀면 안 된다 — 2026-08-25에 이걸로 3시간 50분이 오계상됐다.
                //  운행이 완료 마감될 때(finalizeCurrentTrip) WorkResume.resumeIfPaused()가 푼다.
                //  다만 궤적은 끊기면 안 되므로, 일시정지 중이어도 아래에서 WorkSessionService는 띄운다.
                ps > 0L -> {
                    try { startForegroundService(Intent(this, WorkSessionService::class.java)) } catch (e: Exception) {}
                    return
                }
                else -> return  // 이미 근무중
            }
            try { startForegroundService(Intent(this, WorkSessionService::class.java)) } catch (e: Exception) {}
            try { com.callradar.app.WorkAutoEnd.schedule(this, pushWs, p.getInt("work_max_hours", 15)) } catch (e: Exception) {}
            val userId = p.getString("user_id", null) ?: return
            val sf = p.getInt("work_start_fare", 0)
            val fWs = pushWs; val fPt = pushPt; val fPs = pushPs
            Thread {
                try {
                    val json = JSONObject().apply { put("user_id", userId); put("work_start", fWs); put("paused_total", fPt); put("pause_start", fPs); put("start_fare", sf) }
                    val conn = (URL("$SERVER_URL/api/work-session").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                        requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 15000; readTimeout = 20000
                    }
                    conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                    conn.responseCode; conn.disconnect()
                    Log.d(TAG, "🟢 자동 출근(근무세션 시작/재개) 서버 반영")
                } catch (e: Exception) { Log.e(TAG, "자동출근 서버반영 실패: ${e.message}") }
            }.start()
        } catch (e: Exception) { Log.e(TAG, "자동출근 실패: ${e.message}") }
    }

    // 결제화면에서 통행료 금액만 뽑는다.
    //  플랫폼마다 표기가 달라서 변형을 함께 받는다: 통행 요금 / 통행료 / 유료도로 / 하이패스
    //  라벨과 숫자 사이에 조사·서술어가 낄 수 있어(예: "통행료는 3,300원") 12자까지 건너뛴다.
    //  단 그 사이에 다른 숫자가 있으면 안 잡는다(미터요금 오독 방지).
    private val TOLL_LABELS = Regex("(?:통행\\s*요금|통행료|유료\\s*도로(?:\\s*이용료)?|하이패스)[^0-9₩\\n]{0,12}₩?\\s*([0-9,]{2,9})")

    // 우버 실제 화면(2026-08 확인): 통행료가 없으면 "해당 경로에는 통행료가 발생하지 않았습니다"라고 뜬다.
    //  라벨이 있다고 금액이 있는 게 아니므로, 부정문이면 먼저 걸러낸다.
    private val TOLL_NONE = Regex("통행료가?\\s*(?:발생하지\\s*않|없|미발생)")

    private fun extractToll(allText: String): Int {
        if (TOLL_NONE.containsMatchIn(allText)) return 0
        val m = TOLL_LABELS.find(allText) ?: return 0
        val v = m.groupValues[1].replace(",", "").toIntOrNull() ?: 0
        // 100원 미만은 오독, 10만원 초과는 미터요금을 잘못 물었을 가능성
        return if (v in 100..100000) v else 0
    }

    /**
     * 화면에서 읽은 통행료를 그 운행 기록에 붙인다.
     *
     * [v91] 예전에는 expenses에 지출로 직접 넣었는데, 오늘 기록 수정창에 수동 입력칸이
     *  생기면서 같은 통행료가 두 경로로 들어가 두 번 잡힐 수 있게 됐다.
     *  이제 trips.toll 하나만 원천으로 쓰고, 지출 장부 반영은 서버가 대신 한다
     *  (client_uuid로 트립당 1건 유지).
     */
    private fun putTripToll(tripId: Int, amount: Int) {
        Thread {
            try {
                val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                val userId = prefs.getString("user_id", null) ?: return@Thread
                val json = JSONObject().apply {
                    put("user_id", userId); put("toll", amount); put("toll_src", "screen")
                }
                val conn = (URL("$SERVER_URL/api/trips/$tripId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                    requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 15000; readTimeout = 20000
                }
                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode; conn.disconnect()
                Log.d(TAG, "🛣️ 통행료 기록: #$tripId ${amount}원")
            } catch (e: Exception) { Log.e(TAG, "통행료 기록 실패: ${e.message}") }
        }.start()
    }

    // [#4116] 마감된 트립의 요금을 서버에서 갱신(수정결제 최종금액 반영).
    private fun updateTripFare(tripId: Int, fare: Int) {
        Thread {
            try {
                val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                val userId = prefs.getString("user_id", null) ?: return@Thread
                val json = JSONObject().apply { put("user_id", userId); put("fare", fare) }
                val conn = (URL("$SERVER_URL/api/trips/$tripId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                    requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 15000; readTimeout = 15000
                }
                conn.outputStream.write(json.toString().toByteArray())
                conn.responseCode
                Log.d(TAG, "💱 트립 요금 갱신 완료: #$tripId -> ${fare}원")
                conn.disconnect()
            } catch (e: Exception) { Log.e(TAG, "요금 갱신 실패: ${e.message}") }
        }.start()
    }

    // [자동기록 배지] 트립 종료/취소 시 배지용 동 prefs 정리 → 플로팅이 '시작'으로 복귀.
    private fun clearAutoBadgePrefs() {
        try { getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).edit().remove("auto_origin_dong").remove("auto_cur_dong").apply() } catch (e: Exception) {}
    }

    // ── [유저제보 103-2] 운행 중 앱 업데이트/강제종료 → 기록이 둘로 갈라진다 ──────────
    //  "자동 운행 중 업데이트 하니 기록이 분산된다. 앱이 스스로 재시작은 했으나
    //   출발지가 다시 시작한 지점부터다"
    //
    //  원인: 트립 상태(lastTripId·출발좌표·탑승여부)가 **메모리에만** 있었다.
    //   앱을 업데이트하면 프로세스가 죽고 전부 -1 로 초기화된다.
    //   그 뒤 택시앱 화면이 다시 뜨면 '새 콜'로 보고 트립을 새로 만든다.
    //   → 한 운행이 두 건으로 갈리고, 새 건의 출발지는 재시작한 지점(=도중)이 된다.
    //   운행 중 업데이트는 흔한 일이라 이건 반드시 살아남아야 하는 상태다.
    //
    //  해결: 트립을 만들 때·손님 태울 때 디스크에 적어두고, 서비스가 다시 붙으면 이어받는다.
    //   좌표는 Float 로 저장하면 소수점이 잘려 출발지가 수십 미터 틀어지므로 문자열로 둔다.
    private fun saveTripState() {
        try {
            getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).edit()
                .putInt("auto_trip_id", lastTripId)
                .putString("auto_trip_platform", tripPlatform)
                .putLong("auto_trip_started", tripStartedAt)
                .putString("auto_trip_olat", originLat.toString())
                .putString("auto_trip_olng", originLng.toString())
                .putBoolean("auto_trip_boarded", passengerBoarded)
                .putInt("auto_trip_fare", lastDetectedFare)
                .apply()
        } catch (e: Exception) {}
    }

    private fun clearTripState() {
        try {
            getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).edit()
                .remove("auto_trip_id").remove("auto_trip_platform").remove("auto_trip_started")
                .remove("auto_trip_olat").remove("auto_trip_olng")
                .remove("auto_trip_boarded").remove("auto_trip_fare").apply()
        } catch (e: Exception) {}
    }

    /** 서비스가 다시 붙었을 때 진행 중이던 운행을 이어받는다. 이어받을 게 없으면 아무것도 안 한다. */
    private fun restoreTripState() {
        try {
            val p = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            val tid = p.getInt("auto_trip_id", -1)
            val startedAt = p.getLong("auto_trip_started", 0L)
            if (tid <= 0 || startedAt <= 0L) return
            // 6시간(MAX_TRIP_DURATION)이 지난 건 이어받지 않는다 — 어제 것이 되살아나면 더 나쁘다.
            if (System.currentTimeMillis() - startedAt > MAX_TRIP_DURATION) { clearTripState(); return }
            lastTripId = tid
            activeTripId = tid
            activeTripStartedAt = startedAt
            tripStartedAt = startedAt
            tripPlatform = p.getString("auto_trip_platform", "") ?: ""
            if (tripPlatform.isNotEmpty()) { lastPlatform = tripPlatform; lastTaxiPlatform = tripPlatform }
            originLat = p.getString("auto_trip_olat", "0")?.toDoubleOrNull() ?: 0.0
            originLng = p.getString("auto_trip_olng", "0")?.toDoubleOrNull() ?: 0.0
            passengerBoarded = p.getBoolean("auto_trip_boarded", false)
            if (passengerBoarded) autoBoarded = true
            lastDetectedFare = p.getInt("auto_trip_fare", 0)
            val mins = (System.currentTimeMillis() - startedAt) / 60000
            Log.d(TAG, "♻️ 트립 이어받음: #$tid | $tripPlatform | ${mins}분 경과")
            sendDebugLog("TRIP_RESTORE", "#$tid | $tripPlatform | ${mins}분 경과 | 탑승=$passengerBoarded")
        } catch (e: Exception) { Log.e(TAG, "트립 복원 실패: ${e.message}") }
    }

    // [v53 #124] 플로팅 배지에서 기사가 직접 거는 수동 취소 — 가맹 자동취소 놓침·유령콜 안전망.
    fun cancelActiveTripManually() {
        if (lastTripId <= 0) return
        sendDebugLog("CANCEL_MANUAL", "#$lastTripId | $lastPlatform | 배지 수동취소")
        deleteCurrentTrip()
    }

    // [완료콜누락 방어] 배지 '완료?' 탭 → 현재 진행중 트립을 정상 완료로 마감(기록 유지 · 삭제 아님).
    //  다른 앱 쓰다 '운행 완료' 접근성 클릭을 놓쳐 activeTripId가 안 풀린 케이스를 기사가 원터치로 복구.
    //  캐시된 요금(lastDetectedFare)이 있으면 살리고, 없으면 0원 마감(기록 탭에서 수정).
    fun finalizeActiveTripManually() {
        if (lastTripId <= 0) return
        sendDebugLog("FINALIZE_MANUAL", "#$lastTripId | $lastPlatform | 배지 수동완료 ${lastDetectedFare}원")
        finalizeCurrentTrip(if (lastDetectedFare > 0) lastDetectedFare else 0)
    }

    // [완료콜누락 예방] 카드결제 알림이 진행중 콜과 매칭될 때(=운행 종료 신호) 호출.
    //  요금을 지정 금액으로 완료 마감 + activeTripId 해제 + 배지 정리. '운행 완료' 접근성 탭을 놓쳐도 결제가 대신 마감.
    fun finalizeActiveTripWithFare(fare: Int): Boolean {
        if (lastTripId <= 0) return false
        sendDebugLog("FINALIZE_PAY", "#$lastTripId | $lastPlatform | 결제로 완료마감 ${fare}원")
        finalizeCurrentTrip(fare)
        return true
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
            finally { clearAutoBadgePrefs(); clearTripState(); synchronized(this) { lastTripId = -1; activeTripId = -1; lastLocalTripId = -1; lastSentDest = ""; lastSentTime = 0L; tripStartedAt = 0L; passengerBoarded = false; originLat = 0.0; originLng = 0.0; tripPlatform = ""
                // [취소후 새콜] 취소 직후 이어지는 새 콜이 즉시 감지되게 억제창·잔여상태 완전 초기화.
                clickHandledUntil = 0L; lastTriggerTime = 0L; originRestamped = false; recentFinalTripId = -1; isSendingTrip = false; screenAddrPickup = ""; screenAddrDest = ""; lastLoggedScreenAddr = "" } }
        }.start()
    }

    private fun finalizeCurrentTrip(fare: Int) {
        val tripId = lastTripId
        if (tripId <= 0) return
        // [v93 일시정지 자동해제] 운행이 실제로 완료 마감된 지금에서야 일시정지를 푼다.
        //  트립 생성(ensureWorkStarted) 시점이 아니다 — 거기선 오탐·즉시취소 콜도 통과해버렸다.
        //  취소된 트립은 deleteCurrentTrip으로 빠지므로 여기 오지 않는다.
        try { com.callradar.app.WorkResume.resumeIfPaused(this, "자동기록 운행 완료") } catch (e: Exception) {}
        val actualFare = if (fare > 0) fare else lastDetectedFare
        // [#4116] 마감 직후 '입력하신 요금이 맞습니까? Y'로 기사가 수정결제하면 Y로 갱신하기 위해 잠깐 기억.
        recentFinalTripId = tripId; recentFinalFare = actualFare; recentFinalAt = System.currentTimeMillis()
        lastTripId = -1; activeTripId = -1  // [v3.1x] 정상 종료
        lastDetectedFare = 0
        stopDestUpdateTimer()
        val lat = LocationTrackingService.currentLat
        val lng = LocationTrackingService.currentLng
        // [#679 저요금 방어] 장거리(15km+)인데 요금이 비상식(km당 500원 미만)이면 호출료만 파싱된 오기록 의심
        //  → 저장은 그대로 하되(데이터 유지) 기사에게 확인 알림. (연남→운서 50km가 3,200원으로 기록된 실사례)
        try {
            if (actualFare > 0 && originLat != 0.0 && lat != 0.0) {
                val distKm = distanceMeters(originLat, originLng, lat, lng) / 1000.0
                if (distKm >= 15.0 && actualFare < distKm * 500) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        nm.createNotificationChannel(android.app.NotificationChannel("callradar_farecheck", "요금 확인", android.app.NotificationManager.IMPORTANCE_HIGH))
                        val txt = "${String.format("%.0f", distKm)}km 운행이 ${String.format("%,d", actualFare)}원으로 기록됐어요. 호출료만 잡혔을 수 있으니 기록에서 요금을 확인해 주세요."
                        val n = android.app.Notification.Builder(this, "callradar_farecheck")
                            .setSmallIcon(android.R.drawable.ic_dialog_alert)
                            .setContentTitle("⚠️ 요금 확인 필요")
                            .setContentText(txt).setStyle(android.app.Notification.BigTextStyle().bigText(txt))
                            .setAutoCancel(true).build()
                        nm.notify(7900, n)
                    }
                    sendDebugLog("FARE_SUSPECT", "#$tripId | ${actualFare}원 | ${String.format("%.1f", distKm)}km")
                }
            }
        } catch (e: Exception) {}
        val destScr = screenAddrDest   // [v50] 운행 중 마지막으로 긁은 목적지 주소
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
                    // [유저592] 우버 결제수단 — 완료 화면에서 읽은 값(자동결제/직접결제)을 함께 저장.
                    //  예전엔 우버가 결제수단을 안 보내서 전부 기본값(auto)이 됐고, 현금 운행도 자동결제로 찍혔다.
                    uberPayType?.let { pt -> put("payment_type", pt) }
                    // [v57] 자정 날짜귀속: day_start_hour를 설정한 유저면 완료시각 기준 영업일을 business_date로 보냄(서버가 출근일 대신 이 값 사용).
                    //  미설정 유저는 안 보냄 → 기존 '출근일 귀속' 유지(야간기사 회귀 방지).
                    if (prefs.getBoolean("day_start_set", false)) {
                        // ★ [2026-09-10] 영업일 경계는 BusinessDay 가 정한다(앱에 같은 계산이 5벌 있었다).
                        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"))
                        // ★ apply{} 안이라 this 는 JSONObject 다. 서비스 컨텍스트를 명시한다.
                        cal.timeInMillis = com.callradar.app.BusinessDay.startOf(this@NaviIntentReceiver)
                        put("business_date", String.format("%04d-%02d-%02d", cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH)))
                    }
                    // [v53] 화면주소 파싱 제거 — 완료 순간 GPS(하차점)만. 이동>300m일 때 지오코딩.
                    val hasGps = lat != 0.0 || lng != 0.0
                    val dist = if (hasGps) distanceMeters(originLat, originLng, lat, lng) else 0.0
                    // [v92] 좌표와 이름을 분리해 저장한다.
                    //  예전엔 '이름을 못 얻으면 좌표도 안 보내는' 구조였다:
                    //   ① reverseGeocode는 Geocoder(네트워크 의존)라 지하주차장·터널·음영에서 흔히 null
                    //   ② 이동 300m 이하면 아예 호출도 안 함
                    //  둘 중 하나만 걸려도 서버의 도착지가 출발지 그대로 남아
                    //  '출발=도착' 이상치가 됐다(120일 549건, 그중 513건이 "이름은 있는데 좌표만 같음").
                    //  하차 GPS는 이름과 무관하게 확실한 사실이므로 항상 남긴다.
                    if (hasGps) { put("dest_lat", lat); put("dest_lng", lng) }
                    val destName = if (hasGps && dist > 300) reverseGeocode(lat, lng) else null
                    if (!destName.isNullOrEmpty()) put("destination", destName)
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
                clearAutoBadgePrefs(); clearTripState()
                synchronized(this) {
                    lastTripId = -1; activeTripId = -1; lastLocalTripId = -1
                    lastSentDest = ""; lastSentTime = 0L; tripStartedAt = 0L
                    originLat = 0.0; originLng = 0.0; tripPlatform = ""
                    screenAddrPickup = ""; screenAddrDest = ""; lastLoggedScreenAddr = ""  // [v50] 다음 트립 위해 화면주소 초기화
                }
            }
        }.start()
    }

    // [v50 화면주소] 우버/카카오 네비 화면에서 실제 목적지 주소(POI/도로명)를 추출.
    //   우버 헤더: "크리스찬 디올 성수" + "서울특별시 성동구 연무장5길 7" 형태로 뜬다.
    //   도로명 라인 = (시도) (시군구) (…로/…길) (번지숫자). 번지 숫자가 있어야 확정된 목적지(예상/방향 문구 배제).
    //   반환: POI가 있으면 "POI (구)" 형태, 없으면 "구 도로명 번지". 못 찾으면 null.
    private val reRoadAddr = Regex("([가-힣]+(?:특별시|광역시|특별자치시|특별자치도|도))?\\s*([가-힣]{2,}(?:시|군|구))\\s+([가-힣A-Za-z0-9]+(?:로|길))\\s*(\\d+(?:-\\d+)?)")
    private fun extractScreenAddress(lines: List<String>): String? {
        try {
            for (i in lines.indices) {
                val ln = lines[i]
                if (ln.contains("방향") || ln.contains("남음") || ln.contains("서쪽") || ln.contains("동쪽") || ln.contains("남쪽") || ln.contains("북쪽")) continue // 네비 안내/예상문구 제외
                val m = reRoadAddr.find(ln) ?: continue
                val gu = m.groupValues[2]         // 성동구
                val road = m.groupValues[3]       // 연무장5길
                val no = m.groupValues[4]         // 7
                val roadShort = "$gu $road $no"
                // POI: 바로 앞 줄이 짧은 이름(주소/숫자/UI키워드 아님)이면 사용
                var poi = ""
                if (i > 0) {
                    val prev = lines[i - 1].trim()
                    val isName = prev.length in 2..24 && prev.any { it in '가'..'힣' } &&
                        !prev.contains("특별시") && !prev.contains("광역시") && !reRoadAddr.containsMatchIn(prev) &&
                        !prev.contains("길안내") && !prev.contains("방향") && !prev.contains("요금") && !prev.contains("결제") &&
                        !prev.contains("분") && !prev.contains("km") && !prev.contains("m ") && !prev.any { it.isDigit() && prev.length < 5 }
                    if (isName) poi = prev
                }
                return if (poi.isNotBlank()) "$poi ($gu)" else roadShort
            }
        } catch (e: Exception) {}
        return null
    }

    // [v3.1 수정] Nominatim 직접 호출 (서버 경유 제거)
    // [주소통일] 카카오 좌표→행정동+지번(서버가 KAKAO_REST_KEY로 처리). 구단위 폴백 제거 → '역삼동 823-24' 형태로 통일.
    private fun reverseGeocode(lat: Double, lng: Double): String? {
        try {
            // [고시 제6조③ 2026-08-27] user_id 를 함께 보낸다.
            //  취급대장은 "누구 위치를 언제 무슨 목적으로 취급했는가"를 확인할 수 있어야 하는데,
            //  토큰이 없는 구버전 계정에서는 주체가 null 로 남아 확인이 안 됐다.
            //  (토큰이 있으면 서버가 그쪽을 우선 쓴다. 이건 없을 때의 보조수단이다.)
            val uidQ = try {
                val u = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getString("user_id", "") ?: ""
                if (u.isNotBlank()) "&user_id=$u" else ""
            } catch (e: Exception) { "" }
            val conn = (URL("$SERVER_URL/api/geocode/reverse?x=$lng&y=$lat$uidQ").openConnection().apply {
                com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") }
            } as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("User-Agent", "CallRadar/1.0")
            }
            val raw = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val j = JSONObject(raw)
            if (j.optBoolean("ok", false)) {
                val short = j.optString("short", "")
                if (short.isNotBlank()) return short
                val dong = j.optString("dong", "")
                if (dong.isNotBlank()) return dong
                val region = j.optString("region", "")
                if (region.isNotBlank()) return region.substringAfterLast(" ") // 마지막 토큰 = 동
            }
        } catch (e: Exception) {
            Log.e(TAG, "카카오 역지오코딩 실패(→OSM 폴백): ${e.message}")
        }
        return reverseGeocodeOsm(lat, lng)
    }

    // 폴백: 카카오 키/서버 실패 시 OSM Nominatim (동 우선, 없으면 구).
    private fun reverseGeocodeOsm(lat: Double, lng: Double): String? {
        return try {
            val conn = (URL("https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lng&zoom=16&addressdetails=1&accept-language=ko").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 10000
                setRequestProperty("User-Agent", "CallRadar/1.0")
            }
            val raw = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val json = JSONObject(raw)
            val addr = json.optJSONObject("address")
            val neighbourhood = addr?.optString("neighbourhood", addr.optString("residential", addr.optString("quarter", ""))) ?: ""
            val district = addr?.optString("borough", addr.optString("suburb", "")) ?: ""
            val city = addr?.optString("city", addr.optString("county", "")) ?: ""
            // 동 우선 반환(구는 마지막 수단)
            neighbourhood.ifEmpty { district }.ifEmpty { city }.ifEmpty { null }
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

    /**
     * [v99 #12559] 우버 요금 하한.
     *  우버는 기사가 **타이핑하는 중인 입력칸**을 읽는다. 21,500 을 치는 동안 2,150 같은 중간값이
     *  그대로 잡혀 서버에 굳는다(실측: 47분 서울 운행이 2,150원으로 기록됨).
     *  미터요금은 전국 어느 지역 기본요금(3,800~4,800)보다 낮을 수 없으므로 3,000 미만은 중간값으로 본다.
     *  ★ 이 하한은 **우버의 요금 입력·완료 경로에서만** 쓴다. extractFare 전체에 걸면
     *    카카오·티머니의 확정 요금 화면까지 영향을 받아 회귀가 난다(그쪽은 타이핑을 읽지 않는다).
     */
    private fun uberFloor(v: Int): Int = if (v in 1..2999) 0 else v

    private fun extractFare(lines: List<String>, pkg: String?): Int {
        val allTextRaw = lines.joinToString(" ")

        // [v3.1x] 우버는 지정된 요금 화면에서만 금액을 읽는다 (허용 목록)
        // 대기화면 지도 상단의 "오늘 수입 / 마지막 운행 / 오늘의 합산" 등은 특정 시점에만 떠서
        // 차단 목록으로는 놓치기 쉽다 → 아예 요금 화면이 아니면 0.
        if (pkg == UBER && !isUberFareScreen(allTextRaw)) return 0

        // 평가화면/홈화면 누적수입 방어 (우버 외 플랫폼 및 pkg 미지정 호출 대비)
        // [v55 #468] 홈 대시보드의 '오늘 누적 수입(예 ₩267,519)'을 요금으로 오긁는 것 차단.
        //  '홈+오늘' 조합만으론 "홈 ₩251,293 안전 도구 키트…"(오늘 글자 없는 변형)를 놓침 → 대시보드 고유 마커도 추가.
        val isRatingOrHome = (allTextRaw.contains("라이더") && allTextRaw.contains("평가")) ||
            (allTextRaw.contains("별점") && allTextRaw.contains("탭하세요")) ||
            (allTextRaw.contains("홈") && allTextRaw.contains("오늘")) ||
            allTextRaw.contains("안전 도구 키트") || allTextRaw.contains("운행 리스트") ||
            allTextRaw.contains("운행 명세서") || allTextRaw.contains("운행 플래너") ||
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

    /* ═══ 접근성이 왜 풀리는지 재는 장치 (2026-09-03 유저 103 제보) ══════════════════
     *
     *  제보: "사용 중에 접근성이 풀린다."
     *  서버 실측(30일): 103 기사 **재연결 100회**(하루 3.3회). 그런데 20명이 같은 상태고
     *  1번(156회)·592번(105회)이 더 잦다. 103의 자동기록 누락률은 **1%**(자동 280/수동 4)라
     *  기록 손실로는 거의 안 이어지고 있다 — 체감은 사실이지만 원인이 뭔지 몰랐다.
     *
     *  ★ 못 재고 있던 이유: `onServiceConnected`(붙을 때)만 서버로 보냈다.
     *    그래서 '몇 번 되살아났나'는 알아도 **'얼마나 죽어 있었나'**, 그리고 결정적으로
     *    **왜 죽었나**를 몰랐다. 재연결 사이의 긴 공백은 오히려 '계속 정상'이라는 뜻이라
     *    그걸로는 판별이 안 된다.
     *
     *  ★ 판별 원리 — **끊김 콜백이 오는지 여부**가 두 원인을 갈라낸다.
     *      · 기사/OS가 접근성을 **끔** → onUnbind·onDestroy 가 호출된다 (정상 종료 경로)
     *      · 안드로이드가 **프로세스를 죽임** → 콜백이 아예 안 온다 (SIGKILL)
     *    그래서 끊길 때 `acc_off_at` 을 남긴다. 다음 연결에서
     *      그 값이 있으면 → ACC_OFF  (설정이 꺼졌던 것)
     *      없으면        → ACC_KILL  (프로세스가 죽은 것)
     *
     *  ★ 끊기는 순간엔 네트워크가 안 될 수 있다. 그래서 **그때 보내려 하지 않고**
     *    prefs 에 `commit()`(즉시 디스크)로 남겨 **다음 연결 때** 보고한다.
     *    죽은 시점을 알 수 없는 SIGKILL 은 5분 주기 생존 흔적(`acc_seen_at`)으로 하한을 잡는다. */

    /** 서비스가 살아 있다는 흔적. 이벤트마다 쓰면 I/O 폭주라 5분에 한 번만 쓴다. */
    private fun touchAlive() {
        val now = System.currentTimeMillis()
        if (now - lastAliveWrite < ALIVE_WRITE_INTERVAL) return
        lastAliveWrite = now
        try { getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).edit().putLong("acc_seen_at", now).apply() } catch (e: Exception) {}
    }

    /** 끊김을 디스크에 박아둔다. 이 흔적이 있으면 '설정이 꺼진 것', 없으면 '프로세스가 죽은 것'이다. */
    private fun markAccOff(from: String) {
        try {
            val p = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            val working = p.getLong("work_start", 0L) > 0L
            p.edit().putLong("acc_off_at", System.currentTimeMillis()).commit()   // apply 가 아니라 commit — 곧 죽을 수 있다
            sendDebugLog("ACC_DISCONNECT", "$from | 근무중=$working | 트립=#$lastTripId")
        } catch (e: Exception) {}
    }

    /** 직전 세션이 어떻게 끝났는지 다음 연결에서 보고한다. */
    private fun reportPreviousOutage() {
        try {
            val p = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            val offAt = p.getLong("acc_off_at", 0L)
            val seenAt = p.getLong("acc_seen_at", 0L)
            val working = p.getLong("work_start", 0L) > 0L
            val now = System.currentTimeMillis()
            when {
                offAt > 0L ->
                    sendDebugLog("ACC_OFF", "설정 꺼졌었음 | ${(now - offAt) / 60000L}분 | 근무중=$working")
                // 흔적은 있는데 끊김 콜백이 없었다 = 프로세스 사망. 5분 주기 흔적이라 실제 공백은 이보다 짧을 수 있다.
                seenAt > 0L && now - seenAt > 60_000L ->
                    sendDebugLog("ACC_KILL", "프로세스 사망 | 마지막 흔적 ${(now - seenAt) / 60000L}분 전 | 근무중=$working")
            }
            p.edit().remove("acc_off_at").putLong("acc_seen_at", now).apply()
        } catch (e: Exception) {}
    }

    override fun onUnbind(intent: Intent?): Boolean { markAccOff("onUnbind"); return super.onUnbind(intent) }
    override fun onInterrupt() {
        Log.d(TAG, "NaviIntentReceiver 중단")
        // onInterrupt 는 '해석을 멈춰라'는 신호로, 끊김과 다르다. 상관관계를 보려고 기록만 남긴다.
        try { sendDebugLog("ACC_INTERRUPT", "트립=#$lastTripId") } catch (e: Exception) {}
    }
    override fun onDestroy() { markAccOff("onDestroy"); stopDestUpdateTimer(); super.onDestroy() }
}
