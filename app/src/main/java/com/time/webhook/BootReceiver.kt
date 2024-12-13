package com.time.webhook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val config = Config.getInstance(context)
            if (config.isConfigured()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(Intent(context, MonitorService::class.java))
                } else {
                    context.startService(Intent(context, MonitorService::class.java))
                }
            }
        }
    }
}