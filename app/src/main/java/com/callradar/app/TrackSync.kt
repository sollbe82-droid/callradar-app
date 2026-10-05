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
    private const val SERVER = "https://callradar-server.onrender.com"
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

        /* ★ [부분 처리 후 ACK — 2026-10-05 확정 결함] 고치기 전 동작:
         *   · 서버 index.js:5818 이 `pts.slice(0, 3000)` 으로 **초과분을 조용히 버리고** 200 을 준다.
         *   · 앱은 2xx 만 보고 track_upload_ts 를 배치 전체의 마지막 ts 로 올렸다.
         *   → 3일 창 × 8초 다운샘플 = 최대 약 32,400점. 3000점만 저장되고 워터마크는 32,400점
         *     뒤로 가므로 남은 약 29,400점은 **다시는 올라가지 않는다.** 서버 미러에 영구 공백.
         *   · 서버의 inserted 값도 ON CONFLICT DO NOTHING 때문에 '시도한 행 수'이고 실제 저장 수가
         *     아니다 → ACK 로 신뢰할 수 없다.
         *  고친 방식: 서버 상한(3000)보다 작은 덩어리로 나눠 보내고, **한 덩어리가 2xx 로 끝날 때
         *  그 덩어리의 마지막 ts 까지만** 워터마크를 올린다. 중간에 실패하면 거기서 멈춘다 —
         *  다음 실행이 실패한 덩어리부터 다시 집는다. 서버 변경은 필요 없다(배포 금지 상태에서도 유효). */
        var i = 0
        while (i < kept.size) {
            val end = minOf(i + CHUNK, kept.size)
            val arr = JSONArray()
            for (j in i until end) {
                val p = kept[j]
                arr.put(JSONArray().apply {
                    put((p.lat * 100000).toLong() / 100000.0)   // 소수 5자리로 반올림(용량↓)
                    put((p.lng * 100000).toLong() / 100000.0)
                    put(p.ts); put(if (p.loaded) 1 else 0)
                })
            }
            val chunkMaxTs = kept[end - 1].ts
            val body = JSONObject().apply { put("user_id", uid); put("points", arr) }
            var code = -1
            try {
                val conn = (URL("$SERVER/api/track/points").openConnection().apply {
                    com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") }
                } as HttpURLConnection).apply {
                    requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true; connectTimeout = 15000; readTimeout = 30000
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                code = conn.responseCode
                conn.disconnect()
            } catch (e: Exception) { code = -1 }

            if (code !in 200..299) {
                // 이 덩어리는 못 들어갔다. 워터마크를 올리지 않고 멈춘다 — 다음 실행이 여기서 재개한다.
                prefs.edit()
                    .putLong("track_upload_fail_at", System.currentTimeMillis())
                    .putInt("track_upload_fail_code", code)
                    .apply()
                return
            }
            prefs.edit().putLong("track_upload_ts", chunkMaxTs).apply()
            i = end
        }
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
