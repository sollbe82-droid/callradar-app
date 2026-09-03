package com.callradar.app

import android.content.Context
import android.provider.Settings

/**
 * 접근성 서비스가 **OS 에서 실제로 켜져 있는지** 판정한다.
 *
 * ★ 왜 만들었나 (2026-09-03 유저 103 제보 조사 중)
 *   "사용 중에 접근성이 풀린다." 조사해 보니 진짜 문제는 풀리는 것 자체가 아니라
 *   **앱이 그걸 기사에게 안 알려준다**는 것이었다.
 *
 *   홈 화면은 `prefs("auto_record_on")` 만 보고 "🤖 자동 기록 켜짐"을 표시했다.
 *   그건 **앱 설정값**이지 OS 상태가 아니다. 안드로이드에서 접근성이 꺼져도
 *   화면은 계속 "켜짐"이라고 말한다 → 기사는 켜져 있다고 믿고 운행한다.
 *
 *   그리고 같은 문자열 비교가 네 곳에 흩어져 있었고, 그중 **간편홈에는 아예 없었다**(154명).
 *   그래서 판정을 한 곳으로 모았다 — 정관: 같은 지표의 가드가 파일마다 다르면 이미 사고가 난 것.
 *
 * ★ 서비스 이름까지 비교한다. 패키지명만 보면 우리 앱의 **다른** 접근성 서비스가 켜져 있을 때도
 *   true 가 되어 자동기록이 되는 것처럼 보인다.
 */
object AccessibilityState {

    private const val SERVICE = "com.callradar.app/com.callradar.app.NaviIntentReceiver"

    /** OS 설정에서 우리 접근성 서비스가 켜져 있는가. */
    fun isOn(ctx: Context): Boolean = try {
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .contains(SERVICE)
    } catch (e: Exception) {
        // 읽기 실패를 '꺼짐'으로 단정하지 않는다 — 정관: 켜는 건 즉시, 끄는 건 확실할 때만.
        //  여기서 false 를 주면 잘 돌고 있는 기사에게 '꺼졌다'고 거짓 경고가 뜬다.
        true
    }

    /** 기사가 자동기록을 켜뒀는데 OS 에서는 꺼져 있는 상태 — 이게 곧 '풀렸다'다. */
    fun isBroken(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
        return p.getBoolean("auto_record_on", false) && !isOn(ctx)
    }
}
