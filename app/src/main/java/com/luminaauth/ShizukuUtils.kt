package com.luminaauth

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

object ShizukuUtils {
    private var shellBinder: IShell? = null
    private var binding = false

    @JvmStatic
    fun isShizukuAvailable(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    @JvmStatic
    fun isPermissionGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    @JvmStatic
    fun isReady(): Boolean = isShizukuAvailable() && isPermissionGranted()

    @JvmStatic
    @Synchronized
    fun init(context: Context) {
        try {
            if (!isReady() || shellBinder != null || binding) return
            binding = true
            val componentName = ComponentName(context, ShellUserService::class.java)
            val args = Shizuku.UserServiceArgs(componentName)
            Shizuku.bindUserService(args, conn)
        } catch (_: Throwable) { binding = false }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellBinder = IShell.Stub.asInterface(service)
            binding = false
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            shellBinder = null
            binding = false
        }
    }

    @JvmStatic
    fun exec(command: String): String? = try {
        shellBinder?.exec(command)
    } catch (_: Throwable) { null }

    @JvmStatic
    fun getWifiSsidViaShizuku(context: Context): String? {
        init(context)
        var out = exec("cmd wifi status")
        var ssid = parseConnectedSsid(out)
        if (!ssid.isNullOrEmpty()) return ssid
        out = exec("dumpsys wifi")
        if (out != null) {
            val m = Regex("SSID:\\s*\"?([^\",\\s]+)").find(out)
            if (m != null) return m.groupValues[1].trim()
        }
        return null
    }

    private fun parseConnectedSsid(out: String?): String? {
        if (out == null) return null
        val m = Regex("connected\\s+to\\s+[\"']?([^\"'\\n]+)[\"']?", RegexOption.IGNORE_CASE).find(out)
        return m?.groupValues?.get(1)?.trim()
    }
}
