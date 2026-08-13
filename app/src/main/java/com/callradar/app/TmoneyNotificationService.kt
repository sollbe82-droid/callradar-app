package com.callradar.app

// ===== TmoneyNotificationService v3 (2026-07-10) =====
// v3: 판단 기준을 NaviIntentReceiver.activeTripId 로 변경 (v2의 ended_at/시간창 폐기)
//     - activeTripId > 0  : 플랫폼 콜 진행 중 → 그 트립에 금액 매칭
//     - activeTripId <= 0 : 플랫폼 콜 없음 → 길빵/예약 → 새 트립 생성
//     대기시간이 아무리 길어도, ended_at이 늦게 찍혀도 정확함
//     (v2 버그: 알림이 TRIP_END보다 먼저 와서 정상 카카오 콜을 "취소 의심"으로 오판 →
//      길빵 트립이 카카오 금액을 가져감)

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TmoneyNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "CallRadar"
        private const val SERVER_URL = "https://callradar-server.onrender.com"
        private const val COOLDOWN_MS = 60 * 1000L // 같은 금액 1분 내 중복 방지
        private val FARE_REGEX = Regex("([0-9,]+)\\s*원")

        @Volatile var lastMatchedFare: Int = 0
        @Volatile var lastMatchedTime: Long = 0L
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        // [v56] '금액 자동 입력' 토글(notif_capture_on) OFF면 티머니/택시승인 금액 캡처도 정지.
        //  기존엔 CallCaptureService만 게이트돼 이 서비스가 토글을 무시하고 계속 캡처하던 반쪽 버그 수정.
        if (!getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getBoolean("notif_capture_on", false)) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        val bigText = extras.getString(Notification.EXTRA_BIG_TEXT) ?: text
        val allText = "$title $text $bigText"

        // 택시투데이 알림: "[택시승인] 국민카드 13,640원"
        if (allText.contains("택시승인") || allText.contains("택시결제")) {
            Log.d(TAG, "💰 택시결제 알림: $title | $text")
            val fare = extractFareFromNotif(allText)
            if (fare > 0) matchFareToRecentTrip(fare)
            return
        }

        // 카카오T 자동결제 알림
        if (sbn.packageName == "com.kakao.taxi.driver" && (allText.contains("결제") || allText.contains("요금"))) {
            Log.d(TAG, "💰 카카오T 결제 알림: $title | $text")
            val fare = extractFareFromNotif(allText)
            if (fare > 0) matchFareToRecentTrip(fare)
            return
        }
    }

    private fun extractFareFromNotif(text: String): Int {
        val match = FARE_REGEX.findAll(text).lastOrNull()
        if (match != null) {
            val amount = match.groupValues[1].replace(",", "").toIntOrNull() ?: 0
            if (amount in 1000..500000) return amount
        }
        return 0
    }

    private fun matchFareToRecentTrip(fare: Int) {
        val now = System.currentTimeMillis()
        // 중복 방지
        if (fare == lastMatchedFare && now - lastMatchedTime < COOLDOWN_MS) {
            Log.d(TAG, "💰 중복 금액 무시: ${fare}원")
            return
        }

        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", null) ?: return

        // [v3] 판단 기준: 지금 플랫폼 콜(카카오/우버/티머니)이 진행 중인가?
        //  - 진행 중  → 이 결제는 그 콜의 것 → 해당 트립에 금액 매칭
        //  - 진행 안함 → 앱이 모르는 운행 → 길빵/예약 → 새 트립 생성
        // 대기시간 길이, ended_at 기록 시점과 무관하게 정확함
        val activeId = NaviIntentReceiver.activeTripId

        Thread {
            try {
                if (activeId > 0) {
                    // 진행 중인 플랫폼 콜에 금액 채우기
                    val updateJson = JSONObject().apply {
                        put("user_id", userId)
                        put("fare", fare)
                    }
                    val updateConn = (URL("$SERVER_URL/api/trips/$activeId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                        requestMethod = "PUT"
                        setRequestProperty("Content-Type", "application/json")
                        doOutput = true; connectTimeout = 10000; readTimeout = 10000
                    }
                    updateConn.outputStream.write(updateJson.toString().toByteArray())
                    updateConn.responseCode
                    updateConn.disconnect()

                    lastMatchedFare = fare
                    lastMatchedTime = now
                    Log.d(TAG, "✅ 택시투데이 금액 매칭: 트립 #$activeId → ${fare}원")
                    sendDebugLog(userId, "FARE_MATCH", "#$activeId | ${fare}원 | 진행중 플랫폼콜")
                } else {
                    // 플랫폼 콜 없음 = 길빵/예약 → 새 트립 생성
                    createStandaloneTrip(userId, fare)
                    lastMatchedFare = fare
                    lastMatchedTime = now
                    Log.d(TAG, "🆕 길빵/예약 → 새 트립 생성 (${fare}원)")
                    sendDebugLog(userId, "FARE_NEW_TRIP", "${fare}원 | 진행중 플랫폼콜 없음")
                }
            } catch (e: Exception) {
                Log.e(TAG, "금액 매칭 오류: ${e.message}")
            }
        }.start()
    }

    /** 스타트 없는 콜(길빵/예약): 출발지 미상 + 도착지=현재 위치 + 금액 */
    private fun createStandaloneTrip(userId: String, fare: Int) {
        try {
            val lat = LocationTrackingService.currentLat
            val lng = LocationTrackingService.currentLng
            // 결제 위치를 도착지로 (역지오코딩). 실패 시 "미상"
            val destName = if (lat != 0.0 || lng != 0.0) (reverseGeocode(lat, lng) ?: "미상") else "미상"
            val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date())

            val json = JSONObject().apply {
                put("user_id", userId)
                put("originName", "미상")
                put("destName", destName)
                put("platform", "길빵/예약")
                put("fare", fare)
                put("payment_type", "card")
                put("started_at", nowIso)
            }
            val conn = (URL("$SERVER_URL/api/trips/manual").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true; connectTimeout = 10000; readTimeout = 10000
            }
            conn.outputStream.write(json.toString().toByteArray())
            val code = conn.responseCode
            conn.disconnect()
            Log.d(TAG, "🆕 스타트없는콜 트립 생성: 미상→$destName ${fare}원 (HTTP $code)")
        } catch (e: Exception) {
            Log.e(TAG, "스타트없는콜 생성 실패: ${e.message}")
        }
    }

    /** 좌표 → 지명 (Nominatim). 실패 시 null */
    private fun reverseGeocode(lat: Double, lng: Double): String? {
        return try {
            val url = URL("https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lng&zoom=16&accept-language=ko")
            // [보안] 제3자(OSM Nominatim)에 우리 세션 토큰을 붙이지 않는다.
            val conn = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "CallRadar/1.0")
                connectTimeout = 8000; readTimeout = 8000
            }
            val res = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val addr = JSONObject(res).optJSONObject("address") ?: return null
            addr.optString("quarter", "").ifEmpty {
                addr.optString("suburb", "").ifEmpty {
                    addr.optString("city_district", "").ifEmpty {
                        addr.optString("town", "").ifEmpty { addr.optString("city", "") }
                    }
                }
            }.ifEmpty { null }
        } catch (e: Exception) { null }
    }

    /** 디버그 로그 서버 전송 (매칭 동작 검증용) */
    private fun sendDebugLog(userId: String, event: String, detail: String) {
        Thread {
            try {
                val json = JSONObject().apply {
                    put("user_id", userId); put("event", event); put("detail", detail)
                }
                val conn = (URL("$SERVER_URL/api/debug/log").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                    requestMethod = "POST"; setRequestProperty("Content-Type", "application/json")
                    doOutput = true; connectTimeout = 8000
                }
                conn.outputStream.write(json.toString().toByteArray())
                conn.responseCode; conn.disconnect()
            } catch (e: Exception) { }
        }.start()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
