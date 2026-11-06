package com.luminaauth

import android.content.Context
import android.net.wifi.WifiManager
import com.luminaauth.plugin.Outcome
import com.luminaauth.plugin.Plugin
import com.luminaauth.plugin.PluginManager
import com.luminaauth.plugin.PluginRuntime
import com.luminaauth.plugin.SessionState
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object LoginService {

    @JvmStatic
    fun getLocalIPv4(context: Context): String? {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.let {
                val ip = it.connectionInfo.ipAddress
                if (ip != 0) {
                    return (ip and 0xFF).toString() + "." + ((ip shr 8) and 0xFF) + "." +
                            ((ip shr 16) and 0xFF) + "." + ((ip shr 24) and 0xFF)
                }
            }
        } catch (_: Exception) {}
        try {
            var fallback: String? = null
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                val ifName = (ni.name ?: "").lowercase()
                for (addr in Collections.list(ni.inetAddresses)) {
                    if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                    val ip = addr.hostAddress
                    if (ifName.startsWith("wlan") || ip.startsWith("192.168.")
                        || ip.startsWith("10.") || ip.startsWith("172.")) {
                        return ip
                    }
                    if (fallback == null) fallback = ip
                }
            }
            return fallback
        } catch (_: Exception) {}
        return null
    }

    @JvmStatic
    @Throws(Exception::class)
    fun checkNetworkStatus(ip: String): String {
        val plugin = PluginManager.active(AppContext.get())
        val state = SessionState(plugin, HashMap(), null)
        state.ip = ip
        val outcome = PluginRuntime.check(state)
        return when (outcome) {
            Outcome.ONLINE -> "online"
            Outcome.OFFLINE -> "offline"
            else -> "unreachable"
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun doLogin(username: String, password: String, isp: String?, ip: String): LoginResult {
        val plugin = PluginManager.active(AppContext.get())
        val inputs = HashMap<String, String>()
        inputs[Plugin.FIELD_USERNAME] = username
        inputs[Plugin.FIELD_PASSWORD] = password
        val suffix = isp?.let { if (it.startsWith("@")) it.substring(1) else it }
        val state = SessionState(plugin, inputs, suffix)
        state.ip = ip
        state.loginUrlOverride = PluginRuntime.getEffectiveLoginUrl(
            AppContext.get(), PrefUtils.getLoginUrl(AppContext.get()))
        val outcome = PluginRuntime.login(state)
        return when (outcome) {
            Outcome.SUCCESS -> LoginResult(true, "认证成功")
            Outcome.ALREADY_ONLINE -> LoginResult(true, "已在线，无需操作")
            else -> {
                val detail = state.detail?.takeIf { it.isNotEmpty() } ?: state.reason
                LoginResult(false, "登录失败: $detail")
            }
        }
    }

    class LoginResult(@JvmField val success: Boolean, @JvmField val message: String)
}
