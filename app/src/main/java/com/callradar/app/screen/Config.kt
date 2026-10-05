package com.callradar.app.screen

import android.content.SharedPreferences

/* 서버 주소는 Endpoint 가 유일한 출처다.
 *  이 주석이 전엔 "서버 주소 한 곳 관리" 였는데 사실이 아니었다 — 운영 URL 이 27파일 34곳에
 *  직접 박혀 있었고 Config 를 타는 건 9파일뿐이었다(2026-10-05 전수 스캔). 그래서 Config 는
 *  이제 Endpoint 로 넘기는 얇은 껍데기이고, get() 으로 둬서 Endpoint.init() 이 나중에
 *  돌아도 반영된다(val 로 고정하면 object 초기화 시점 값이 박힌다). */
object Config {
    val SERVER_URL: String get() = com.callradar.app.Endpoint.base
    // [v43] 알림 자동캡처(금액 자동입력) 노출 — 대표 결정으로 Play·원스토어 둘 다 활성화.
    //   '[택시승인] …원' 카드결제 승인 알림에서 금액을 뽑아 현재 무금액 운행에 반영(유령운행 생성 없음).
    //   ※ Play는 NotificationListener 사용사유 심사 민감(반려·삭제 리스크 감수). Manifest의 CallCaptureService 주석 해제 병행.
    const val NOTIF_CAPTURE_ENABLED = true
}

// [v17] 플랫폼 수수료 소수점 지원. 기존엔 Int(putInt)로 저장돼 있어서
// 곧바로 getFloat 하면 ClassCastException으로 앱이 죽는다 → 최초 읽을 때 안전하게 Float로 이관.
fun feeRateFloat(prefs: SharedPreferences, key: String): Float {
    return try {
        prefs.getFloat(key, 0f)
    } catch (e: ClassCastException) {
        val v = try { prefs.getInt(key, 0).toFloat() } catch (e2: Exception) { 0f }
        prefs.edit().putFloat(key, v).apply()
        v
    }
}

// 소수점이 0이면 정수로, 아니면 1자리로 표시 (예: 3 → "3", 3.3 → "3.3")
fun fmtFee(v: Float): String =
    if (v == v.toLong().toFloat()) v.toLong().toString() else String.format("%.1f", v)
