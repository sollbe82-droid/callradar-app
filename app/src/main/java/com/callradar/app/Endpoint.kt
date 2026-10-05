package com.callradar.app

import android.content.Context

/**
 * 서버 주소의 유일한 출처.
 *
 * 왜 만들었나 (2026-10-05 실측):
 *   `Config.SERVER_URL` 이 "서버 주소 한 곳 관리" 라고 적혀 있었지만 사실이 아니었다.
 *   전수 스캔 결과 운영 URL 이 **27개 파일에 34곳** 직접 박혀 있었고, Config 를 타는 것은
 *   9개 파일뿐이었다. 즉 Config.SERVER_URL 만 바꿔도 나머지 요청은 전부 운영으로 갔다.
 *   검증 환경을 세우고 "로컬로 붙였다" 고 말할 수 있는 상태가 아니었다.
 *
 *   하드코딩 34곳 목록: _handover_20261002/measure/urls_20261005.txt
 *
 * 규칙
 *   · 앱 코드에서 서버 주소는 **반드시** `Endpoint.base` 또는 `Endpoint.url(path)` 로만 만든다.
 *     리터럴 "https://callradar-server.onrender.com" 을 다시 쓰면 안 된다.
 *     (검사: `node _scan_urls.js` — Endpoint.kt 밖의 리터럴이 0건이어야 한다)
 *   · 기본값은 **운영**이다. 아무 설정도 없으면 운영으로 간다 — 실수로 로컬을 보지 않게.
 *   · 전환은 **디버그 빌드에서만** 가능하다. release 에서는 override 를 읽지 않는다.
 *     스토어에 나간 앱이 검증 서버를 보는 사고를 코드로 막는다.
 */
object Endpoint {

    /** 운영 서버. 이 문자열은 이 파일에만 존재한다. */
    const val PROD = "https://callradar-server.onrender.com"

    private const val PREFS = "callradar_prefs"
    /** 디버그 전용 전환 키. adb 로 넣는다 — UI 를 만들지 않는다(오조작 방지). */
    const val KEY_OVERRIDE = "server_url_override"

    @Volatile
    private var current: String = PROD

    /** 지금 쓰는 서버 주소. 끝에 / 가 없다. */
    val base: String get() = current

    /** 운영을 보고 있나. 화면 배너·로그에 쓴다. */
    val isProd: Boolean get() = current == PROD

    fun url(path: String): String =
        if (path.startsWith("/")) current + path else "$current/$path"

    /**
     * 앱 시작 시 한 번 호출한다(Application/MainActivity).
     * release 빌드에서는 override 를 무시하고 운영으로 고정한다.
     */
    fun init(ctx: Context) {
        current = PROD
        if (!BuildConfig.DEBUG) return
        try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_OVERRIDE, null)?.trim().orEmpty()
            if (raw.isEmpty()) return
            // http(s) 로 시작하고 공백이 없을 때만 받는다. 잘못된 값으로 앱이 먹통이 되지 않게.
            if (!(raw.startsWith("http://") || raw.startsWith("https://"))) return
            if (raw.contains(' ')) return
            current = raw.trimEnd('/')
        } catch (e: Exception) {
            current = PROD
        }
    }
}
