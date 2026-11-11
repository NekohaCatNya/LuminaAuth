package com.luminaauth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
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

        private val TARGET_SSIDS = arrayOf(
            "ChinaNet-JLZG", "ChinaNet-JLZG-5G", "ChinaNet-JLZG-2.4G",
            "JLZG", "JLZG-5G", "JLZG-2.4G"
        )
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_INTERVAL_MS = 5000L
        private const val WIFI_READY_TIMEOUT_MS = 25000
        private const val NETWORK_READY_TIMEOUT_MS = 8000

        private val LOGIN_LOCK = Any()
        private var loginInProgress = false
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
            val s = instance ?: return
            s.handler?.let { h ->
                h.removeCallbacks(s.pollTask)
                h.post(s.pollTask)
            }
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
            return a.contains(t) || t.contains(a)
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
            var token = rest.split(Regex("[\\s，。、,.;:：]+"))[0].trim()
            while (token.isNotEmpty()) {
                val c = token[token.length - 1]
                if (c in "。，,.!！？?：;；") {
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

    private val notifyKeys = HashSet<String>()
    private var lastNotifyKey: String? = null
    private var lastNotifySignalAt = 0L

    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val s = instance
            val h = s?.handler
            if (s != null && h != null) {
                h.removeCallbacks(s.pollTask)
                h.post(s.pollTask)
            }
        }
    }

    private val pollTask: Runnable = Runnable {
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
        handler?.post(pollTask)
        if (PrefUtils.isAutoAuthEnabled(this) && !PrefUtils.isNotifyDetectEnabled(this)) {
            forceReauthNextRun = true
            LogBuffer.addDetail("SVC", "force_reauth=1 reason=start")
        }
        if (PrefUtils.isNotifyDetectEnabled(this)
            && !GatewayNotificationListener.isListenerConnected()) {
            LogBuffer.add("NTFY", "svc=start listener=0 act=rebind")
            GatewayNotificationListener.requestRebind(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        handler?.removeCallbacks(pollTask)
        bgThread?.quitSafely()
        bgThread = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
                updateStatus(this, "校园网自动认证", "已连接 WiFi，但无法读取网络名称，请在系统设置中开启定位服务")
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

        val username = cfg[0]
        val password = cfg[1]
        val isp = "after"

        LogBuffer.add("MON", "campus=enter ssid=$ssid act=auth")
        LogBuffer.addDetail("AUTH", "begin ssid=$ssid isp=$isp user=${maskAccount(username)}")
        updateStatus(this, "校园网自动认证", "检测到 $ssid，正在等待网络就绪...")

        Thread {
            var success = false
            var message = "未知错误"
            try {
                if (!waitForWifiReady(WIFI_READY_TIMEOUT_MS.toLong())) {
                    message = "WiFi 网络尚未就绪，稍后自动重试"
                    LogBuffer.add("AUTH", "wifi_ready=timeout retry=auto")
                    LogBuffer.addDetail("AUTH", "abort reason=wifi_ready_timeout")
                    updateStatus(this, "校园网自动认证", "网络就绪超时，稍后自动重试")
                    notifyResult(false, message)
                    return@Thread
                }
                val skipStableBuffer = fastAuthRequested
                fastAuthRequested = false
                if (!skipStableBuffer) {
                    try { Thread.sleep(PrefUtils.getWaitTimeMs(this@AutoLoginService)) } catch (_: InterruptedException) { return@Thread }
                }

                for (attempt in 1..MAX_ATTEMPTS) {
                    if (attempt > 1) {
                        try { Thread.sleep(RETRY_INTERVAL_MS) } catch (_: InterruptedException) { break }
                    }
                    updateStatus(this, "校园网自动认证", "正在认证（第 $attempt/$MAX_ATTEMPTS 次）...")

                    val ip = waitForValidIp(NETWORK_READY_TIMEOUT_MS.toLong())
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
                            val r = LoginService.doLogin(username, password, isp, ip)
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
            wifi?.connectionInfo?.let { return normalizeSsid(it.ssid) }
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

    private fun isWifiConnected(): Boolean {
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                val n = (ni.name ?: "").lowercase()
                if (n.startsWith("wlan")) return true
            }
        } catch (_: Exception) {}
        return false
    }

    private fun waitForWifiReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ssid = getCurrentSsid()
            if (isTargetSsid(ssid)) {
                val ip = LoginService.getLocalIPv4(this)
                if (isValidIp(ip)) return true
            }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
        }
        return false
    }

    private fun waitForValidIp(timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ip = LoginService.getLocalIPv4(this)
            if (isValidIp(ip)) return ip
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
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
                if (ssidMatches(t, ssid)) return true
            }
        } catch (_: Exception) {
            for (t in TARGET_SSIDS) {
                if (ssidMatches(t, ssid)) return true
            }
        }
        return false
    }
}
