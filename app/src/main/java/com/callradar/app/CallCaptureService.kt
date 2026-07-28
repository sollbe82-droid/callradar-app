package com.callradar.app

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.callradar.app.screen.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * [v23] 알림 자동캡처 — "손 안 가는 앱"의 핵심.
 * 택시 기사앱(카카오T·우버·티머니GO)의 완료 알림을 읽어 금액을 뽑아, 서버가 '금액 없는' 최근 운행에 자동 반영.
 * 유저는 아무것도 안 해도 매출이 쌓임. 실물 알림 문구는 서버에 샘플로 모아 파싱을 점점 정밀화(학습).
 * 옵트인(notif_capture_on)일 때만 동작. 알림 내용은 금액 반영·학습 외 용도로 쓰지 않음.
 */
class CallCaptureService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        val TARGET_PACKAGES = setOf(
            "com.kakao.taxi.driver",        // 카카오T 기사용
            "com.ubercab.driver",           // 우버 기사
            "com.thinkware.inaviair.tmoney" // 티머니GO 기사(추정)
        )
        // "12,000원" / "12000 원" 등에서 금액 추출
        private val AMOUNT = Regex("([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{4,6})\\s*원")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg !in TARGET_PACKAGES) return
        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notif_capture_on", false)) return
        val userId = prefs.getString("user_id", "") ?: ""
        if (userId.isEmpty()) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val full = "$title\n$text\n$big"
        val amount = AMOUNT.find(full)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()

        scope.launch {
            try {
                val body = JSONObject().apply {
                    put("user_id", userId.toIntOrNull() ?: userId)
                    put("package", pkg)
                    put("title", title.take(200))
                    put("body", (text + " " + big).trim().take(500))
                    if (amount != null) put("amount", amount)
                }
                val conn = (URL("${Config.SERVER_URL}/api/notif-capture").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 6000; readTimeout = 6000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode; conn.disconnect()
            } catch (e: Exception) {}
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
