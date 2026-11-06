package com.luminaauth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED
            && action != Intent.ACTION_LOCKED_BOOT_COMPLETED
            && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!PrefUtils.isAutoAuthEnabled(context)) return
        try {
            val svc = Intent(context, AutoLoginService::class.java)
            svc.setPackage(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
            LogBuffer.add("SVC", "boot svc=start")
        } catch (e: Exception) {
            LogBuffer.add("SVC", "boot svc=start_fail err=${e.message}")
        }
    }
}
