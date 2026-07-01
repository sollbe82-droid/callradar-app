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
        val locationIntent = Intent(context, LocationTrackingService::class.java)
        context.startForegroundService(locationIntent)
    }
}