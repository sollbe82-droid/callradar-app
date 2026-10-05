package com.callradar.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * [v44] 궤적(GPS 트레일) 서버 백업/복원.
 *  - 궤적은 원래 폰 로컬(LocalTrackDatabase) 전용이라 기기·스토어를 바꾸면 사라졌음.
 *  - 이제 최근 궤적을 서버에 미러(다운샘플 8초 간격)해 두고, 로컬에 없을 때 서버에서 불러와 그려줌.
 *  - 보관 31일(서버가 자동 정리). 업로드는 마지막 업로드 이후분만(track_upload_ts).
 */
object TrackSync {
    private val SERVER = com.callradar.app.Endpoint.base
    private const val MIN_INTERVAL_MS = 8000L   // 다운샘플: 8초 간격만 업로드(용량 절감)
    /* 서버가 한 요청에서 받는 상한은 3000점(index.js:5818 `pts.slice(0,3000)`)이고 **초과분을
     *  조용히 버린다.** 그보다 작게 끊어 보내 '보낸 만큼만 ACK' 가 성립하게 한다.
     *  1000점 ≈ 8초 간격으로 2시간 13분 분량. 본문 약 45KB — 0.1 CPU DB 에도 부담이 적다. */
    private const val CHUNK = 1000

    /** 최근(마지막 업로드 이후, 최대 3일) 궤적을 서버에 업로드. 네트워크는 내부 스레드. */
    fun uploadRecent(ctx: Context) { Thread { try { uploadRecentSync(ctx) } catch (e: Exception) {} }.start() }

    private fun uploadRecentSync(ctx: Context) {
        val prefs = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val uid = prefs.getString("user_id", "") ?: ""
        if (uid.isEmpty()) return
        val last = prefs.getLong("track_upload_ts", 0L)
        val windowStart = System.currentTimeMillis() - 3L * 86_400_000L
        /* [창 밖 누락 가시화] since 를 max(last, now-3일) 로 자르기 때문에, 3일 넘게 업로드가
         *  안 된 구간(기기 꺼짐·장기 오프라인)은 서버 미러에 영구 공백으로 남는다. 로컬 원본은
         *  남아 있으므로 자료가 사라지는 건 아니다. 조용히 넘기지 않고 흔적을 남긴다. */
        if (last in 1 until windowStart) {
            prefs.edit()
                .putLong("track_gap_from", last)
                .putLong("track_gap_to", windowStart)
                .putInt("track_gap_count", prefs.getInt("track_gap_count", 0) + 1)
                .apply()
        }
        val since = maxOf(last, windowStart)
        val pts = LocalTrackDatabase.getInstance(ctx).pointsSince(since)
        if (pts.isEmpty()) return

        // 다운샘플 먼저. 그 다음 '보낸 덩어리 단위로만' 워터마크를 올린다.
        val kept = ArrayList<LocalTrackDatabase.Pt>(pts.size)
        var lastTs = 0L
        for (p in pts) {
            if (p.ts - lastTs < MIN_INTERVAL_MS) continue
            lastTs = p.ts
            kept.add(p)
        }
        if (kept.isEmpty()) return

        /* ★ [배치 상한 초과 방지 + 확인된 범위까지만 워터마크] 2026-10-05
         *
         *  고치기 전 동작(검증 환경에서 실측 재현):
         *   · 서버 index.js:5818 이 `pts.slice(0, 3000)` 으로 초과분을 조용히 버리고 200 을 준다.
         *   · 앱은 2xx 만 보고 track_upload_ts 를 배치 전체의 마지막 ts 로 올렸다.
         *   → 4000점 전송 → status 200 / inserted 3000 / 실제 저장 3000. 1000점 영구 소실.
         *     3일 창 × 8초 다운샘플이면 최대 약 32,400점이라 약 29,400점이 사라질 수 있었다.
         *
         *  ★ 이 수정은 '배치 상한 초과 방지' 다. **진짜 ACK 가 아니다.**
         *    서버는 어느 점이 저장됐는지 돌려주지 않는다. inserted 는 '유효해서 INSERT 를 시도한
         *    행 수' 이고 ON CONFLICT DO NOTHING 때문에 실제 신규 저장 수와도 다르다.
         *    점별 ACK 는 서버 변경이 필요하고 그건 운영배포 승인 대기 항목이다.
         *
         *  그래서 앱이 할 수 있는 선까지만 한다 — **확인된 범위까지만 워터마크를 올린다**:
         *   ① 상한보다 작은 덩어리로 나눈다(CHUNK=1000 < 서버 3000).
         *   ② 응답의 inserted 를 읽는다.
         *      · inserted >= 보낸 수      → 이 덩어리는 서버가 끝까지 처리했다. 마지막 ts 까지 올린다.
         *      · 0 < inserted < 보낸 수   → 서버가 앞에서부터 자른 것이다(slice 는 앞부터).
         *                                   **inserted 번째 점의 ts 까지만** 올리고 거기서 멈춘다.
         *      · inserted == 0            → 아무것도 안 들어갔다. 올리지 않고 멈춘다.
         *      · 본문을 못 읽었다          → 저장됐는지 알 수 없다. **올리지 않고 멈춘다.**
         *        (응답 유실 시 다음 실행이 같은 점을 다시 보낸다. 서버가 (user_id, ts) 로 멱등이라
         *         중복 재전송은 안전하다 — 저장 후 응답만 유실된 경우도 손실이 없다.)
         *   ③ 워터마크는 **덩어리마다 즉시 커밋**한다. 앱이 중간에 죽어도 다음 실행이 그 자리에서 잇는다.
         *   ④ 다 끝나면 읽기로 되짚어 확인한다(verifyWatermark). 서버가 실제로 가진 마지막 점이
         *      워터마크보다 앞이면 **워터마크를 되돌린다.** 이것이 지금 가능한 유일한 진짜 확인이다.
         */
        var i = 0
        var advancedTo = last
        var stopReason: String? = null
        while (i < kept.size) {
            val end = minOf(i + CHUNK, kept.size)
            val sentCount = end - i
            val arr = JSONArray()
            for (j in i until end) {
                val p = kept[j]
                arr.put(JSONArray().apply {
                    put((p.lat * 100000).toLong() / 100000.0)   // 소수 5자리로 반올림(용량↓)
                    put((p.lng * 100000).toLong() / 100000.0)
                    put(p.ts); put(if (p.loaded) 1 else 0)
                })
            }
            val body = JSONObject().apply { put("user_id", uid); put("points", arr) }

            var code = -1
            var inserted = -1          // -1 = 모름(본문 못 읽음)
            try {
                val conn = (URL("$SERVER/api/track/points").openConnection().apply {
                    com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") }
                } as HttpURLConnection).apply {
                    requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true; connectTimeout = 15000; readTimeout = 30000
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                code = conn.responseCode
                if (code in 200..299) {
                    try {
                        val txt = conn.inputStream.bufferedReader().readText()
                        inserted = JSONObject(txt).optInt("inserted", -1)
                    } catch (e: Exception) { inserted = -1 }
                }
                conn.disconnect()
            } catch (e: Exception) { code = -1 }

            if (code !in 200..299) { stopReason = "http_$code"; break }
            if (inserted < 0) { stopReason = "ack_unreadable"; break }
            if (inserted == 0) { stopReason = "inserted_zero"; break }

            if (inserted >= sentCount) {
                advancedTo = kept[end - 1].ts
                prefs.edit().putLong("track_upload_ts", advancedTo).apply()
                i = end
            } else {
                // 서버가 앞에서부터 자른 범위까지만 인정한다.
                advancedTo = kept[i + inserted - 1].ts
                prefs.edit().putLong("track_upload_ts", advancedTo).apply()
                stopReason = "server_truncated_${inserted}_of_$sentCount"
                break
            }
        }

        if (stopReason != null) {
            prefs.edit()
                .putLong("track_upload_fail_at", System.currentTimeMillis())
                .putString("track_upload_stop_reason", stopReason)
                .apply()
        } else {
            prefs.edit().remove("track_upload_stop_reason").apply()
        }

        // ④ 읽기로 되짚어 확인. 올린 워터마크가 서버 실제 보유분보다 앞서 있으면 되돌린다.
        if (advancedTo > last) verifyWatermark(ctx, uid, last, advancedTo)
    }

    /**
     * 올린 워터마크가 서버에 실제로 반영됐는지 **읽어서** 확인한다.
     * 서버가 점별 ACK 를 주지 않으므로, 지금 가능한 진짜 확인은 이것뿐이다.
     *
     * 서버가 가진 그 구간의 마지막 ts 가 워터마크보다 앞이면 그 값으로 **되돌린다**.
     * 되돌리면 다음 실행이 그 뒤를 다시 보낸다 — (user_id, ts) 멱등이라 중복은 안전하다.
     */
    private fun verifyWatermark(ctx: Context, uid: String, from: Long, claimed: Long) {
        try {
            val prefs = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            val pts = fetchRange(ctx, from, claimed + 1)
            if (pts.isEmpty()) {
                // 읽기 자체가 실패했는지, 정말 없는지 구분할 수 없다 → 워터마크를 되돌리고 기록만 한다.
                prefs.edit()
                    .putLong("track_upload_ts", from)
                    .putString("track_verify", "readback_empty_rolled_back")
                    .apply()
                return
            }
            val serverMax = pts.maxOf { it.ts }
            if (serverMax < claimed) {
                prefs.edit()
                    .putLong("track_upload_ts", serverMax)
                    .putString("track_verify", "rolled_back_to_$serverMax")
                    .putInt("track_verify_rollbacks", prefs.getInt("track_verify_rollbacks", 0) + 1)
                    .apply()
            } else {
                prefs.edit()
                    .putString("track_verify", "ok_$serverMax")
                    .putLong("track_verified_ts", serverMax)
                    .apply()
            }
        } catch (e: Exception) { /* 확인 실패는 업로드를 되돌리지 않는다 — 다음 실행이 다시 확인한다 */ }
    }

    /** 로컬에 궤적이 없을 때 서버에서 그 기간 궤적을 불러옴(동기 — 호출부 스레드에서). */
    fun fetchRange(ctx: Context, since: Long, until: Long): List<LocalTrackDatabase.Pt> {
        val out = ArrayList<LocalTrackDatabase.Pt>()
        try {
            val prefs = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            val uid = prefs.getString("user_id", "") ?: ""
            if (uid.isEmpty()) return out
            val conn = (URL("$SERVER/api/track/points/$uid?since=$since&until=$until").openConnection().apply {
                com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") }
            } as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 30000 }
            val txt = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            val a = JSONArray(txt)
            for (i in 0 until a.length()) {
                val p = a.getJSONArray(i)
                out.add(LocalTrackDatabase.Pt(p.getDouble(0), p.getDouble(1), p.getLong(2), p.getInt(3) == 1))
            }
        } catch (e: Exception) {}
        return out
    }
}
