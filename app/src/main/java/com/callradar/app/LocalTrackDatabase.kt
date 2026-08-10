package com.callradar.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location

/**
 * [v32] GPS 궤적 저장 — 근무 중 저빈도 브레드크럼(위치 점) 로컬 기록.
 *  각 점에 loaded(실차=1 / 공차=0) 태그 → 실차/공차 거리·시간, 지도 궤적, PNG의 원천.
 *  근무모드(WorkSessionService)에서만 기록. 로컬 전용(서버 업로드 없음, 배터리·저장 절약 위해 저빈도).
 */
class LocalTrackDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "callradar_track.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE track_points (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                ts INTEGER NOT NULL,
                loaded INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX idx_track_ts ON track_points(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {}

    data class Pt(val lat: Double, val lng: Double, val ts: Long, val loaded: Boolean)
    data class DayStats(val loadedM: Double, val emptyM: Double, val loadedMinutes: Long, val emptyMinutes: Long, val points: Int)

    fun addPoint(lat: Double, lng: Double, ts: Long, loaded: Boolean) {
        try {
            writableDatabase.insert("track_points", null, ContentValues().apply {
                put("lat", lat); put("lng", lng); put("ts", ts); put("loaded", if (loaded) 1 else 0)
            })
        } catch (e: Exception) {}
    }

    /** since(ms) 이후 점들(시간순). 기본 오늘 하루. */
    fun pointsSince(since: Long): List<Pt> {
        val out = ArrayList<Pt>()
        try {
            readableDatabase.query("track_points", arrayOf("lat", "lng", "ts", "loaded"), "ts>=?", arrayOf(since.toString()), null, null, "ts ASC").use {
                while (it.moveToNext()) out.add(Pt(it.getDouble(0), it.getDouble(1), it.getLong(2), it.getInt(3) == 1))
            }
        } catch (e: Exception) {}
        return out
    }

    /** [과거날짜] since ≤ ts < until 범위 점들(특정 영업일 조회용). */
    fun pointsBetween(since: Long, until: Long): List<Pt> {
        val out = ArrayList<Pt>()
        try {
            readableDatabase.query("track_points", arrayOf("lat", "lng", "ts", "loaded"), "ts>=? AND ts<?", arrayOf(since.toString(), until.toString()), null, null, "ts ASC").use {
                while (it.moveToNext()) out.add(Pt(it.getDouble(0), it.getDouble(1), it.getLong(2), it.getInt(3) == 1))
            }
        } catch (e: Exception) {}
        return out
    }

    /** 연속 점 사이 거리(m)·시간(분)을 loaded 여부로 나눠 합산.
     *  [거리버그] 예전엔 '거리 400m 초과=GPS점프'로 버려서, 20초 간격 고속주행(20초에 555m+)이 통째로 누락 → 거리가 실제보다 작게.
     *  이제 절대거리 대신 '순간속도'로 판정: 60m/s(216km/h) 이하면 정상 이동으로 합산(고속도로 거리 정상 반영). */
    private fun computeStats(pts: List<Pt>): DayStats {
        var lm = 0.0; var em = 0.0; var lMin = 0L; var eMin = 0L
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val dt = b.ts - a.ts
            if (dt <= 0 || dt > 10 * 60_000L) continue   // 10분 넘게 끊긴 구간은 이동으로 안 봄
            val d = FloatArray(1)
            Location.distanceBetween(a.lat, a.lng, b.lat, b.lng, d)
            val speed = d[0] / (dt / 1000f)              // m/s
            if (speed > 60f) continue                     // GPS 점프만 제외(거리 절대값 아닌 속도로)
            if (d[0] < 5f) continue                        // [지터] 5m 미만 이동 = GPS 흔들림 → 거리 인플레(정차 중 700km 버그) 제외
            // 구간의 성격은 뒤 점(b)의 loaded로 판정
            if (b.loaded) { lm += d[0]; lMin += dt } else { em += d[0]; eMin += dt }
        }
        return DayStats(lm, em, lMin / 60_000L, eMin / 60_000L, pts.size)
    }

    fun statsSince(since: Long): DayStats = computeStats(pointsSince(since))
    fun statsBetween(since: Long, until: Long): DayStats = computeStats(pointsBetween(since, until))
    /** [v44] 서버에서 불러온 점 목록으로 통계 계산(로컬에 없을 때 복원용). */
    fun statsOf(pts: List<Pt>): DayStats = computeStats(pts)

    /** 오래된 궤적 정리(기본 7일 이전 삭제) — 저장 누수 방지. */
    fun purgeBefore(ts: Long) {
        try { writableDatabase.delete("track_points", "ts<?", arrayOf(ts.toString())) } catch (e: Exception) {}
    }

    companion object {
        @Volatile private var inst: LocalTrackDatabase? = null
        fun getInstance(context: Context): LocalTrackDatabase =
            inst ?: synchronized(this) { inst ?: LocalTrackDatabase(context).also { inst = it } }
    }
}
