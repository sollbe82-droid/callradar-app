package com.callradar.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 계정 권한 플래그(`is_admin` / `auto_entitled` / `free_open`) 서버 조회.
 *
 * ★ 왜 별도 파일로 뺐나 (2026-08-30 유저 108 제보 추적 중 발견)
 *
 *  이 조회가 **홈 화면 안에 LaunchedEffect 로 박혀 있었다.** 그런데
 *  `NaviIntentReceiver.onAccessibilityEvent` 첫 줄이 `if (!isAdmin() || !autoOn()) return` 이라
 *  이 플래그가 꺼지면 **자동기록이 로그 한 줄 없이 죽는다.**
 *
 *  그래서 **간편모드 유저(145명)는 한 번 꺼지면 영영 복구가 안 됐다.**
 *  간편홈은 prefs 를 읽기만 하고 이 조회를 안 하기 때문이다. 앱을 아무리 껐다 켜도 소용없고,
 *  홈모드로 바꿔야만 풀렸다 — 기사는 그걸 알 방법이 없다.
 *
 *  이제 두 화면이 같은 함수를 부른다. 자가 치유가 어느 모드에서든 돈다.
 */
object AccountFlags {

    /** 서버에서 플래그를 받아 prefs 에 반영한다. 돌려주는 값 = (관리자, 자동기록 자격). */
    suspend fun refresh(ctx: Context, userId: String): Pair<Boolean, Boolean>? {
        if (userId.isBlank()) return null
        val prefs = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        return try {
            // 앱 버전을 같이 보낸다(스토어 구분용). 버전 문자열뿐 — 기기 식별자나 개인정보가 아니다.
            val v = java.net.URLEncoder.encode(BuildConfig.VERSION_NAME, "UTF-8")
            val body = withContext(Dispatchers.IO) {
                (URL("${com.callradar.app.screen.Config.SERVER_URL}/api/users/$userId/flags?v=$v")
                    .openConnection().apply {
                        Auth.tok?.let { t -> if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t") }
                    } as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 12000 }
                    .inputStream.bufferedReader().readText()
            }
            val o = JSONObject(body)
            val ia = o.optBoolean("is_admin", false)
            val ae = o.optBoolean("auto_entitled", false)
            val fo = o.optBoolean("free_open", false)

            /* ★★ 권한을 **끄는 방향**은 응답 하나로 바꾸지 않는다.
             *  서버가 오류일 때 200 + 전부 false 를 주던 시절, 앱이 그걸 저장해서
             *  DB 한 번 삐끗에 자동기록이 통째로 죽었다(실측 15명·누락 3%).
             *  서버는 이제 503 을 주지만(= catch 로 빠짐) 앱도 스스로 지킨다.
             *  세 값이 모두 false 인 건 "권한 없음"과 "조회 실패"가 구분되지 않는다.
             *  **켜는 건 즉시, 끄는 건 확실할 때만** — 진짜 회수는 `revoked: true` 로 명시한다. */
            val allFalse = !ia && !ae && !fo
            val hadAny = prefs.getBoolean("is_admin", false) ||
                         prefs.getBoolean("acct_entitled", false) ||
                         prefs.getBoolean("auto_free_open", false)
            if (allFalse && hadAny && !o.optBoolean("revoked", false)) {
                android.util.Log.w("CRFlags", "권한 전부 false — 기존 권한 유지(조회 실패로 본다)")
                return Pair(prefs.getBoolean("is_admin", false),
                            prefs.getBoolean("acct_entitled", false) || prefs.getBoolean("auto_free_open", false))
            }
            /* [v110] 업데이트 안내 — 서버가 내려주면 prefs 에 담아 두고 홈이 팝업으로 띄운다.
             *  왜 여기냐: 이 호출은 앱이 뜰 때 이미 버전을 실어 보내고 있다. 새 통신을 만들 필요가 없다.
             *  ★ 안내가 없으면 **지운다.** 안 지우면 업데이트한 뒤에도 팝업이 계속 뜬다. */
            val up = o.optJSONObject("update")
            prefs.edit().apply {
                if (up != null) {
                    putString("upd_required", up.optString("required", ""))
                    putString("upd_reason", up.optString("reason", ""))
                    putString("upd_store", up.optString("store", "play"))
                    putBoolean("upd_force", up.optBoolean("force", false))
                } else {
                    remove("upd_required"); remove("upd_reason"); remove("upd_store"); remove("upd_force")
                }
            }.apply()

            prefs.edit().putBoolean("acct_admin", ia).putBoolean("acct_entitled", ae)
                .putBoolean("is_admin", ia).putBoolean("auto_free_open", fo).apply()
            Pair(ia, ae || fo)
        } catch (e: Exception) {
            // 실패 시 prefs 를 건드리지 않는다 — 기존 권한이 유지된다.
            null
        }
    }
}
