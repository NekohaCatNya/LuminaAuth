package com.luminaauth

import android.content.Context
import android.content.SharedPreferences
import com.luminaauth.plugin.PluginManager

object PrefUtils {
    private const val PREF_NAME = "app_prefs"
    private const val KEY_AUTO_AUTH = "auto_auth"
    private const val KEY_POLL_INTERVAL = "poll_interval_ms"
    private const val KEY_WAIT_TIME = "wait_time_ms"
    private const val KEY_ENHANCED_MODE = "enhanced_mode"
    private const val KEY_SSID_LIST = "ssid_list"
    private const val KEY_LOGIN_URL = "login_url"
    private const val KEY_REMEMBER_PWD = "remember_pwd"
    private const val KEY_NOTIFY_DETECT = "notify_detect"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_AUTH_STATE = "auth_status"
    private const val KEY_AUTH_STATE_TIME = "auth_status_ts"
    private const val KEY_WIFI_DISCONNECT_TS = "wifi_disconnect_ts"
    private const val KEY_NET_CHANNEL = "net_channel"

    const val DEFAULT_POLL_INTERVAL_MS = 10000L
    const val DEFAULT_WAIT_TIME_MS = 5000L
    const val PALETTE_KEY_PREFIX = "palette_"

    @JvmStatic
    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun pluginDefaultSsidList(context: Context): String =
        PluginManager.defaultSsids(context).joinToString(",")

    private fun pluginDefaultLoginUrl(context: Context): String =
        PluginManager.defaultLoginUrl(context)

    @JvmStatic fun getPollIntervalMs(context: Context): Long =
        prefs(context).getLong(KEY_POLL_INTERVAL, DEFAULT_POLL_INTERVAL_MS)

    @JvmStatic fun setPollIntervalMs(context: Context, v: Long) =
        prefs(context).edit().putLong(KEY_POLL_INTERVAL, v).apply()

    @JvmStatic fun getWaitTimeMs(context: Context): Long =
        prefs(context).getLong(KEY_WAIT_TIME, DEFAULT_WAIT_TIME_MS)

    @JvmStatic fun setWaitTimeMs(context: Context, v: Long) =
        prefs(context).edit().putLong(KEY_WAIT_TIME, v).apply()

    @JvmStatic fun isAutoAuthEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_AUTH, false)

    @JvmStatic fun setAutoAuthEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_AUTH, v).apply()

    @JvmStatic fun isEnhancedModeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENHANCED_MODE, false)

    @JvmStatic fun setEnhancedModeEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENHANCED_MODE, v).apply()

    @JvmStatic fun isNotifyDetectEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_DETECT, false)

    @JvmStatic fun setNotifyDetectEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_NOTIFY_DETECT, v).apply()

    @JvmStatic fun isRememberPwdEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REMEMBER_PWD, false)

    @JvmStatic fun setRememberPwdEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_REMEMBER_PWD, v).apply()

    @JvmStatic fun clearConfig(context: Context) =
        prefs(context).edit().remove("username").remove("password").remove("isp").apply()

    @JvmStatic fun getSsidList(context: Context): String {
        val persisted = prefs(context).getString(KEY_SSID_LIST, null)
        if (!persisted.isNullOrBlank()) return persisted
        return pluginDefaultSsidList(context)
    }

    @JvmStatic fun getSsidArray(context: Context): Array<String> {
        val list = getSsidList(context)
        if (list.isBlank()) return emptyArray()
        return list.split(",").toTypedArray()
    }

    @JvmStatic fun setSsidList(context: Context, ssidList: String) =
        prefs(context).edit().putString(KEY_SSID_LIST, ssidList).apply()

    @JvmStatic fun resetSsidList(context: Context) =
        prefs(context).edit().remove(KEY_SSID_LIST).apply()

    @JvmStatic fun addSsid(context: Context, ssid: String) {
        if (ssid.isBlank()) return
        val current = getSsidList(context)
        for (s in current.split(",")) {
            if (s.trim().equals(ssid.trim(), ignoreCase = true)) return
        }
        val newList = if (current.isEmpty()) ssid.trim() else "$current,${ssid.trim()}"
        setSsidList(context, newList)
    }

    @JvmStatic fun removeSsid(context: Context, ssid: String) {
        if (ssid.isBlank()) return
        val current = getSsidList(context)
        val sb = StringBuilder()
        for (s in current.split(",")) {
            if (!s.trim().equals(ssid.trim(), ignoreCase = true)) {
                if (sb.isNotEmpty()) sb.append(",")
                sb.append(s.trim())
            }
        }
        setSsidList(context, sb.toString())
    }

    @JvmStatic fun getLoginUrl(context: Context): String {
        val url = prefs(context).getString(KEY_LOGIN_URL, null)
        if (url != null && url.contains("/a79.htm")) {
            resetLoginUrl(context)
            return pluginDefaultLoginUrl(context)
        }
        if (!url.isNullOrBlank()) return url
        return pluginDefaultLoginUrl(context)
    }

    @JvmStatic fun setLoginUrl(context: Context, url: String) =
        prefs(context).edit().putString(KEY_LOGIN_URL, url).apply()

    @JvmStatic fun resetLoginUrl(context: Context) =
        prefs(context).edit().remove(KEY_LOGIN_URL).apply()

    @JvmStatic fun saveConfig(context: Context, username: String, password: String, ispSuffix: String?) =
        prefs(context).edit()
            .putString("username", username)
            .putString("password", password)
            .putString("isp", ispSuffix ?: "")
            .apply()

    @JvmStatic fun loadConfig(context: Context): Array<String>? {
        val p = prefs(context)
        val u = p.getString("username", null) ?: return null
        val pw = p.getString("password", null) ?: return null
        val i = p.getString("isp", null) ?: return null
        return arrayOf(u, pw, i)
    }

    @JvmStatic fun getThemeMode(context: Context): Int =
        prefs(context).getInt(KEY_THEME_MODE, 0)

    @JvmStatic fun setThemeMode(context: Context, mode: Int) =
        prefs(context).edit().putInt(KEY_THEME_MODE, mode).apply()

    @JvmStatic fun getPaletteColor(context: Context, key: String, defArgb: Long): Long =
        prefs(context).getLong(key, defArgb)

    @JvmStatic fun setPaletteColor(context: Context, key: String, argb: Long) =
        prefs(context).edit().putLong(key, argb).apply()

    @JvmStatic fun resetPaletteColor(context: Context, key: String) =
        prefs(context).edit().remove(key).apply()

    @JvmStatic fun resetAllPaletteColors(context: Context) {
        val sp = prefs(context)
        val editor = sp.edit()
        for (key in sp.all.keys) {
            if (key != null && key.startsWith(PALETTE_KEY_PREFIX)) editor.remove(key)
        }
        editor.apply()
    }

    @JvmStatic fun saveAuthState(context: Context, statusOrdinal: Int, timestamp: Long) =
        prefs(context).edit()
            .putInt(KEY_AUTH_STATE, statusOrdinal)
            .putLong(KEY_AUTH_STATE_TIME, timestamp)
            .apply()

    @JvmStatic fun loadAuthState(context: Context): Int =
        prefs(context).getInt(KEY_AUTH_STATE, -1)

    @JvmStatic fun getAuthStateTime(context: Context): Long =
        prefs(context).getLong(KEY_AUTH_STATE_TIME, 0L)

    @JvmStatic fun saveWifiDisconnectTs(context: Context, ts: Long) =
        prefs(context).edit().putLong(KEY_WIFI_DISCONNECT_TS, ts).apply()

    @JvmStatic fun getWifiDisconnectTs(context: Context): Long =
        prefs(context).getLong(KEY_WIFI_DISCONNECT_TS, 0L)

    // 0=WiFi（默认）, 1=移动数据
    @JvmStatic fun getNetChannel(context: Context): Int =
        prefs(context).getInt(KEY_NET_CHANNEL, 0)

    @JvmStatic fun setNetChannel(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_NET_CHANNEL, v).apply()
}
