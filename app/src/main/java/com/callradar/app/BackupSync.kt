package com.callradar.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * [v32] 기기변경/재설치 대비 로컬 설정 서버 백업/복원.
 *  - 회사프로필(company_profiles) + 급여/정산 설정 번들(payroll_v1)을 서버 /api/backup 에 저장.
 *  - 복원은 '기변 첫 실행'에서 로컬에 없는 키만 채운다(로컬 최신값 덮어쓰기 방지).
 *  본인 계정·본인 데이터 범위. 홈/정산 계산 로직은 무손상(prefs 키만 채움).
 */
object BackupSync {
    private const val SERVER = "https://callradar-server.onrender.com"
    private const val PREFS = "callradar_prefs"
    private const val KEY_PROFILES = "company_profiles"
    private const val KEY_PAYROLL = "payroll_v1"

    private val INT_KEYS = listOf(
        "pay_base", "pay_insurance", "pay_union", "pay_other_deduct",
        "daily_sanap", "work_days", "annual_leave", "profit_share", "lpg_refund_rate",
        "lpg_price", "lpg_daily", "gas_fixed", "lpg_daily_cost", "lpg_subsidy", "day_start_hour"
    )
    private val FLOAT_KEYS = listOf("gas_reduction_f", "fee_kakao", "fee_uber", "fee_tmoney")
    private val BOOL_KEYS = listOf("pay_zero_net", "cash_to_company")
    // [v43] work_session_log(과거 근무세션 요약 리스트) 추가 — 기기·스토어 바꿔도 지난 근무기록 유지(로컬전용이던 것 서버백업).
    private val STRING_KEYS = listOf("driver_type", "affiliation", "fuel_type", "gas_method", "active_company", "active_profile", "work_session_log")

    private fun uid(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("user_id", null)

    private fun post(userId: String, key: String, value: String) {
        try {
            val body = JSONObject().apply { put("user_id", userId); put("key", key); put("value", value) }
            val conn = (URL("$SERVER/api/backup").openConnection().apply {
                com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") }
            } as HttpURLConnection).apply {
                requestMethod = "POST"; setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true; connectTimeout = 8000; readTimeout = 8000
            }
            conn.outputStream.write(body.toString().toByteArray(Charsets.UTF_8))
            conn.responseCode; conn.disconnect()
        } catch (e: Exception) { Log.e("CallRadar", "backup push($key) 실패: ${e.message}") }
    }

    private fun fetch(userId: String, key: String): String? {
        return try {
            val conn = (URL("$SERVER/api/backup/$userId/$key").openConnection().apply {
                com.callradar.app.Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") }
            } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
            val txt = conn.inputStream.bufferedReader().readText()
            val j = JSONObject(txt)
            if (j.optBoolean("found", false)) j.optString("value", "") else null
        } catch (e: Exception) { Log.e("CallRadar", "backup fetch($key) 실패: ${e.message}"); null }
    }

    /** 회사프로필 문자열을 서버에 백업 */
    fun pushProfiles(ctx: Context) {
        val u = uid(ctx) ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val p = prefs.getString(KEY_PROFILES, null) ?: return
        Thread { post(u, KEY_PROFILES, p) }.start()
    }

    /** 급여/정산 설정 번들을 서버에 백업 */
    fun pushPayroll(ctx: Context) {
        val u = uid(ctx) ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Thread {
            try {
                val o = JSONObject()
                for (k in INT_KEYS) if (prefs.contains(k)) o.put(k, prefs.getInt(k, 0))
                for (k in FLOAT_KEYS) if (prefs.contains(k)) o.put(k, try { prefs.getFloat(k, 0f).toDouble() } catch (e: Exception) { prefs.getInt(k, 0).toDouble() })
                for (k in BOOL_KEYS) if (prefs.contains(k)) o.put(k, prefs.getBoolean(k, false))
                for (k in STRING_KEYS) if (prefs.contains(k)) o.put(k, prefs.getString(k, "") ?: "")
                if (o.length() > 0) post(u, KEY_PAYROLL, o.toString())
            } catch (e: Exception) { Log.e("CallRadar", "payroll 백업 실패: ${e.message}") }
        }.start()
    }

    fun pushAll(ctx: Context) { pushProfiles(ctx); pushPayroll(ctx) }

    /**
     * 기변 첫 실행 복원: 로컬에 없는 키만 서버값으로 채운다.
     * @param onDone 복원 시도 후 콜백(메인스레드 아님).
     */
    fun restore(ctx: Context, onDone: (() -> Unit)? = null) {
        val u = uid(ctx) ?: run { onDone?.invoke(); return }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Thread {
            try {
                // 회사프로필: 로컬에 아직 없을 때만(시드 주입 전) 복원
                if (!prefs.contains(KEY_PROFILES)) {
                    fetch(u, KEY_PROFILES)?.let { v ->
                        if (v.isNotBlank() && v.startsWith("[")) prefs.edit().putString(KEY_PROFILES, v).putBoolean("company_profiles_seeded", true).apply()
                    }
                }
                // 급여/정산 번들: 없는 키만 채움
                fetch(u, KEY_PAYROLL)?.let { v ->
                    if (v.isNotBlank() && v.startsWith("{")) {
                        val o = JSONObject(v); val ed = prefs.edit()
                        val it = o.keys()
                        while (it.hasNext()) {
                            val k = it.next()
                            if (prefs.contains(k)) continue
                            when {
                                INT_KEYS.contains(k) -> ed.putInt(k, o.optInt(k, 0))
                                FLOAT_KEYS.contains(k) -> ed.putFloat(k, o.optDouble(k, 0.0).toFloat())
                                BOOL_KEYS.contains(k) -> ed.putBoolean(k, o.optBoolean(k, false))
                                STRING_KEYS.contains(k) -> ed.putString(k, o.optString(k, ""))
                            }
                        }
                        ed.apply()
                    }
                }
            } catch (e: Exception) { Log.e("CallRadar", "복원 실패: ${e.message}") }
            onDone?.invoke()
        }.start()
    }
}
