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

    /** 최근(마지막 업로드 이후, 최대 3일) 궤적을 서버에 업로드. 네트워크는 내부 스레드. */
    fun uploadRecent(ctx: Context) { Thread { try { uploadRecentSync(ctx) } catch (e: Exception) {} }.start() }

    private fun uploadRecentSync(ctx: Context) {
        val prefs = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        val uid = prefs.getString("user_id", "") ?: ""
        if (uid.isEmpty()) return
        val last = prefs.getLong("track_upload_ts", 0L)
        val since = maxOf(last, System.currentTimeMillis() - 3L * 86_400_000L)
        val pts = LocalTrackDatabase.getInstance(ctx).pointsSince(since)
        if (pts.isEmpty()) return
        val arr = JSONArray(); var lastTs = 0L; var maxTs = last
        for (p in pts) {
            if (p.ts - lastTs < MIN_INTERVAL_MS) continue
            lastTs = p.ts; if (p.ts > maxTs) maxTs = p.ts
            arr.put(JSONArray().apply {
                put((p.lat * 100000).toLong() / 100000.0)   // 소수 5자리로 반올림(용량↓)
                put((p.lng * 100000).toLong() / 100000.0)
                put(p.ts); put(if (p.loaded) 1 else 0)
            })
        }
        if (arr.length() == 0) return
        val body = JSONObject().apply { put("user_id", uid); put("points", arr) }
        val conn = (URL("$SERVER/api/track/points").openConnection().apply {
            com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") }
        } as HttpURLConnection).apply {
            requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doOutput = true; connectTimeout = 15000; readTimeout = 30000
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode in 200..299) prefs.edit().putLong("track_upload_ts", maxTs).apply()
        conn.disconnect()
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
