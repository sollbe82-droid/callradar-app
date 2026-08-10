package com.callradar.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class LocalTripDatabase(context: Context) : SQLiteOpenHelper(context, "callradar.db", null, 4) {

    companion object {
        private const val TAG = "CallRadar"
        private const val SERVER_URL = "https://callradar-server.onrender.com"

        // [v31 fix-B] createTrip이 직접 전송 중인 localId — syncPendingTrips가 중복 전송하지 않도록.
        val handlingLocalIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

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
                client_uuid TEXT,
                created_at TEXT DEFAULT (datetime('now'))
            )
        """)
        createExpensesTable(db)
    }

    // [지출 오프라인 큐] 운행처럼 지출도 로컬 우선 저장 → 온라인 시 재전송(오프라인 유실 방지)
    private fun createExpensesTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS local_expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                status TEXT DEFAULT 'pending',
                user_id TEXT,
                category TEXT,
                amount INTEGER DEFAULT 0,
                expense_type TEXT DEFAULT 'business',
                memo TEXT,
                liters REAL DEFAULT 0,
                price_per_liter INTEGER DEFAULT 0,
                tax_deductible INTEGER DEFAULT 1,
                expense_date TEXT,
                client_uuid TEXT,
                created_at TEXT DEFAULT (datetime('now'))
            )
        """)
    }

    // [중요] onUpgrade에서 local_trips를 DROP하면 미전송(pending) 운행이 유실된다 → 파괴적 변경 금지.
    //  누락 컬럼/신규 테이블만 보강(additive). 데이터는 보존.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("CREATE TABLE IF NOT EXISTS local_trips (id INTEGER PRIMARY KEY AUTOINCREMENT)") // 없으면 생성(구조는 아래 보강)
        // 혹시 없던 컬럼 보강(구버전 → 신버전). 이미 있으면 무시.
        val addCols = listOf(
            "server_id INTEGER DEFAULT -1", "status TEXT DEFAULT 'pending'", "user_id TEXT", "platform TEXT",
            "origin TEXT", "destination TEXT", "origin_lat REAL DEFAULT 0", "origin_lng REAL DEFAULT 0",
            "dest_lat REAL DEFAULT 0", "dest_lng REAL DEFAULT 0", "fare INTEGER DEFAULT 0",
            "started_at TEXT", "client_uuid TEXT", "created_at TEXT"
        )
        for (c in addCols) try { db.execSQL("ALTER TABLE local_trips ADD COLUMN $c") } catch (e: Exception) {}
        createExpensesTable(db)
    }

    // 로컬에 pending으로 저장, 로컬 ID 반환
    fun savePending(
        userId: String?, platform: String, origin: String, destination: String,
        originLat: Double, originLng: Double, destLat: Double, destLng: Double,
        startedAt: String, clientUuid: String? = null
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
            if (!clientUuid.isNullOrBlank()) put("client_uuid", clientUuid)
            put("created_at", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))  // 업그레이드 DB(기본값 없는 created_at) NULL 방지 → 재전송 순서 보존
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

    // [v31] 금액(+플랫폼)만 로컬 업데이트 — 오프라인 QuickEntry용(좌표 보존)
    //  [fix-C] 이미 서버 전송(synced)된 행이면 status='fare_pending'으로 표시 → syncFareUpdates가 서버에 금액을 마저 반영.
    fun updateFare(localId: Long, fare: Int, platform: String? = null) {
        var serverId = -1
        try {
            readableDatabase.query("local_trips", arrayOf("server_id"), "id=?", arrayOf(localId.toString()), null, null, null).use {
                if (it.moveToNext()) serverId = it.getInt(0)
            }
        } catch (e: Exception) {}
        val values = ContentValues().apply {
            if (fare > 0) put("fare", fare)
            if (!platform.isNullOrBlank()) put("platform", platform)
            if (serverId > 0 && fare > 0) put("status", "fare_pending")   // 서버 반영 대기
        }
        if (values.size() > 0) writableDatabase.update("local_trips", values, "id=?", arrayOf(localId.toString()))
    }

    // [v31 fix-C] synced됐지만 금액이 서버에 안 올라간 행 → PUT /api/trips/{server_id}로 금액 반영.
    fun syncFareUpdates() {
        val rows = try {
            val out = mutableListOf<Triple<Long, Int, Pair<Int, String>>>()
            readableDatabase.query("local_trips", arrayOf("id", "server_id", "fare", "platform"), "status=?", arrayOf("fare_pending"), null, null, null).use {
                while (it.moveToNext()) out.add(Triple(it.getLong(0), it.getInt(1), Pair(it.getInt(2), it.getString(3) ?: "")))
            }
            out
        } catch (e: Exception) { return }
        for ((localId, serverId, fp) in rows) {
            if (serverId <= 0) continue
            Thread {
                try {
                    val json = JSONObject().apply { if (fp.first > 0) put("fare", fp.first); if (fp.second.isNotBlank()) put("platform", fp.second) }
                    val conn = (URL("$SERVER_URL/api/trips/$serverId").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                        requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 30000; readTimeout = 30000
                    }
                    conn.outputStream.use { it.write(json.toString().toByteArray()) }
                    if (conn.responseCode in 200..299) {
                        writableDatabase.update("local_trips", ContentValues().apply { put("status", "synced") }, "id=?", arrayOf(localId.toString()))
                    }
                    conn.disconnect()
                } catch (e: Exception) {}
            }.start()
        }
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
            // [fix-B] createTrip이 지금 이 행을 직접 전송 중이면 건너뜀(중복 방지)
            if (handlingLocalIds.contains(localId)) return@forEach
            Thread {
                try {
                    // [fix-A] createTrip과 동일한 /api/trips/manual + 동일 필드(fare·started_at 보존).
                    //  기존 /api/trips는 fare를 무시하고 started_at을 안 넣어 트립이 통계에서 사라졌음.
                    val json = JSONObject().apply {
                        put("user_id", trip["user_id"])
                        put("platform", trip["platform"] ?: "길빵/예약")
                        put("originName", trip["origin"] ?: "")
                        put("destName", trip["destination"] ?: "")
                        put("origin_lat", trip["origin_lat"] ?: 0.0)
                        put("origin_lng", trip["origin_lng"] ?: 0.0)
                        put("dest_lat", trip["dest_lat"] ?: 0.0)
                        put("dest_lng", trip["dest_lng"] ?: 0.0)
                        put("fare", trip["fare"] ?: 0)
                        put("started_at", trip["started_at"])
                        put("source", "gps")
                        put("payment_type", "cash")
                        trip["client_uuid"]?.let { put("client_uuid", it) }   // [fix-B] 멱등키
                    }
                    val conn = (URL("$SERVER_URL/api/trips/manual").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        doOutput = true
                        connectTimeout = 30000
                        readTimeout = 30000
                    }
                    conn.outputStream.write(json.toString().toByteArray(Charsets.UTF_8))
                    val serverId = if (conn.responseCode in 200..299) JSONObject(conn.inputStream.bufferedReader().readText()).optInt("id", -1) else -1
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

    // ===== [지출 오프라인 큐] 운행과 동일 패턴: 로컬 우선 저장 → 온라인 시 재전송 =====

    // 지출을 로컬 pending으로 저장(로컬 ID 반환). 오프라인에서도 절대 유실 안 되게.
    fun savePendingExpense(
        userId: String?, category: String, amount: Int, expenseType: String,
        memo: String, liters: Double = 0.0, pricePerLiter: Int = 0,
        taxDeductible: Boolean = true, expenseDate: String? = null, clientUuid: String? = null
    ): Long {
        val values = ContentValues().apply {
            put("status", "pending")
            put("user_id", userId ?: "")
            put("category", category)
            put("amount", amount)
            put("expense_type", expenseType)
            put("memo", memo)
            put("liters", liters)
            put("price_per_liter", pricePerLiter)
            put("tax_deductible", if (taxDeductible) 1 else 0)
            if (!expenseDate.isNullOrBlank()) put("expense_date", expenseDate)
            if (!clientUuid.isNullOrBlank()) put("client_uuid", clientUuid)
            put("created_at", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))  // 업그레이드 DB(기본값 없는 created_at) NULL 방지 → 재전송 순서 보존
        }
        val localId = writableDatabase.insert("local_expenses", null, values)
        Log.d(TAG, "지출 로컬 저장: local_exp #$localId (pending)")
        return localId
    }

    fun markExpenseSynced(localId: Long) {
        writableDatabase.update("local_expenses", ContentValues().apply { put("status", "synced") }, "id=?", arrayOf(localId.toString()))
    }

    // pending 지출을 서버로 재전송. 성공한 것만 synced 표시. (앱 실행/온라인 복귀 시 호출)
    fun syncPendingExpenses(context: Context) {
        val pending = mutableListOf<Map<String, Any?>>()
        try {
            readableDatabase.query("local_expenses", null, "status=?", arrayOf("pending"), null, null, "created_at ASC").use {
                while (it.moveToNext()) {
                    val m = mutableMapOf<String, Any?>()
                    for (i in 0 until it.columnCount) m[it.getColumnName(i)] = when (it.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_INTEGER -> it.getLong(i)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> it.getDouble(i)
                        else -> it.getString(i)
                    }
                    pending.add(m)
                }
            }
        } catch (e: Exception) { return }
        if (pending.isEmpty()) return
        Log.d(TAG, "지출 재전송 대기: ${pending.size}건")
        pending.forEach { exp ->
            val localId = (exp["id"] as? Long) ?: return@forEach
            Thread {
                try {
                    val json = JSONObject().apply {
                        put("user_id", exp["user_id"])
                        put("category", exp["category"] ?: "기타")
                        put("amount", exp["amount"] ?: 0)
                        put("expense_type", exp["expense_type"] ?: "business")
                        put("memo", exp["memo"] ?: "")
                        (exp["liters"] as? Double)?.let { if (it > 0) put("liters", it) }
                        (exp["price_per_liter"] as? Long)?.let { if (it > 0) put("price_per_liter", it) }
                        put("tax_deductible", ((exp["tax_deductible"] as? Long) ?: 1L) != 0L)
                        (exp["expense_date"] as? String)?.let { if (it.isNotBlank()) put("expense_date", it) }  // [버그수정] 서버는 date가 아니라 expense_date를 읽음(오프라인 지출 날짜 밀림 방지)
                        (exp["client_uuid"] as? String)?.let { if (it.isNotBlank()) put("client_uuid", it) }     // 멱등키(재전송 중복 방지)
                    }
                    val conn = (URL("$SERVER_URL/api/expenses").openConnection().apply { com.callradar.app.Auth.tok?.let { _t -> if (_t.isNotBlank()) setRequestProperty("Authorization", "Bearer $_t") } } as HttpURLConnection).apply {
                        requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true; connectTimeout = 15000; readTimeout = 30000
                    }
                    conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode in 200..299) { markExpenseSynced(localId); Log.d(TAG, "지출 재전송 성공: local_exp #$localId") }
                    conn.disconnect()
                } catch (e: Exception) { Log.e(TAG, "지출 재전송 실패: local_exp #$localId - ${e.message}") }
            }.start()
        }
    }
}