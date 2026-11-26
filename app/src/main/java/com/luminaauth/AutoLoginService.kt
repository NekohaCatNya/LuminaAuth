package com.luminaauth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.luminaauth.plugin.PluginManager
import java.net.NetworkInterface
import java.util.Collections

class AutoLoginService : Service() {

    companion object {
        private const val CHANNEL_ID = "auto_login_service"
        private const val NOTIFY_ID = 2001
        private const val SLOW_POLL_MIN_MS = 10_000L
        private const val SLOW_POLL_MAX_MS = 60_000L
        private const val SLOW_POLL_MULTIPLIER = 3
        private const val SUCCESS_PROBE_MIN_MS = 120_000L
        private const val SUCCESS_PROBE_MAX_MS = 300_000L
        private const val SUCCESS_PROBE_MULTIPLIER = 12
        private const val WIFI_FLAP_WINDOW_MS = 3_000L
        private const val WIFI_RECONNECT_WINDOW_MS = 45_000L
        private const val REAUTH_MIN_GAP_MS = 30_000L
        private const val STATE_VALID_MS = 15 * 60 * 1000L
        private const val FAIL_THRESHOLD = 3
        private const val DIRTY_THRESHOLD = 5
        private const val MIN_POLL_GAP_MS = 2_000L
        private const val SUCCESS_EVENT_COOLDOWN_MS = 20_000L

        private val TARGET_SSIDS = arrayOf(
            "ChinaNet-JLZG", "ChinaNet-JLZG-5G", "ChinaNet-JLZG-2.4G",
            "JLZG", "JLZG-5G", "JLZG-2.4G"
        )
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_INTERVAL_MS = 5000L
        private const val WIFI_READY_TIMEOUT_MS = 25000
        private const val NETWORK_READY_TIMEOUT_MS = 8000

        private val LOGIN_LOCK = Any()
        @Volatile private var loginInProgress = false
        private var lastLoginAt = 0L
        private var lastSeenTarget = false
        private const val LOGIN_COOLDOWN_MS = 20000

        private var instance: AutoLoginService? = null

        private const val ACTION_LOGIN_RESULT = "com.schoolautologin.LOGIN_RESULT"
        private const val EXTRA_SUCCESS = "success"
        private const val EXTRA_MESSAGE = "message"
        private const val NOTIFY_MIN_GAP_MS = 3000L

        @JvmStatic
        fun restartPolling(context: Context) {
            instance?.refreshDetectMode("settings")
        }

        @JvmStatic
        fun boostPriorityIfEnhanced(context: Context) {
            try {
                if (!PrefUtils.isEnhancedModeEnabled(context)) return
                val pid = android.os.Process.myPid()
                if (RootUtils.isRootAvailable()) {
                    val ok = RootUtils.boostProcessPriority()
                    LogBuffer.add("SVC", if (ok) "boost ok=1 mode=root oom=-17 nice=-20 pid=$pid" else "boost ok=0 mode=root")
                } else if (ShizukuUtils.isReady()) {
                    ShizukuUtils.exec("renice -n -20 -p $pid")
                    LogBuffer.add("SVC", "boost mode=shizuku best_effort=1 pid=$pid")
                }
            } catch (_: Throwable) {}
        }

        @JvmStatic
        fun updateStatus(context: Context, title: String, text: String) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(CHANNEL_ID, "校园网自动认证服务", NotificationManager.IMPORTANCE_LOW)
                    channel.setShowBadge(false)
                    nm.createNotificationChannel(channel)
                }
                val n = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_popup_sync)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .build()
                nm.notify(NOTIFY_ID, n)
            } catch (_: Exception) {}
        }

        private fun maskAccount(account: String?): String {
            if (account.isNullOrEmpty()) return "na"
            if (account.length <= 2) return account[0] + "***"
            return account.substring(0, 2) + "***"
        }

        private fun normalizeSsid(ssid: String?): String {
            if (ssid == null) return ""
            var s = ssid.trim()
            if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length - 1)
            }
            return s
        }

        private fun ssidMatches(target: String, actual: String): Boolean {
            if (target.isEmpty() || actual.isEmpty()) return false
            if (target.equals(actual, ignoreCase = true)) return true
            if (target.length < 4 || actual.length < 4) return false
            val t = target.lowercase()
            val a = actual.lowercase()
            // 只允许“前缀包含”（如 ChinaNet-JLZG 命中 ChinaNet-JLZG-5G），
            // 不做任意子串匹配，避免 NotJLZG 之类误判成校园网。
            return a.startsWith(t) || t.startsWith(a)
        }

        private fun isValidIp(ip: String?): Boolean {
            if (ip.isNullOrEmpty()) return false
            if (ip.startsWith("0.") || ip.startsWith("169.254.")) return false
            val parts = ip.split(".")
            if (parts.size != 4) return false
            for (p in parts) {
                if (p.isEmpty()) return false
                try { p.toInt() } catch (_: NumberFormatException) { return false }
            }
            return true
        }

        fun extractSsidFromNotifyText(text: String?): String? {
            if (text.isNullOrEmpty()) return null
            val idx = text.indexOf("网络")
            if (idx < 0) return null
            var rest = text.substring(idx + 2).trim()
            if (rest.isEmpty()) return null
            // SSID 里的 '.' 是合法字符（ChinaNet-JLZG-2.4G），不能当分隔符
            var token = rest.split(Regex("[\\s，。、,;；]+"))[0].trim()
            while (token.isNotEmpty()) {
                val c = token[token.length - 1]
                if (c in "。，,!！？?；;") {
                    token = token.substring(0, token.length - 1)
                } else break
            }
            return token.ifEmpty { null }
        }
    }

    private var bgThread: HandlerThread? = null
    private var handler: Handler? = null

    private var lastPollInTarget = false
    private var lastWifiOn = false
    private var lastBssid: String? = null
    private var lastPollRunAt = 0L
    private var lastListenerCheckAt = 0L
    private var forceProbeNextRun = false
    private var forceReauthNextRun = false
    private var pendingSsid: String? = null
    private var lastReauthAt = 0L
    @Volatile private var fastAuthRequested = false
    @Volatile private var lastAuthSuccessAt = 0L
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastEvtSig: String? = null
    private var lastEvtAt = 0L
    private var eventRetryCount = 0
    private var lastSsidWarnAt = 0L

    private val notifyKeys = HashSet<String>()
    private var lastNotifyKey: String? = null
    private var lastNotifySignalAt = 0L

    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val s = instance
            val h = s?.handler
            if (s != null && h != null) {
                if (s.isEventDriven()) {
                    h.post { s.eventCheck("broadcast") }
                } else {
                    h.removeCallbacks(s.pollTask)
                    h.post(s.pollTask)
                }
            }
        }
    }

    private val pollTask: Runnable = Runnable {
        // 事件驱动模式不跑轮询；万一被旧路径 post 进来也立刻退出
        if (isEventDriven()) return@Runnable
        var nextInterval = computeSlowPollInterval()
        try {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastPollRunAt < MIN_POLL_GAP_MS && !forceReauthNextRun) {
                handler?.postDelayed(pollTask, 1000)
                return@Runnable
            }
            lastPollRunAt = nowMs

            if (PrefUtils.isEnhancedModeEnabled(this@AutoLoginService)
                && PrefUtils.isNotifyDetectEnabled(this@AutoLoginService)
                && nowMs - lastListenerCheckAt > 30000L) {
                lastListenerCheckAt = nowMs
                if (!GatewayNotificationListener.isListenerConnected()) {
                    LogBuffer.add("NTFY", "guard listener=0 act=rebind")
                    GatewayNotificationListener.requestRebind(this@AutoLoginService)
                }
            }

            if (PrefUtils.isNotifyDetectEnabled(this@AutoLoginService)) {
                if (forceReauthNextRun) {
                    forceReauthNextRun = false
                    var ssid = if (!pendingSsid.isNullOrEmpty()) pendingSsid else getCurrentSsid()
                    pendingSsid = null
                    if (!ssid.isNullOrEmpty() && !"<unknown ssid>".equals(ssid, ignoreCase = true)) {
                        AuthGlobalState.setStatus(AuthStatus.NEED_AUTH)
                        fastAuthRequested = true
                        LogBuffer.add("NTFY", "trigger=auth ssid=$ssid")
                        LogBuffer.addDetail("AUTH", "start src=notify ssid=$ssid")
                        performAutoLogin(ssid)
                    } else {
                        LogBuffer.addDetail("NTFY", "skip reason=no_ssid")
                    }
                }
                handler?.postDelayed(pollTask, computeSlowPollInterval())
                return@Runnable
            }

            if (!PrefUtils.isAutoAuthEnabled(this@AutoLoginService)) {
                handler?.postDelayed(pollTask, nextInterval)
                return@Runnable
            }

            val wifiOn = isWifiConnected()
            var lastDiscTs = PrefUtils.getWifiDisconnectTs(this@AutoLoginService)
            if (!wifiOn) {
                if (lastWifiOn) {
                    lastDiscTs = System.currentTimeMillis()
                    PrefUtils.saveWifiDisconnectTs(this@AutoLoginService, lastDiscTs)
                }
                if (System.currentTimeMillis() - lastDiscTs < WIFI_FLAP_WINDOW_MS) {
                    handler?.postDelayed(pollTask, 1500)
                    return@Runnable
                }
            }
            lastWifiOn = wifiOn
            val wifiReconnected = wifiOn && lastDiscTs > 0
                    && System.currentTimeMillis() - lastDiscTs > WIFI_FLAP_WINDOW_MS
                    && System.currentTimeMillis() - lastDiscTs <= WIFI_RECONNECT_WINDOW_MS

            val ssid = getCurrentSsid()
            val status = AuthGlobalState.currentStatus
            val isTarget = wifiOn && ssid.isNotEmpty()
                    && !"<unknown ssid>".equals(ssid, ignoreCase = true) && isTargetSsid(ssid)
            LogBuffer.addDetail("POLL", "wifi=${if (wifiOn) 1 else 0}"
                    + " ssid=${if (ssid.isEmpty()) "-" else ssid}"
                    + " s=$status target=${if (isTarget) 1 else 0}")

            if (!wifiOn) {
                if (status != AuthStatus.NO_WIFI) {
                    AuthGlobalState.setStatus(AuthStatus.NO_WIFI)
                    LogBuffer.add("MON", "wifi=0 st=wait")
                    LogBuffer.addDetail("STATE", "->NO_WIFI reason=flap_window")
                }
                nextInterval = computeSlowPollInterval()
            } else if (ssid.isEmpty() || "<unknown ssid>".equals(ssid, ignoreCase = true)) {
                LogBuffer.addDetail("POLL", "ssid=na keep s=$status")
                nextInterval = minOf(computeSlowPollInterval(), 3000)
            } else if (!isTargetSsid(ssid)) {
                if (status != AuthStatus.NOT_CAMPUS) {
                    AuthGlobalState.setStatus(AuthStatus.NOT_CAMPUS)
                    LogBuffer.add("MON", "target=0 ssid=$ssid st=wait")
                    LogBuffer.addDetail("STATE", "->NOT_CAMPUS ssid=$ssid")
                }
                nextInterval = computeSlowPollInterval()
            } else {
                nextInterval = PrefUtils.getPollIntervalMs(this@AutoLoginService)
                if ((forceReauthNextRun || wifiReconnected)
                    && System.currentTimeMillis() - lastReauthAt > REAUTH_MIN_GAP_MS) {
                    val reauthBySignal = forceReauthNextRun
                    forceReauthNextRun = false
                    lastReauthAt = System.currentTimeMillis()
                    fastAuthRequested = true
                    AuthGlobalState.setStatus(AuthStatus.NEED_AUTH)
                    LogBuffer.add("MON", "reauth trig=${if (reauthBySignal) "notify" else "reconnect"} ssid=$ssid")
                    LogBuffer.addDetail("STATE", "->NEED_AUTH reason=signal act=auth")
                    performAutoLogin(ssid)
                } else if (forceProbeNextRun) {
                    forceProbeNextRun = false
                    LogBuffer.add("MON", "wakeup act=probe")
                    LogBuffer.addDetail("PROBE", "start src=wakeup")
                    probeSessionAndMaybeReauth(ssid)
                    nextInterval = computeSuccessProbeInterval()
                } else {
                    when (status) {
                        AuthStatus.AUTH_SUCCESS -> {
                            var probed = false
                            if (PrefUtils.isEnhancedModeEnabled(this@AutoLoginService)) {
                                val bssid = getCurrentBssid()
                                LogBuffer.addDetail("PROBE", "bssid now=$bssid last=$lastBssid")
                                if (!bssid.isNullOrEmpty() && bssid != lastBssid) {
                                    lastBssid = bssid
                                    LogBuffer.add("MON", "bssid=changed act=probe")
                                    probeSessionAndMaybeReauth(ssid)
                                    probed = true
                                }
                            }
                            if (!probed) {
                                LogBuffer.addDetail("PROBE", "tick s=AUTH_SUCCESS iv=${computeSuccessProbeInterval() / 1000}")
                                probeSessionAndMaybeReauth(ssid)
                            }
                            nextInterval = computeSuccessProbeInterval()
                        }
                        AuthStatus.NEED_AUTH -> {
                            LogBuffer.addDetail("AUTH", "start s=NEED_AUTH ssid=$ssid")
                            performAutoLogin(ssid)
                        }
                        else -> {
                            AuthGlobalState.setStatus(AuthStatus.NEED_AUTH)
                            LogBuffer.addDetail("STATE", "->NEED_AUTH reason=enter_campus ssid=$ssid")
                            performAutoLogin(ssid)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        handler?.postDelayed(pollTask, nextInterval)
    }

    /**
     * 事件驱动模式下的会话保活：只负责“已经登录成功后”探活，不负责发现网络。
     * 每次执行前都要重新读一遍 SSID 并确认仍在目标列表里。
     */
    private val keepAliveTask: Runnable = Runnable {
        if (!isEventDriven()) return@Runnable
        if (!PrefUtils.isAutoAuthEnabled(this)) return@Runnable
        if (AuthGlobalState.currentStatus != AuthStatus.AUTH_SUCCESS) {
            LogBuffer.addDetail("PROBE", "keepalive skip st=${AuthGlobalState.currentStatus}")
            return@Runnable
        }
        if (loginInProgress) {
            scheduleKeepAlive()
            return@Runnable
        }
        val ssid = readValidSsid()
        if (ssid.isNullOrEmpty() || !isTargetSsid(ssid)) {
            LogBuffer.addDetail("PROBE", "keepalive skip reason=ssid")
            return@Runnable
        }
        LogBuffer.addDetail("PROBE", "keepalive ssid=$ssid")
        try {
            probeSessionAndMaybeReauth(ssid)
        } catch (e: Throwable) {
            LogBuffer.addDetail("PROBE", "keepalive err=${e.message}")
        }
        if (AuthGlobalState.currentStatus == AuthStatus.AUTH_SUCCESS) scheduleKeepAlive()
    }

    private fun computeSlowPollInterval(): Long {
        var slow = PrefUtils.getPollIntervalMs(this) * SLOW_POLL_MULTIPLIER
        if (slow < SLOW_POLL_MIN_MS) slow = SLOW_POLL_MIN_MS
        if (slow > SLOW_POLL_MAX_MS) slow = SLOW_POLL_MAX_MS
        return slow
    }

    private fun computeSuccessProbeInterval(): Long {
        var probe = PrefUtils.getPollIntervalMs(this) * SUCCESS_PROBE_MULTIPLIER
        if (probe < SUCCESS_PROBE_MIN_MS) probe = SUCCESS_PROBE_MIN_MS
        if (probe > SUCCESS_PROBE_MAX_MS) probe = SUCCESS_PROBE_MAX_MS
        return probe
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppContext.attach(this)
        bgThread = HandlerThread("autologin-poll").also { it.start() }
        handler = Handler(bgThread!!.looper)
        // 后台冷启动也要按已保存通道绑定一次，否则不打开界面时不会生效（放后台线程，避免阻塞主线程）
        handler?.post {
            try {
                NetworkChannel.init(this)
                val ch = PrefUtils.getNetChannel(this).coerceIn(0, NetworkChannel.labels.lastIndex)
                val iface = NetworkChannel.applyChannel(ch)
                NativeBridge.nativeSetBindIface(iface)
                LogBuffer.add("NET", "CHANNEL: " + NetworkChannel.labels.getOrElse(ch) { "na" }
                        + (if (iface.isEmpty()) " (failed)" else " iface=$iface"))
            } catch (_: Throwable) {
            }
        }
        LogBuffer.add("SVC", "start")
        startForeground(NOTIFY_ID, buildNotification("校园网自动认证", "后台监听校园网 WiFi，自动完成登录"))
        registerNetworkReceiver()
        boostPriorityIfEnhanced(this)
        try {
            val savedOrd = PrefUtils.loadAuthState(this)
            val savedTs = PrefUtils.getAuthStateTime(this)
            if (savedOrd >= 0 && savedOrd < AuthStatus.values().size
                && savedTs > 0 && System.currentTimeMillis() - savedTs < STATE_VALID_MS) {
                AuthGlobalState.restore(AuthStatus.values()[savedOrd])
                LogBuffer.add("SVC", "state restore=${AuthGlobalState.currentStatus}")
                LogBuffer.addDetail("STATE", "restore s=${AuthGlobalState.currentStatus}"
                        + " age=${(System.currentTimeMillis() - savedTs) / 1000}"
                        + " ttl=${STATE_VALID_MS / 60000}m")
            } else {
                LogBuffer.addDetail("STATE", "restore=none ord=$savedOrd ts=$savedTs boot=cold")
            }
        } catch (e: Exception) {
            LogBuffer.addDetail("STATE", "restore_err=${e.message}")
        }
        if (!isEventDriven()
            && PrefUtils.isAutoAuthEnabled(this)
            && !PrefUtils.isNotifyDetectEnabled(this)) {
            // 必须在投递 pollTask 之前置位，避免后台线程先跑到任务时漏掉启动补认证
            forceReauthNextRun = true
            LogBuffer.addDetail("SVC", "force_reauth=1 reason=start")
        }
        refreshDetectMode("start")
        if (PrefUtils.isNotifyDetectEnabled(this)
            && !GatewayNotificationListener.isListenerConnected()) {
            LogBuffer.add("NTFY", "svc=start listener=0 act=rebind")
            GatewayNotificationListener.requestRebind(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && AutoLoginReceiver.ACTION_TRIGGER_CHECK == intent.action
            && isEventDriven()) {
            val notifyKey = intent.getStringExtra(GatewayNotificationListener.EXTRA_NOTIFY_KEY)
            if (notifyKey.isNullOrEmpty()) {
                LogBuffer.addDetail("EVT", "trigger=intent act=check")
                handler?.post { eventCheck("intent") }
            } else {
                LogBuffer.addDetail("EVT", "trigger=notify ignored mode=event key=$notifyKey")
            }
            return START_STICKY
        }
        if (intent != null && AutoLoginReceiver.ACTION_TRIGGER_CHECK == intent.action) {
            val notifyKey = intent.getStringExtra(GatewayNotificationListener.EXTRA_NOTIFY_KEY)
            if (!notifyKey.isNullOrEmpty()) {
                val now = System.currentTimeMillis()
                if (notifyKey == lastNotifyKey && now - lastNotifySignalAt < NOTIFY_MIN_GAP_MS) {
                    LogBuffer.addDetail("NTFY", "dup=1 key=$notifyKey act=drop")
                    return START_STICKY
                }
                lastNotifyKey = notifyKey
                lastNotifySignalAt = now
                val notifyText = intent.getStringExtra(GatewayNotificationListener.EXTRA_NOTIFY_TEXT)
                LogBuffer.add("MON", "signal=notify act=check")
                LogBuffer.addDetail("NTFY", "signal key=$notifyKey text=$notifyText")
                if (PrefUtils.isNotifyDetectEnabled(this)) {
                    val ssidFromNotify = extractSsidFromNotifyText(notifyText)
                    if (ssidFromNotify.isNullOrEmpty()) {
                        LogBuffer.addDetail("NTFY", "ssid=parse_fail act=skip_auth")
                        return START_STICKY
                    }
                    if (!isTargetSsid(ssidFromNotify)) {
                        LogBuffer.add("NTFY", "ssid=$ssidFromNotify target=0 act=skip keep=1")
                        return START_STICKY
                    }
                    LogBuffer.add("NTFY", "ssid=$ssidFromNotify target=1 act=auth")
                    notifyKeys.add(notifyKey)
                    pendingSsid = ssidFromNotify
                    forceReauthNextRun = true
                } else {
                    notifyKeys.add(notifyKey)
                    forceReauthNextRun = true
                }
            } else {
                forceProbeNextRun = true
            }
            handler?.let { h ->
                h.removeCallbacks(pollTask)
                h.post(pollTask)
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            if (PrefUtils.isAutoAuthEnabled(this)) {
                val restart = Intent(this, AutoLoginService::class.java)
                restart.setPackage(packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restart)
                } else {
                    startService(restart)
                }
                LogBuffer.add("SVC", "task_removed act=restart")
            }
        } catch (e: Exception) {
            LogBuffer.add("SVC", "task_removed restart_err=${e.message}")
        }
    }

    override fun onDestroy() {
        instance = null
        LogBuffer.add("SVC", "stop")
        try { unregisterReceiver(networkReceiver) } catch (_: Exception) {}
        unregisterNetworkCallback()
        try { NetworkChannel.unbindProcess() } catch (_: Throwable) {}
        handler?.removeCallbacks(pollTask)
        handler?.removeCallbacks(keepAliveTask)
        bgThread?.quitSafely()
        bgThread = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== 事件驱动检测（普通模式） ====================

    /** 普通模式 + 检测方式=事件驱动；增强模式仍走原有轮询逻辑。 */
    private fun isEventDriven(): Boolean =
        PrefUtils.getDetectMode(this) == PrefUtils.MODE_EVENT &&
            !PrefUtils.isEnhancedModeEnabled(this)

    /** 设置页切换检测方式 / 增强模式后重新装配触发源。 */
    private fun refreshDetectMode(reason: String) {
        val h = handler ?: return
        h.removeCallbacks(pollTask)
        h.removeCallbacks(keepAliveTask)
        eventRetryCount = 0
        if (isEventDriven()) {
            LogBuffer.addDetail("EVT", "mode=event src=$reason poll=off")
            installNetworkCallback()
            h.postDelayed({ eventCheck("boot:$reason") }, 500)
        } else {
            LogBuffer.addDetail(
                "EVT",
                "mode=poll src=$reason notify=${PrefUtils.isNotifyDetectEnabled(this)}"
            )
            unregisterNetworkCallback()
            h.post(pollTask)
        }
    }

    private fun installNetworkCallback() {
        val h = handler ?: return
        val cm = try {
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (_: Throwable) {
            null
        }
        if (cm == null) {
            LogBuffer.addDetail("EVT", "callback=register ok=0 err=no_cm")
            return
        }
        unregisterNetworkCallback()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // 蜂窝等非 WiFi 默认网络的能力变化不参与认证判断
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
                h.post { eventCheck("cap") }
            }

            override fun onLost(network: Network) {
                h.post { onNetworkLost() }
            }
        }
        try {
            // 只订阅 WiFi 传输类型：即使系统默认网络仍是蜂窝（WLAN 助理/智能切换），
            // 也能在连上校园 WiFi 时收到事件；真实身份依旧以重新读取的 SSID 为准。
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(request, callback, h)
            networkCallback = callback
            LogBuffer.addDetail("EVT", "callback=register wifi_only ok=1")
        } catch (e: Throwable) {
            LogBuffer.addDetail("EVT", "callback=register ok=0 err=${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.unregisterNetworkCallback(callback)
            LogBuffer.addDetail("EVT", "callback=unregister")
        } catch (_: Throwable) {
        }
    }

    /**
     * 唯一的事件入口：网络事件只代表“可能变了”，真实身份必须重新读 SSID 再比对列表。
     * 依次过滤：自动认证开关、插件有效性、认证进行中、成功冷却、WiFi 状态、SSID 可读性、目标列表。
     */
    private fun eventCheck(reason: String) {
        if (!isEventDriven()) return
        if (!PrefUtils.isAutoAuthEnabled(this)) {
            eventRetryCount = 0
            evtLog("auto_off", "skip reason=auto_off src=$reason")
            return
        }
        val plugin = try {
            PluginManager.active(this)
        } catch (_: Throwable) {
            null
        }
        if (plugin == null || plugin.id.isEmpty()) {
            eventRetryCount = 0
            evtLog("plugin", "skip reason=plugin_invalid src=$reason")
            return
        }
        if (loginInProgress) {
            evtLog("busy", "skip reason=busy src=$reason")
            return
        }
        val now = System.currentTimeMillis()
        val status = AuthGlobalState.currentStatus
        if (!isWifiConnected()) {
            handler?.removeCallbacks(keepAliveTask)
            if (status != AuthStatus.NO_WIFI) {
                AuthGlobalState.setStatus(AuthStatus.NO_WIFI)
                LogBuffer.add("MON", "wifi=0 st=wait src=evt")
                LogBuffer.addDetail("STATE", "->NO_WIFI reason=event")
            }
            eventRetryCount = 0
            evtLog("no_wifi", "skip reason=no_wifi src=$reason")
            return
        }
        // 事件之后重新读 SSID：不信任回调里携带的网络信息，也不信任缓存。
        // 先读再判断状态，确保从校园网切到别的 WiFi 时不会残留 AUTH_SUCCESS。
        val ssid = readValidSsid()
        if (ssid.isNullOrEmpty()) {
            // 事件可能早于 supplicant 完成：只做有界短重试（最多 3 次），不是周期轮巡
            if (eventRetryCount < 3) {
                eventRetryCount++
                evtLog("ssid_na", "skip reason=ssid_unreadable src=$reason retry=$eventRetryCount")
                handler?.postDelayed({ eventCheck("retry$eventRetryCount") }, 1500)
            } else {
                eventRetryCount = 0
                evtLog("ssid_na", "skip reason=ssid_unreadable src=$reason retry=give_up")
                warnSsidUnreadable()
            }
            return
        }
        val targets = try {
            PrefUtils.getSsidArray(this).map { it.trim() }.filter { it.isNotEmpty() }
        } catch (_: Throwable) {
            emptyList()
        }
        if (targets.isEmpty()) {
            eventRetryCount = 0
            evtLog("no_targets", "skip reason=no_target_list src=$reason")
            return
        }
        if (!isTargetSsid(ssid)) {
            if (status != AuthStatus.NOT_CAMPUS) {
                AuthGlobalState.setStatus(AuthStatus.NOT_CAMPUS)
                LogBuffer.add("MON", "target=0 ssid=$ssid st=wait src=evt")
                LogBuffer.addDetail("STATE", "->NOT_CAMPUS ssid=$ssid")
            }
            eventRetryCount = 0
            evtLog("other_ssid", "skip reason=not_target ssid=$ssid src=$reason")
            return
        }
        if (status == AuthStatus.AUTH_SUCCESS && lastAuthSuccessAt > 0L) {
            // 本进程内刚认证成功：系统 VALIDATED 往往延迟 5~15 秒，这段时间丢弃重复事件
            if (now - lastAuthSuccessAt in 1L until SUCCESS_EVENT_COOLDOWN_MS) {
                eventRetryCount = 0
                evtLog("cooldown", "skip reason=cooldown src=$reason")
                return
            }
            eventRetryCount = 0
            evtLog("online", "skip reason=already_online src=$reason")
            scheduleKeepAlive()
            return
        }
        // 从磁盘恢复的 AUTH_SUCCESS 不算数：继续往下按 VALIDATED 重新核验
        val caps = currentWifiCapabilities()
        if (caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            AuthGlobalState.setStatus(AuthStatus.AUTH_SUCCESS)
            lastAuthSuccessAt = now
            LogBuffer.add("MON", "evt=validated act=online ssid=$ssid")
            LogBuffer.addDetail("EVT", "validated=1 ssid=$ssid act=skip_auth src=$reason")
            eventRetryCount = 0
            scheduleKeepAlive()
            return
        }
        eventRetryCount = 0
        LogBuffer.add("MON", "evt=need_auth ssid=$ssid")
        LogBuffer.addDetail("EVT", "ssid=$ssid target=1 validated=0 act=auth src=$reason")
        performAutoLogin(ssid)
    }

    /**
     * 事件确认用的 SSID 读取：先确认 wlan 网卡真的连着，再要求 supplicant COMPLETED。
     * 避免 WiFi 已断开的几秒内 connectionInfo 仍返回上一次 SSID 的缓存误触发。
     */
    private fun readValidSsid(): String? {
        if (!isWifiConnected()) return null
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            val info = wifi.connectionInfo ?: return null
            val state = info.supplicantState
            val ssid = normalizeSsid(info.ssid)
            if (ssid.isEmpty() || ssid.equals("<unknown ssid>", ignoreCase = true)) {
                LogBuffer.addDetail("EVT", "ssid_read state=$state ssid=unknown")
                return null
            }
            // 屏幕熄灭后部分 ROM 会把 supplicant 置为 DORMANT（省电态），此时 WiFi 仍连着、
            // SSID 也有效。旧逻辑硬要求 COMPLETED，会导致后台整段时间全部 ssid_unreadable。
            if (state != SupplicantState.COMPLETED && state != SupplicantState.DORMANT) {
                LogBuffer.addDetail("EVT", "ssid_read state=$state act=skip")
                return null
            }
            ssid
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 当前所有 WiFi 网络里优先找已 VALIDATED 的那个。
     * 不用 activeNetwork：WLAN 助理把默认网络留在蜂窝时，activeNetwork 不是 WiFi。
     */
    private fun currentWifiCapabilities(): NetworkCapabilities? {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            var fallback: NetworkCapabilities? = null
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) {
                    return caps
                }
                if (fallback == null) fallback = caps
            }
            fallback
        } catch (_: Throwable) {
            null
        }
    }

    private fun onNetworkLost() {
        if (!isEventDriven()) return
        if (isWifiConnected()) return
        handler?.removeCallbacks(keepAliveTask)
        synchronized(LOGIN_LOCK) { lastSeenTarget = false }
        if (AuthGlobalState.currentStatus != AuthStatus.NO_WIFI) {
            AuthGlobalState.setStatus(AuthStatus.NO_WIFI)
            LogBuffer.add("MON", "wifi=0 st=wait src=evt_lost")
            LogBuffer.addDetail("STATE", "->NO_WIFI reason=event_lost")
        }
        LogBuffer.addDetail("EVT", "network=lost act=reset")
    }

    private fun scheduleKeepAlive() {
        val h = handler ?: return
        if (!isEventDriven()) {
            h.removeCallbacks(keepAliveTask)
            return
        }
        // 已经排好就不再重置，避免网络事件密集时把探活一直往后推
        if (h.hasCallbacks(keepAliveTask)) return
        val interval = computeSuccessProbeInterval()
        LogBuffer.addDetail("PROBE", "keepalive arm iv=${interval / 1000}s")
        h.postDelayed(keepAliveTask, interval)
    }

    /** 同类事件 5 秒内只记一条，避免连接过程中回调密集刷屏。 */
    private fun evtLog(sig: String, msg: String) {
        val now = System.currentTimeMillis()
        if (sig == lastEvtSig && now - lastEvtAt < 5000L) return
        lastEvtSig = sig
        lastEvtAt = now
        LogBuffer.addDetail("EVT", msg)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "校园网自动认证服务", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun registerNetworkReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION)
            addAction("android.net.conn.CONNECTIVITY_CHANGE")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(networkReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(networkReceiver, filter)
            }
        } catch (_: Exception) {}
    }

    private fun probeSessionAndMaybeReauth(ssid: String) {
        val ip = waitForValidIp(NETWORK_READY_TIMEOUT_MS.toLong())
        if (ip == null) {
            LogBuffer.addDetail("PROBE", "ip=timeout dirty=${AuthGlobalState.sessionDirtyCount + 1} act=dirty")
            AuthGlobalState.addDirty()
            maybeReauthFromProbe(ssid)
            return
        }
        try {
            val s1 = LoginService.checkNetworkStatus(ip)
            LogBuffer.addDetail("PROBE", "ip=$ip chk=$s1"
                    + " fail=${AuthGlobalState.sessionFailCount}"
                    + " dirty=${AuthGlobalState.sessionDirtyCount}")
            if ("online" == s1) {
                AuthGlobalState.resetCounters()
                LogBuffer.addDetail("PROBE", "chk=online counters=reset")
                return
            }
            if ("offline" == s1) {
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
                val s2 = try { LoginService.checkNetworkStatus(ip) } catch (_: Exception) { null }
                LogBuffer.addDetail("PROBE", "chk=offline recheck=$s2")
                if ("online" == s2) {
                    AuthGlobalState.resetCounters()
                    LogBuffer.addDetail("PROBE", "recheck=online transient=1 counters=reset")
                    return
                }
                if ("offline" == s2) AuthGlobalState.addFail() else AuthGlobalState.addDirty()
            } else {
                AuthGlobalState.addDirty()
            }
        } catch (e: Exception) {
            LogBuffer.addDetail("PROBE", "chk_err=${e.message} act=dirty")
            AuthGlobalState.addDirty()
        }
        maybeReauthFromProbe(ssid)
    }

    private fun maybeReauthFromProbe(ssid: String) {
        LogBuffer.addDetail("PROBE", "counters fail=${AuthGlobalState.sessionFailCount}"
                + " dirty=${AuthGlobalState.sessionDirtyCount}"
                + " thr_fail=$FAIL_THRESHOLD thr_dirty=$DIRTY_THRESHOLD")
        if (AuthGlobalState.sessionFailCount >= FAIL_THRESHOLD
            || AuthGlobalState.sessionDirtyCount >= DIRTY_THRESHOLD) {
            LogBuffer.add("AUTH", "session=stale act=reauth")
            LogBuffer.addDetail("STATE", "->NEED_AUTH reason=probe_threshold")
            AuthGlobalState.setStatus(AuthStatus.NEED_AUTH)
            performAutoLogin(ssid)
        }
    }

    private fun warnSsidUnreadable() {
        val now = System.currentTimeMillis()
        if (now - lastSsidWarnAt < 60_000L) return
        lastSsidWarnAt = now
        updateStatus(
            this,
            "校园网自动认证",
            "已连接 WiFi，但暂时读不到网络名称；若持续出现，请把定位权限设为始终允许并开启定位"
        )
        LogBuffer.add("SVC", "ssid_unreadable notify=1")
    }

    private fun performAutoLogin(ssid: String) {
        if (!PrefUtils.isAutoAuthEnabled(this)) return
        val cfg = PrefUtils.loadConfig(this) ?: run {
            LogBuffer.addDetail("AUTH", "skip reason=no_credentials")
            return
        }

        if (!isTargetSsid(ssid)) {
            synchronized(LOGIN_LOCK) {
                val justLeft = lastSeenTarget
                lastSeenTarget = false
                if (justLeft) LogBuffer.add("MON", "campus=leave ssid=${if (ssid.isEmpty()) "na" else ssid}")
            }
            if (isWifiConnected() && ssid.isEmpty()) {
                warnSsidUnreadable()
            }
            LogBuffer.addDetail("AUTH", "skip ssid=$ssid target=0")
            return
        }

        synchronized(LOGIN_LOCK) {
            if (loginInProgress) {
                LogBuffer.addDetail("AUTH", "skip reason=busy")
                return
            }
            val now = System.currentTimeMillis()
            val justConnected = !lastSeenTarget
            lastSeenTarget = true
            if (!justConnected && now - lastLoginAt < LOGIN_COOLDOWN_MS) {
                LogBuffer.addDetail("AUTH", "skip reason=cooldown dt=${(now - lastLoginAt) / 1000} cd=${LOGIN_COOLDOWN_MS / 1000}")
                return
            }
            loginInProgress = true
            lastLoginAt = now
        }

        val plugin = try { PluginManager.active(this) } catch (_: Throwable) { null }
        val timing = plugin?.timing ?: com.luminaauth.plugin.TimingDef()
        val detect = plugin?.detect ?: com.luminaauth.plugin.DetectDef()
        val policy = plugin?.policy ?: com.luminaauth.plugin.NetworkPolicyDef()
        try { NetworkChannel.preferredIfaces = policy.preferredIfaces } catch (_: Throwable) {}

        val username = cfg[0]
        val password = cfg[1]
        val isp = "after"

        LogBuffer.add("MON", "campus=enter ssid=$ssid act=auth")
        val notifyMode = PrefUtils.isNotifyDetectEnabled(this)
        val skipReady = notifyMode && timing.skipNetworkReadyOnNotify
        LogBuffer.addDetail("AUTH", "begin ssid=$ssid isp=$isp user=${maskAccount(username)} notify=$notifyMode")
        updateStatus(this, "校园网自动认证",
            if (notifyMode) "检测到 $ssid，正在认证..." else "检测到 $ssid，正在等待网络就绪...")

        Thread {
            var success = false
            var message = "未知错误"
            try {
                if (!skipReady && !waitForWifiReady(WIFI_READY_TIMEOUT_MS.toLong(), timing.ipPollMs)) {
                    message = "WiFi 网络尚未就绪，稍后自动重试"
                    LogBuffer.add("AUTH", "wifi_ready=timeout retry=auto")
                    LogBuffer.addDetail("AUTH", "abort reason=wifi_ready_timeout")
                    updateStatus(this, "校园网自动认证", "网络就绪超时，稍后自动重试")
                    notifyResult(false, message)
                    return@Thread
                }
                val skipStableBuffer = fastAuthRequested || skipReady
                fastAuthRequested = false
                if (!skipStableBuffer) {
                    // 原来是无脑 sleep，现在是“IP 一就绪就走”，最多等配置的时长
                    waitUntilIpReady(PrefUtils.getWaitTimeMs(this@AutoLoginService), timing.ipPollMs)
                }

                val attempts = detect.retryCount.coerceIn(1, 10)
                val retryDelay = detect.retryDelayMs.coerceIn(200L, 60000L)
                for (attempt in 1..attempts) {
                    if (attempt > 1) {
                        try { Thread.sleep(retryDelay) } catch (_: InterruptedException) { break }
                    }
                    updateStatus(this, "校园网自动认证", "正在认证（第 $attempt/$attempts 次）...")

                    // 认证请求必须从 WiFi 网卡出站：校园内网 Portal 走蜂窝必然超时。
                    // 插件 network.auth_over_wifi=true 且 WiFi 已连接时，这里临时绑 wlan0，
                    // 认证完成后再恢复用户选择的发送通道。
                    try {
                        val selectedCh = PrefUtils.getNetChannel(this@AutoLoginService)
                            .coerceIn(0, NetworkChannel.labels.lastIndex)
                        val authCh = if (policy.authOverWifi && isWifiConnected()) NetworkChannel.WIFI else selectedCh
                        val ifaceNow = NetworkChannel.applyChannel(authCh)
                        LogBuffer.addDetail("AUTH", "attempt=$attempt ch=${NetworkChannel.labels[authCh]}"
                                + (if (authCh != selectedCh) " (auth_over_wifi, selected=${NetworkChannel.labels[selectedCh]})" else "")
                                + " iface=${ifaceNow.ifEmpty { "na" }}")
                    } catch (_: Throwable) {
                    }

                    val ip = waitForValidIp(
                        minOf(NETWORK_READY_TIMEOUT_MS.toLong(), timing.maxIpWaitMs.coerceAtLeast(500L)),
                        timing.ipPollMs
                    )
                    if (ip == null) {
                        message = "IP 未分配，等待重试"
                        LogBuffer.addDetail("AUTH", "attempt=$attempt ip=na")
                        continue
                    }
                    try {
                        val status = LoginService.checkNetworkStatus(ip)
                        LogBuffer.addDetail("AUTH", "attempt=$attempt ip=$ip chk=$status")
                        if ("online" == status) {
                            success = true
                            message = "已在线，无需操作"
                            LogBuffer.addDetail("AUTH", "already_online submit=0")
                            break
                        } else if ("offline" == status) {
                            val macNow = getCurrentMac()
                            LogBuffer.addDetail("AUTH", "attempt=$attempt mac=${if (macNow == "000000000000") "fallback" else "ok"}")
                            val r = LoginService.doLogin(username, password, isp, ip, macNow)
                            success = r.success
                            message = r.message
                            LogBuffer.add("AUTH", "attempt=$attempt login=${if (r.success) "ok" else "fail"}")
                            LogBuffer.addDetail("AUTH", "attempt=$attempt login=${if (r.success) "ok" else "fail"}")
                            if (success) break
                        } else {
                            message = "认证服务器暂不可达，等待重试"
                        }
                    } catch (e: Throwable) {
                        message = "异常: ${e.message}"
                        LogBuffer.add("AUTH", "attempt=$attempt err=${e.message}")
                        LogBuffer.addDetail("AUTH", "attempt=$attempt ex=$e")
                        android.util.Log.getStackTraceString(e).lines().forEach {
                            LogBuffer.addDetail("AUTH", "  | $it")
                        }
                    }
                }

                AuthGlobalState.setStatus(if (success) AuthStatus.AUTH_SUCCESS else AuthStatus.NEED_AUTH)
                LogBuffer.addDetail("STATE", "auth_done ok=${if (success) 1 else 0} ->${AuthGlobalState.currentStatus}")
                if (success || "已在线，无需操作" == message) clearCampusNotifications()
                if (success) {
                    lastAuthSuccessAt = System.currentTimeMillis()
                    handler?.post { scheduleKeepAlive() }
                    // 认证是借 wlan0 做的，成功后把通道恢复成用户选择的那条
                    try {
                        val selectedCh = PrefUtils.getNetChannel(this@AutoLoginService)
                            .coerceIn(0, NetworkChannel.labels.lastIndex)
                        val iface = NetworkChannel.applyChannel(selectedCh)
                        NativeBridge.nativeSetBindIface(iface)
                        LogBuffer.add("NET", "CHANNEL restore: ${NetworkChannel.labels[selectedCh]}"
                                + (if (iface.isEmpty()) " (failed)" else " iface=$iface"))
                    } catch (_: Throwable) {
                    }
                    if (policy.bindProcess) {
                        val ok = NetworkChannel.bindProcessTo(NetworkChannel.boundNetwork)
                        LogBuffer.add("NET", "bind_process ok=${if (ok) 1 else 0}")
                    }
                    // 本来就是已在线时不重连，避免把现有连接打断
                    if ("已在线，无需操作" != message) promoteSelectedChannel()
                    handler?.postDelayed({
                        try { updateStatus(this, "校园网自动认证", "后台监听校园网 WiFi，自动完成登录") } catch (_: Throwable) {}
                    }, 2000)
                }
                if ("已在线，无需操作" != message) {
                    updateStatus(this,
                        if (success) "校园网自动认证成功" else "校园网自动认证失败", message)
                }
                notifyResult(success, message)
            } finally {
                synchronized(LOGIN_LOCK) { loginInProgress = false }
            }
        }.start()
    }

    /**
     * 增强模式 + root：认证成功后催促系统重新评估网络，让默认网络尽快切到所选通道，
     * 随后重新绑定一次（重连后 Network 对象会变）。
     */
    private fun promoteSelectedChannel() {
        try {
            val ch = PrefUtils.getNetChannel(this).coerceIn(0, NetworkChannel.labels.lastIndex)
            val label = NetworkChannel.labels.getOrElse(ch) { "na" }
            val plugin = try { PluginManager.active(this) } catch (_: Throwable) { null }
            val policy = plugin?.policy ?: com.luminaauth.plugin.NetworkPolicyDef()
            val r = NetworkChannel.promoteOnce(ch, policy.promoteNetwork, policy.rootSetDefault)
            LogBuffer.add("NET", "promote ch=$label result=$r")
            if (r.startsWith("skip") || r.startsWith("denied")) return
            handler?.postDelayed({
                try {
                    val iface = NetworkChannel.applyChannel(ch)
                    NativeBridge.nativeSetBindIface(iface)
                    LogBuffer.add("NET", "CHANNEL rebind: $label"
                            + (if (iface.isEmpty()) " (failed)" else " iface=$iface"))
                } catch (_: Throwable) {
                }
            }, 3000)
        } catch (_: Throwable) {
        }
    }

    private fun clearCampusNotifications() {
        synchronized(notifyKeys) {
            if (notifyKeys.isNotEmpty()) {
                var n = 0
                for (k in notifyKeys) {
                    GatewayNotificationListener.cancelNotificationByKey(k)
                    n++
                }
                notifyKeys.clear()
                LogBuffer.add("NTFY", "auth=ok cleared=$n")
            }
        }
        GatewayNotificationListener.cancelCampusNotificationsByScan(this)
    }

    private fun getCurrentSsid(): String {
        // WiFi 没连上就不要读 SSID：root/shizuku 的 dumpsys 可能返回保存过的网络名
        if (!isWifiConnected()) return ""
        if (PrefUtils.isEnhancedModeEnabled(this)) {
            try {
                if (RootUtils.isRootAvailable()) {
                    RootUtils.getWifiSsidViaRoot()?.let { if (it.isNotEmpty()) return normalizeSsid(it) }
                }
            } catch (_: Exception) {}
            try {
                if (ShizukuUtils.isReady()) {
                    ShizukuUtils.getWifiSsidViaShizuku(this)?.let { if (it.isNotEmpty()) return normalizeSsid(it) }
                }
            } catch (_: Exception) {}
        }
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.connectionInfo?.let {
                val s = normalizeSsid(it.ssid)
                if (s.isNotEmpty() && !s.equals("<unknown ssid>", ignoreCase = true)) return s
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun getCurrentBssid(): String? {
        val p = Regex("BSSID:\\s*([0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5})")
        if (PrefUtils.isEnhancedModeEnabled(this)) {
            try {
                if (RootUtils.isRootAvailable()) {
                    RootUtils.exec("dumpsys wifi")?.let { out ->
                        p.find(out)?.let { return it.groupValues[1].uppercase() }
                    }
                }
            } catch (_: Exception) {}
            try {
                if (ShizukuUtils.isReady()) {
                    ShizukuUtils.exec("dumpsys wifi")?.let { out ->
                        p.find(out)?.let { return it.groupValues[1].uppercase() }
                    }
                }
            } catch (_: Exception) {}
        }
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.connectionInfo?.let { b ->
                b.bssid?.let { if (it.isNotEmpty()) return it.uppercase() }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * 判断是否连着 WiFi：优先用 ConnectivityManager 的 WiFi Network（最可靠），
     * 其次看 supplicant 状态，最后才退回枚举网卡。
     * 只用 NetworkInterface.isUp 在部分 ROM 上会误报 false（日志里 SSID 能读到但 wifi=0）。
     */
    private fun isWifiConnected(): Boolean {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
                    // 只要有 WiFi 传输类型的活动 Network 就算连着；
                    // 网卡名可能为空（部分 ROM 在权限/时序上拿不到），不能因此判成没 WiFi
                    return true
                }
            }
        } catch (_: Throwable) {
        }
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifi?.connectionInfo
            if (info != null && info.supplicantState == SupplicantState.COMPLETED) {
                val ssid = normalizeSsid(info.ssid)
                if (ssid.isNotEmpty() && !ssid.equals("<unknown ssid>", ignoreCase = true)) return true
            }
        } catch (_: Throwable) {
        }
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                val n = (ni.name ?: "").lowercase()
                if (n.startsWith("wlan")) return true
            }
        } catch (_: Exception) {
        }
        return false
    }


    /**
     * 当前 wlan0 的 MAC：优先 root/shizuku 读 /sys/class/net/wlan0/address（随机化后的真实值），
     * 其次 WifiManager（Android 10+ 常返回 02:00:00:00:00:00），都拿不到就用全 0 兜底。
     */
    private fun getCurrentMac(): String {
        fun normalize(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val hex = raw.lowercase().replace(":", "").replace("-", "").trim()
            if (hex.length != 12 || hex.all { it == '0' }) return null
            if (!hex.all { it in "0123456789abcdef" }) return null
            return hex
        }
        try {
            if (PrefUtils.isEnhancedModeEnabled(this)) {
                if (RootUtils.isRootAvailable()) {
                    val out = RootUtils.exec("cat /sys/class/net/wlan0/address")
                    normalize(out?.lineSequence()?.firstOrNull())?.let { return it }
                }
                if (ShizukuUtils.isReady()) {
                    val out = ShizukuUtils.exec("cat /sys/class/net/wlan0/address")
                    normalize(out?.lineSequence()?.firstOrNull())?.let { return it }
                }
            }
        } catch (_: Throwable) {
        }
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            normalize(wifi?.connectionInfo?.macAddress)?.let { return it }
        } catch (_: Throwable) {
        }
        return "000000000000"
    }

    private fun waitForWifiReady(timeoutMs: Long, stepMs: Long = 250L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ssid = getCurrentSsid()
            if (isTargetSsid(ssid)) {
                val ip = LoginService.getLocalIPv4(this)
                if (isValidIp(ip)) return true
            }
            try { Thread.sleep(stepMs.coerceIn(50L, 1000L)) } catch (_: InterruptedException) { break }
        }
        return false
    }

    /** 稳定性等待：IP 有效就立刻返回，不再死等满配置时长。 */
    private fun waitUntilIpReady(maxMs: Long, stepMs: Long = 250L) {
        if (maxMs <= 0L) return
        val deadline = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < deadline) {
            if (isValidIp(LoginService.getLocalIPv4(this))) return
            try { Thread.sleep(stepMs.coerceIn(50L, 1000L)) } catch (_: InterruptedException) { return }
        }
    }

    private fun waitForValidIp(timeoutMs: Long, stepMs: Long = 250L): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ip = LoginService.getLocalIPv4(this)
            if (isValidIp(ip)) return ip
            try { Thread.sleep(stepMs.coerceIn(50L, 1000L)) } catch (_: InterruptedException) { break }
        }
        val ip = LoginService.getLocalIPv4(this)
        return if (isValidIp(ip)) ip else null
    }

    private fun notifyResult(success: Boolean, message: String) {
        if (MainActivity.foreground) {
            val i = Intent(ACTION_LOGIN_RESULT)
            i.setPackage(packageName)
            i.putExtra(EXTRA_SUCCESS, success)
            i.putExtra(EXTRA_MESSAGE, message)
            sendBroadcast(i)
        }
    }

    private fun isTargetSsid(ssid: String?): Boolean {
        if (ssid.isNullOrEmpty()) return false
        try {
            val targets = PrefUtils.getSsidArray(this)
            for (t in targets) {
                if (ssidMatches(t.trim(), ssid)) return true
            }
        } catch (_: Exception) {
            for (t in TARGET_SSIDS) {
                if (ssidMatches(t, ssid)) return true
            }
        }
        return false
    }
}
