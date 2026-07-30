package com.callradar.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class LocalTripDatabase(context: Context) : SQLiteOpenHelper(context, "callradar.db", null, 2) {

    companion object {
        private const val TAG = "CallRadar"
        private const val SERVER_URL = "https://callradar-server.onrender.com"

        // 싱글톤
        @Volatile private var instance: LocalTripDatabase? = null
        fun getInstance(context: Context): LocalTripDatabase {
            return instance ?: synchronized(this) {
                instance ?: LocalTripDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS local_trips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                server_id INTEGER DEFAULT -1,
                status TEXT DEFAULT 'pending',
                user_id TEXT,
                platform TEXT,
                origin TEXT,
                destination TEXT,
                origin_lat REAL DEFAULT 0,
                origin_lng REAL DEFAULT 0,
                dest_lat REAL DEFAULT 0,
                dest_lng REAL DEFAULT 0,
                fare INTEGER DEFAULT 0,
                started_at TEXT,
                created_at TEXT DEFAULT (datetime('now'))
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS local_trips")
        onCreate(db)
    }

    // 로컬에 pending으로 저장, 로컬 ID 반환
    fun savePending(
        userId: String?, platform: String, origin: String, destination: String,
        originLat: Double, originLng: Double, destLat: Double, destLng: Double,
        startedAt: String
    ): Long {
        val values = ContentValues().apply {
            put("status", "pending")
            put("user_id", userId ?: "")
            put("platform", platform)
            put("origin", origin)
            put("destination", destination)
            put("origin_lat", originLat)
            put("origin_lng", originLng)
            put("dest_lat", destLat)
            put("dest_lng", destLng)
            put("started_at", startedAt)
        }
        val localId = writableDatabase.insert("local_trips", null, values)
        Log.d(TAG, "로컬 저장 완료: local #$localId (pending)")
        return localId
    }

    // 서버 전송 성공 시 synced로 업데이트
    fun markSynced(localId: Long, serverId: Int) {
        val values = ContentValues().apply {
            put("status", "synced")
            put("server_id", serverId)
        }
        writableDatabase.update("local_trips", values, "id=?", arrayOf(localId.toString()))
        Log.d(TAG, "로컬 동기화 완료: local #$localId -> server #$serverId")
    }

    // 목적지/요금 업데이트
    fun updateDestination(localId: Long, destination: String, destLat: Double, destLng: Double, fare: Int = 0) {
        val values = ContentValues().apply {
            put("destination", destination)
            put("dest_lat", destLat)
            put("dest_lng", destLng)
            if (fare > 0) put("fare", fare)
        }
        writableDatabase.update("local_trips", values, "id=?", arrayOf(localId.toString()))
    }

    // pending 항목 전체 가져오기
    fun getPendingTrips(): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        val cursor = readableDatabase.query(
            "local_trips", null, "status=?", arrayOf("pending"),
            null, null, "created_at ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val map = mutableMapOf<String, Any?>()
                for (i in 0 until it.columnCount) {
                    map[it.getColumnName(i)] = when (it.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_INTEGER -> it.getLong(i)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> it.getDouble(i)
                        else -> it.getString(i)
                    }
                }
                result.add(map)
            }
        }
        return result
    }

    // pending 항목 서버로 재전송
    fun syncPendingTrips(context: Context) {
        val pending = getPendingTrips()
        if (pending.isEmpty()) return
        Log.d(TAG, "재전송 대기 항목: ${pending.size}건")
        pending.forEach { trip ->
            val localId = (trip["id"] as? Long) ?: return@forEach
            Thread {
                try {
                    val json = JSONObject().apply {
                        put("user_id", trip["user_id"])
                        put("platform", trip["platform"] ?: "")
                        put("originName", trip["origin"] ?: "")
                        put("destName", trip["destination"] ?: "")
                        put("depLat", trip["origin_lat"] ?: 0.0)
                        put("depLng", trip["origin_lng"] ?: 0.0)
                        put("destLat", trip["dest_lat"] ?: 0.0)
                        put("destLng", trip["dest_lng"] ?: 0.0)
                        put("fare", trip["fare"] ?: 0)
                    }
                    val conn = (URL("$SERVER_URL/api/trips").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                        doOutput = true
                        connectTimeout = 30000
                        readTimeout = 30000
                    }
                    conn.outputStream.write(json.toString().toByteArray())
                    val responseJson = JSONObject(conn.inputStream.bufferedReader().readText())
                    val serverId = responseJson.optInt("id", -1)
                    if (serverId > 0) {
                        markSynced(localId, serverId)
                        Log.d(TAG, "재전송 성공: local #$localId -> server #$serverId")
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "재전송 실패: local #$localId - ${e.message}")
                }
            }.start()
        }
    }
}