package com.callradar.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d("CallRadar", "자동 시작: ${intent.action}")
                startServices(context)
            }
        }
    }

    private fun startServices(context: Context) {
        // [자동실행 최소화] 부팅/업데이트 후 무조건 켜던 것 → '자동기록을 켠 사용자'만 위치서비스 복원.
        //  자동기록 안 쓰는 사용자는 아무것도 시작 안 함(재부팅·업데이트 후 콜레이더가 저절로 도는 문제 해결).
        //  boot_autostart=false로 두면 자동기록 사용자도 부팅 자동시작을 끌 수 있음.
        try {
            val prefs = context.getSharedPreferences("callradar_prefs", Context.MODE_PRIVATE)
            val autoOn = prefs.getBoolean("auto_record_on", false)
            if (!autoOn) return
            if (!prefs.getBoolean("boot_autostart", true)) return
            context.startForegroundService(Intent(context, LocationTrackingService::class.java))
        } catch (e: Exception) {}
    }
}