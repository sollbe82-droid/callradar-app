package com.callradar.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * [v92] 기사 설정 자동 복원 · 점검
 *
 *  ── 왜 만들었나 ────────────────────────────────────────────────
 *  서버(driver_settings)에 사납금·수수료·가스단가·가맹형태를 백업해두고도
 *  **복원 코드가 '기사 설정' 화면 안에만 있었다.** 기사가 그 화면에 직접 들어가야만
 *  값이 돌아왔다.
 *
 *  그래서 기기를 바꾸거나, 앱을 지웠다 깔거나, 스토어를 옮기면(구글↔원스토어는
 *  서명이 달라 재설치가 된다) 로컬이 비고, 기사는 그 사실조차 모른 채
 *   · 사납금 0원으로 순수입이 부풀려진 정산을 보고
 *   · 나중에 설정 화면에서 뭐 하나 고쳐 저장하는 순간, 로컬 기본값이 통째로 올라가
 *     **서버에 남아 있던 원래 값까지 지웠다**
 *  실측으로 26명이 이렇게 비워졌고, 법인기사인데 사납금 0인 사람이 30명이다.
 *
 *  백업이 있는데 안 돌려준 건 우리 잘못이다. 서버 쪽 덮어쓰기는 이미 막았고,
 *  여기서는 '앱이 시작할 때 알아서 가져오게' 만든다.
 *
 *  ── 원칙 ──────────────────────────────────────────────────────
 *  · 로컬에 값이 있으면 건드리지 않는다. 서버는 '빈 칸을 메우는' 용도다.
 *    (로컬이 최신일 수 있다. 덮어쓰면 같은 실수를 반대 방향으로 반복하는 셈)
 *  · 실패는 조용히 넘어간다. 설정 복원 때문에 앱이 느려지거나 멈추면 안 된다.
 */
object SettingsSync {

    private const val SERVER = "https://callradar-server.onrender.com"

    /** 앱 시작(로그인 직후) 1회 — 서버 설정으로 로컬 빈 칸을 메운다 */
    fun restore(ctx: Context, userId: String) {
        if (userId.isBlank()) return
        Thread {
            try {
                val conn = (URL("$SERVER/api/driver-settings/$userId").openConnection().apply {
                    Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") }
                } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 20000 }
                val j = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()

                val p = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
                val e = p.edit()
                var filled = 0

                // 문자열: 로컬이 비었을 때만
                fun str(key: String, jsonKey: String) {
                    val cur = p.getString(key, "") ?: ""
                    val srv = j.optString(jsonKey, "")
                    if (cur.isBlank() && srv.isNotBlank()) { e.putString(key, srv); filled++ }
                }
                // 숫자: 로컬이 0(=미입력)일 때만. 서버도 0이면 메울 게 없다.
                fun num(key: String, jsonKey: String) {
                    val cur = p.getInt(key, 0)
                    val srv = j.optInt(jsonKey, 0)
                    if (cur == 0 && srv != 0) { e.putInt(key, srv); filled++ }
                }

                str("driver_type", "driver_type")
                // affiliation은 기본값이 'none'이라 '안 고른 것'과 '비가맹'이 구분되지 않는다.
                //  로컬이 비었거나 none인데 서버에 실제 선택값(kakao/uber)이 있으면 그게 진짜다.
                val affLocal = p.getString("affiliation", "") ?: ""
                val affSrv = j.optString("affiliation", "")
                if ((affLocal.isBlank() || affLocal == "none") && affSrv.isNotBlank() && affSrv != "none") {
                    e.putString("affiliation", affSrv); filled++
                }

                // 서버 키 ↔ 로컬 키가 이름이 다른 게 있다(gas_price ↔ lpg_price). 저장 코드 기준으로 맞춘다.
                num("daily_sanap", "daily_payment")     // 사납금 — 정산에 가장 큰 영향
                num("lpg_price", "gas_price")           // 가스 단가
                num("work_days", "work_days")
                num("annual_leave", "annual_leave")
                num("profit_share", "profit_share")
                num("lpg_refund_rate", "lpg_refund_rate")
                // commission_rate는 복원하지 않는다 — 서버에는 카카오·우버·티머니 수수료를 '합산한 값'만
                //  올라가 있어서 되돌릴 수가 없다(3%+3%+3%=9%인지 9%+0%+0%인지 구분 불가).
                //  개별 수수료(fee_*)는 원래부터 로컬 전용이다. 다음에 서버 스키마를 나눌 때 같이 고친다.

                if (filled > 0) {
                    e.apply()
                    android.util.Log.d("SettingsSync", "서버에서 설정 $filled 항목 복원")
                }
            } catch (ex: Exception) {
                // 네트워크 실패 등 — 다음 실행에서 다시 시도된다
            }
        }.start()
    }

    data class Issue(
        val code: String, val severity: String, val action: String,
        val title: String, val body: String, val cta: String
    )

    /**
     * 설정 점검 — 서버가 판단한 '지금 이 기사에게 있는 문제'.
     *
     *  앱이 상태를 기억하지 않는 게 핵심이다. 값이 채워지면 다음 호출에서 그냥 사라진다.
     *  (오늘 설치 점검 카드가 '켰는데도 안 사라지던' 문제를 겪었다. 판단을 한 곳에만 둔다)
     */
    fun health(ctx: Context, userId: String, onResult: (List<Issue>) -> Unit) {
        if (userId.isBlank()) { onResult(emptyList()); return }
        Thread {
            val out = ArrayList<Issue>()
            try {
                val conn = (URL("$SERVER/api/settings/health/$userId").openConnection().apply {
                    Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") }
                } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 20000 }
                val arr = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("issues")
                conn.disconnect()
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(Issue(
                        o.optString("code"), o.optString("severity", "normal"),
                        o.optString("action", ""), o.optString("title"),
                        o.optString("body"), o.optString("cta", "확인")
                    ))
                }
            } catch (ex: Exception) { }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(out) }
        }.start()
    }

    /** '나중에' 누른 뒤 7일간 숨김 — 끌 수 없는 경고는 잔소리가 된다 */
    fun snooze(ctx: Context, code: String) {
        ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            .edit().putLong("issue_snooze_$code", System.currentTimeMillis() + 7L * 86400_000L).apply()
    }

    fun snoozed(ctx: Context, code: String): Boolean =
        ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            .getLong("issue_snooze_$code", 0L) > System.currentTimeMillis()
}
