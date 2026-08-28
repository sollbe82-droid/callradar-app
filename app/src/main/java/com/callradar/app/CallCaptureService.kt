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
        // [v99] 이즐(eB카드) 택시운전자. 제주 기사들이 쓰는 카드결제 단말의 짝 앱이다.
        //  콜 앱이 아니라 결제 단말이라 접근성 대상이 아니다(화면에 콜·탑승·완료가 없다).
        //  주는 건 알림 하나뿐인데 그 안에 승인시간·승인금액이 다 있다:
        //    "신규 승인 내역 알림 / 승인시간 : 2026-08-24 23:21:50 / 승인금액 :    7,920 원"
        const val EZL = "com.ebcard.taxi.driver"
        // 택시앱(자동결제·요금 알림용)
        val TARGET_PACKAGES = setOf(
            "com.kakao.taxi.driver",        // 카카오T 기사용
            "com.ubercab.driver",           // 우버 기사
            "com.thinkware.inaviair.tmoney",// 티머니GO 기사(추정)
            EZL                             // 이즐 택시운전자(제주 등)
        )
        // [검증됨/옛 TmoneyNotificationService] 금액은 카드결제 알림 "[택시승인] 국민카드 13,640원"에서도 옴 → 문구로도 잡음.
        // 금액은 여러 개면 마지막(총액)을 씀.
        private val AMOUNT = Regex("([0-9,]+)\\s*원")
        // [v99] 이즐 승인시간. 이 알림은 **늦게 온다**(기사 제보). 도착시각으로 매칭하면 그 사이에 끝난
        //  다음 운행에 남의 요금이 꽂힌다 → 알림이 스스로 들고 있는 승인시각으로 매칭한다.
        private val APPROVED_AT = Regex("승인\\s*시간\\s*[:：]?\\s*(\\d{4})[-./](\\d{1,2})[-./](\\d{1,2})\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")
        // 승인취소·반품·환불 알림의 금액을 매출로 넣으면 안 된다(지금까지 없던 안전장치).
        //  ★ 그냥 "취소"로 잡으면 "취소 수수료 안내" 같은 꼬리말이 붙은 **정상** 결제 알림까지 버린다 → 좁게 잡는다.
        private val CANCEL = Regex("승인\\s*취소|결제\\s*취소|매입\\s*취소|취소\\s*승인|반품|환불")
        private const val STALE_MS = 12L * 60 * 60 * 1000   // 알림함에 묵은 알림 재게시 방어
        private const val FRESH_MS = 3L * 60 * 1000         // pending_fare(다음 화면 프리필)를 허용할 신선도
        // [v99 수정] 중복 판정을 '마지막 1건'이 아니라 **최근 키 집합**으로 바꿨다.
        //  단일 슬롯(lastFare/lastApproved)은 사이에 다른 금액 알림이 하나만 끼어도 무력화된다:
        //    이즐 A1(7,920) → 카카오(9,600) → 이즐 A1 재게시(7,920)
        //    → lastFare 가 9,600 이라 amount 비교가 어긋나 재게시를 못 막고 서버에 다시 갔다.
        //  승인시각을 아는 알림은 (승인시각+금액)으로 영구 중복 차단, 모르는 알림은 종전대로 같은 금액 60초.
        //  LinkedHashMap(accessOrder) + removeEldestEntry 로 64개만 들고 있는다.
        private val seen = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 64
        }

        /** "승인시간 : 2026-08-24 23:21:50" → epoch ms. 기기 로컬(KST) 기준. 못 읽으면 0. */
        fun parseApprovedAt(s: String): Long {
            val g = (APPROVED_AT.find(s) ?: return 0L).groupValues
            return try {
                val c = java.util.Calendar.getInstance()
                c.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(),
                      g[4].toInt(), g[5].toInt(), g.getOrNull(6)?.toIntOrNull() ?: 0)
                c.set(java.util.Calendar.MILLISECOND, 0)
                c.timeInMillis
            } catch (e: Exception) { 0L }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val prefs = getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notif_capture_on", false)) return
        val userId = prefs.getString("user_id", "") ?: ""
        if (userId.isEmpty()) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val full = "$title\n$text\n$big"

        // 캡처 대상: (1) 카드 택시결제 알림 "[택시승인]/[택시결제]"(패키지 무관) (2) 택시앱 결제·요금 알림
        //  [v99] 이즐 문구엔 결제·요금·완료가 **하나도 없다**("승인시간/승인금액") → 패키지만 추가하면 조용히 아무것도 안 된다.
        //   "승인"을 전체에 풀면 카카오·우버의 '정산 승인' 같은 알림까지 물어버리므로 이즐에만 건다.
        val isTaxiPay = full.contains("택시승인") || full.contains("택시결제")
        val isPlatformPay = pkg in TARGET_PACKAGES && (
            full.contains("결제") || full.contains("요금") || full.contains("완료") ||
            (pkg == EZL && full.contains("승인금액"))
        )
        if (!isTaxiPay && !isPlatformPay) return

        val now = System.currentTimeMillis()
        val approvedAt = parseApprovedAt(full)
        // 알림함에 며칠 묵은 알림이 남아 있다가 재게시되는 일이 실제로 있다(기사 스크린샷: 8/24 승인알림 5개 잔류).
        // 그게 오늘 운행에 꽂히면 매출이 통째로 틀어진다.
        if (approvedAt > 0L && kotlin.math.abs(now - approvedAt) > STALE_MS) return

        // [v99 수정] 승인취소·반품·환불은 **매출이 아니다**. 다만 처음엔 함수 맨 앞에서 return 했는데,
        //  그러면 서버 샘플(notif_samples) 전송까지 막혀 오탐이 나도 확인할 방법이 없어진다.
        //  판정은 여기서(금액 반영만 차단) 하고, 아래 샘플 전송은 그대로 살린다.
        //  또 문구를 '승인취소·결제취소·매입취소'로 좁혔다 — 그냥 "취소"면
        //  "취소 수수료 안내" 같은 꼬리말이 붙은 정상 결제 알림까지 통째로 버린다.
        val isCancel = CANCEL.containsMatchIn(full)

        val amount = if (isCancel) null
            else AMOUNT.findAll(full).lastOrNull()?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
                ?.takeIf { it in 1000..500000 }
        if (amount != null) {
            // 승인시각을 아는 알림은 그 승인 자체를 키로 삼아 영구 중복 차단(재게시·중복 게시 방어).
            //  모르는 알림(카카오·우버·카드사)은 종전대로 같은 금액 60초 쿨다운.
            //  onNotificationPosted 가 단일 스레드 보장이 없으므로 읽기·쓰기를 함께 잠근다.
            val key = if (approvedAt > 0L) "A$approvedAt#$amount" else "N$amount"
            val dup = synchronized(seen) {
                val prev = seen[key]
                val isDup = prev != null && (approvedAt > 0L || now - prev < 60000L)
                if (!isDup) seen[key] = now
                isDup
            }
            if (dup) return
            // [v43] 직접결제 카드승인 금액을 종료 금액창(QuickEntry) 프리필용 pending_fare로 저장.
            //   플로팅 완료(finalizeCurrentTrip)가 5분 내 pending_fare를 읽어 자동 기입 → 손 안 대고 금액 채워짐.
            // [v99] 단, 승인이 '방금'일 때만. 20분 늦게 온 알림으로 프리필하면 **다음 운행**의 금액칸이 남의 요금으로 채워진다.
            //   (서버 귀속은 승인시각으로 정확히 찾아가므로, 프리필을 걸러도 금액은 제 운행에 들어간다)
            val fresh = approvedAt == 0L || (now - approvedAt) <= FRESH_MS
            if (fresh) {
                try { prefs.edit().putInt("pending_fare", amount).putLong("pending_fare_ts", now).putString("pending_fare_raw", full.take(120)).apply() } catch (e: Exception) {}
            }
        }

        scope.launch {
            try {
                val body = JSONObject().apply {
                    put("user_id", userId.toIntOrNull() ?: userId)
                    put("package", pkg)
                    put("title", title.take(200))
                    put("body", (text + " " + big).trim().take(500))
                    if (amount != null) put("amount", amount)
                    // [v99] 승인시각(epoch ms). 서버가 '도착시각'이 아니라 이 시각을 품는 운행에 금액을 붙인다.
                    if (approvedAt > 0L) put("approved_at", approvedAt)
                }
                val conn = (URL("${Config.SERVER_URL}/api/notif-capture").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
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
