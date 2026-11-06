package com.luminaauth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build

class AutoLoginReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TRIGGER_CHECK = "com.luminaauth.TRIGGER_CHECK"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val networkChanged = WifiManager.NETWORK_STATE_CHANGED_ACTION == action
        val suppChanged = WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION == action
        val connectivityChanged = "android.net.conn.CONNECTIVITY_CHANGE" == action
        if (!networkChanged && !suppChanged && !connectivityChanged) return
        if (!PrefUtils.isAutoAuthEnabled(context)) return
        try {
            val svc = Intent(context, AutoLoginService::class.java)
            svc.setAction(ACTION_TRIGGER_CHECK)
            svc.setPackage(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (_: Exception) {}
    }
}
