package com.callradar.app

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

        Thread {
            try {
                // 최근 5건 중 fare가 null/0인 가장 최근 트립 찾기
                val conn = (URL("$SERVER_URL/api/trips/$userId?limit=5").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 10000
                }
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val trips = JSONArray(response)

                var targetTripId = -1
                for (i in 0 until trips.length()) {
                    val trip = trips.getJSONObject(i)
                    val tripFare = trip.optInt("fare", 0)
                    if (tripFare == 0 || trip.isNull("fare")) {
                        targetTripId = trip.getInt("id")
                        break // 가장 최근 fare=null 트립
                    }
                }

                if (targetTripId > 0) {
                    // 요금 업데이트
                    val updateJson = JSONObject().apply {
                        put("user_id", userId)
                        put("fare", fare)
                    }
                    val updateConn = (URL("$SERVER_URL/api/trips/$targetTripId").openConnection() as HttpURLConnection).apply {
                        requestMethod = "PUT"
                        setRequestProperty("Content-Type", "application/json")
                        doOutput = true; connectTimeout = 10000; readTimeout = 10000
                    }
                    updateConn.outputStream.write(updateJson.toString().toByteArray())
                    updateConn.responseCode
                    updateConn.disconnect()

                    lastMatchedFare = fare
                    lastMatchedTime = now
                    Log.d(TAG, "✅ 택시투데이 금액 매칭: 트립 #$targetTripId → ${fare}원")
                } else {
                    Log.d(TAG, "⚠️ 금액 매칭 실패: fare=null인 트립 없음 (${fare}원)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "금액 매칭 오류: ${e.message}")
            }
        }.start()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
