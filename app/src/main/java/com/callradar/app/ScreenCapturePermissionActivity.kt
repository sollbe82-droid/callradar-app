package com.callradar.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * [v18] MediaProjection 화면캡처 동의 액티비티 (투명).
 *  - 유저가 "화면 스샷 공유"를 누르면 이 액티비티가 열려 시스템 화면캡처 동의창을 띄운다.
 *  - 동의하면 결과(resultCode/data)를 ScreenCaptureService로 넘겨 1프레임만 캡처 → 워터마크 → 공유.
 *  - 완전히 유저 개시(user-initiated), 포그라운드, 유저가 결과를 눈으로 봄 → Play 심사 안전 범위.
 */
class ScreenCapturePermissionActivity : Activity() {

    private val REQ = 7001

    /**
     * 이 동의가 무슨 용도인지 — 안내 문구를 맞추려면 알아야 한다.
     * [v91] 캡처를 쓰고 나서 "금액 자동입력이 취소됐어요"가 떠서 기사님이 놀랐다.
     *   그 문구는 종료요금 OCR용인데 화면 공유 흐름에서도 그대로 나왔다.
     *   서비스가 purpose를 지워버리므로(readAndClearPurpose) 여기서 미리 읽어둔다.
     */
    private var purpose: String = ""
    private val isShare get() = purpose == "share"

    companion object {
        /** 어디서든 이 화면공유 흐름을 시작하는 진입점 */
        fun start(context: Context) {
            val i = Intent(context, ScreenCapturePermissionActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        purpose = try {
            getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).getString("capture_purpose", "") ?: ""
        } catch (e: Exception) { "" }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // [v24 fix] Android 14+ 는 기본이 '앱 하나 공유'(앱 선택창)라 요금화면 캡처가 꼬임(=금액인식 안 됨).
            //  전체화면 캡처로 강제 → 앱 선택 단계 제거 + 타앱 요금화면 확실히 캡처(공유 단계도 줄어듦).
            val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
                try {
                    mpm.createScreenCaptureIntent(
                        android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                    )
                } catch (e: Throwable) { mpm.createScreenCaptureIntent() }
            } else mpm.createScreenCaptureIntent()
            startActivityForResult(captureIntent, REQ)
        } catch (e: Exception) {
            Toast.makeText(this,
                if (isShare) "화면 읽기를 시작할 수 없어요" else "금액 인식용 화면 읽기를 시작할 수 없어요",
                Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == Activity.RESULT_OK && data != null) {
            val svc = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        } else if (isShare) {
            // 캡처를 취소한 것 — 굳이 알릴 일이 아니다(본인이 취소 눌렀으니). 조용히 닫는다.
            //  [v91] 여기서 "금액 자동인식이 취소됐어요"가 떠서 기사님이 놀랐다. 용도가 다르다.
            try { getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE).edit().remove("capture_purpose").apply() } catch (e: Exception) {}
        } else {
            Toast.makeText(this, "금액 자동인식이 취소됐어요 (기록에서 직접 입력 가능)", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
