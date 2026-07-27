package selfevolve

/**
 * Self-Evolve Engine — 앱용 클라이언트 (Android / Kotlin), 도메인 독립.
 *
 * 익명 사용/행동/결과 이벤트를 큐에 모아 배치로 서버에 전송한다.
 * 개인정보 미수집: 무작위 anonId + event/context/성공여부/수치 meta만.
 * 옵트아웃 지원(setEnabled(false)면 전송 안 함).
 *
 * 사용법:
 *   val se = SelfEvolveClient(context, baseUrl = "https://myserver/se", domain = "stock")
 *   se.log("signal_view", context = "youtuber_A")                 // 성공 이벤트
 *   se.log("order_try", context = "buy", ok = false)              // 실패 = 숨은 니즈 신호
 *   se.setEnabled(false)                                          // 옵트아웃
 *
 * 서버: selfevolve.js 의 POST /events 와 짝. (배치 {domain, anon_id, events:[...]} 전송)
 */
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class SelfEvolveClient(
    context: Context,
    private val baseUrl: String,          // 예: "https://server/se"
    private val domain: String,           // 예: "stock", "silver", "callradar"
    private val flushEvery: Int = 10      // 이 개수 쌓이면 자동 전송
) {
    private val prefs = context.getSharedPreferences("selfevolve", Context.MODE_PRIVATE)
    private val anonId: String = prefs.getString("anon_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("anon_id", it).apply()
    }
    private val queue = ArrayList<JSONObject>()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun isEnabled(): Boolean = prefs.getBoolean("se_enabled", true)
    fun setEnabled(on: Boolean) { prefs.edit().putBoolean("se_enabled", on).apply(); if (!on) synchronized(queue) { queue.clear() } }

    /** 이벤트 기록. ok=false 는 "시도했으나 실패/이탈" = 숨은 니즈 신호. */
    fun log(event: String, context: String = "", ok: Boolean = true, meta: String = "") {
        if (!isEnabled()) return
        val o = JSONObject().apply {
            put("event", event.take(64)); put("context", context.take(96)); put("ok", ok); put("meta", meta.take(240))
        }
        val ready: List<JSONObject>?
        synchronized(queue) { queue.add(o); ready = if (queue.size >= flushEvery) ArrayList(queue).also { queue.clear() } else null }
        if (ready != null) send(ready)
    }

    /** 앱 종료/백그라운드 시 남은 것 전송. */
    fun flush() {
        val ready: List<JSONObject>
        synchronized(queue) { if (queue.isEmpty()) return; ready = ArrayList(queue); queue.clear() }
        send(ready)
    }

    private fun send(events: List<JSONObject>) {
        if (!isEnabled() || events.isEmpty()) return
        scope.launch {
            try {
                val body = JSONObject().apply {
                    put("domain", domain); put("anon_id", anonId)
                    put("events", JSONArray().apply { events.forEach { put(it) } })
                }
                withContext(Dispatchers.IO) {
                    val conn = (URL("$baseUrl/events").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 8000
                        setRequestProperty("Content-Type", "application/json")
                    }
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)); it.flush() }
                    conn.responseCode; conn.disconnect()
                }
            } catch (_: Exception) { /* 텔레메트리는 실패해도 앱에 영향 없음 */ }
        }
    }
}
