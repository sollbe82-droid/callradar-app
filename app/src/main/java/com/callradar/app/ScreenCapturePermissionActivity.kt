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
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
        } catch (e: Exception) {
            Toast.makeText(this, "화면 공유를 시작할 수 없어요", Toast.LENGTH_SHORT).show()
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
        } else {
            Toast.makeText(this, "화면 공유가 취소됐어요", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
