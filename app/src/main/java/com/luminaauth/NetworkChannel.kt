package com.luminaauth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * 发送通道：固定三项，不做运行时扫描。
 *
 * 0 = WiFi
 * 1 = 移动数据
 * 2 = 以太网
 *
 * 认证门户场景下当前 WiFi 没有默认路由（默认网络可能仍落在蜂窝上），
 * 所以必须显式拿到该通道对应的 [Network] 再发 HTTP 请求。
 */
object NetworkChannel {
    const val WIFI = 0
    const val CELLULAR = 1
    const val ETHERNET = 2

    val labels = listOf("WiFi", "移动数据", "以太网")

    private var connectivityManager: ConnectivityManager? = null

    /** 选中通道对应的 Android [Network]，沙箱 HTTP 用它强制从该网卡出站。 */
    @Volatile
    var boundNetwork: Network? = null
        private set

    /** 选中通道的网卡名（"wlan0" / "rmnet0" / "eth0"），给原生 socket 的 SO_BINDTODEVICE 用。 */
    @Volatile
    var boundIface: String = ""
        private set

    @Volatile
    private var currentChannel: Int = -1

    /** 本进程是否已被 bindProcessToNetwork 绑定，以及绑到了哪个 Network。 */
    @Volatile
    private var processBinding = false
    @Volatile
    private var processBoundNetwork: Network? = null

    /** 插件声明的优先网卡名，空表示按传输类型挑第一个可用。 */
    @Volatile
    var preferredIfaces: List<String> = emptyList()

    /** 常驻监听：网络晚于进程出现（冷启动时 WiFi 还没连上）时自动补绑。 */
    private val watchers = HashMap<Int, ConnectivityManager.NetworkCallback>()

    fun init(context: Context) {
        connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private fun transportOf(channel: Int): Int = when (channel) {
        CELLULAR -> NetworkCapabilities.TRANSPORT_CELLULAR
        ETHERNET -> NetworkCapabilities.TRANSPORT_ETHERNET
        else -> NetworkCapabilities.TRANSPORT_WIFI
    }

    /**
     * 应用选中通道：优先同步枚举已有网络并缓存对应 [Network]。
     * 返回网卡名（如 "wlan0"），供 C++ `SO_BINDTODEVICE` 使用；失败返回空串表示暂未绑定。
     */
    @Synchronized
    fun applyChannel(channel: Int): String {
        val cm = connectivityManager
        if (cm == null) {
            boundNetwork = null
            return ""
        }
        currentChannel = channel
        val transport = transportOf(channel)
        watch(channel, transport)
        // 只认真实还活在 allNetworks 里的 Network；旧代码会把已丢失的 boundNetwork
        // 继续当兜底，WiFi 重连后进程仍绑在旧 Network 上，后续 socket 全部 ENETUNREACH。
        val network = pick(cm, transport)
        boundNetwork = network
        val iface = ifaceOf(cm, network)
        boundIface = iface
        syncProcessBinding(network)
        return iface
    }

    private fun ifaceOf(cm: ConnectivityManager, network: Network?): String {
        if (network == null) return ""
        return try {
            cm.getLinkProperties(network)?.interfaceName.orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    /** 同步挑一个该传输类型的网络；跳过 VPN 虚拟网卡和没有网卡名的网络。 */
    private fun pick(cm: ConnectivityManager, transport: Int): Network? {
        val all = try {
            cm.allNetworks
        } catch (_: Throwable) {
            return null
        }
        for (n in all) {
            val caps = try { cm.getNetworkCapabilities(n) } catch (_: Throwable) { null } ?: continue
            if (!caps.hasTransport(transport)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            val iface = try { cm.getLinkProperties(n)?.interfaceName } catch (_: Throwable) { null }
            if (iface.isNullOrEmpty()) continue
            if (preferredIfaces.isNotEmpty() && preferredIfaces.none { iface.startsWith(it) }) continue
            return n
        }
        return null
    }

    private fun watch(channel: Int, transport: Int) {
        val cm = connectivityManager ?: return
        if (watchers.containsKey(transport)) return
        val request = NetworkRequest.Builder()
            .addTransportType(transport)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                if (currentChannel != channel) return
                boundNetwork = n
                val iface = ifaceOf(cm, n)
                boundIface = iface
                syncProcessBinding(n)
                LogBuffer.add("NET", "watch avail ch=${labels.getOrElse(channel) { "na" }} iface=${iface.ifEmpty { "na" }}")
            }

            override fun onLost(n: Network) {
                if (boundNetwork != n) return
                boundNetwork = pick(cm, transport)
                boundIface = ifaceOf(cm, boundNetwork)
                syncProcessBinding(boundNetwork)
                LogBuffer.add("NET", "watch lost ch=${labels.getOrElse(channel) { "na" }}"
                        + " rebind=" + (boundNetwork != null)
                        + " process=" + (if (processBinding) 1 else 0))
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            watchers[transport] = callback
        } catch (_: Throwable) {
        }
    }

    private var lastPromoteAt = 0L
    private const val PROMOTE_COOLDOWN_MS = 3000L

    /**
     * 克制版“催系统评估”：认证成功后最多 3 秒触发一次，不循环轰炸。
     * 只在当前默认网络不是所选通道时才动作；增强模式 + root 时额外做一次全局默认网络切换。
     * 返回用于日志的结果串。
     */
    @Synchronized
    fun promoteOnce(channel: Int, allowPromote: Boolean = true, allowRoot: Boolean = true): String {
        val cm = connectivityManager ?: return "skip:no-cm"
        if (!allowPromote) return "skip:disabled"
        val now = System.currentTimeMillis()
        if (now - lastPromoteAt < PROMOTE_COOLDOWN_MS) return "skip:cooldown"
        lastPromoteAt = now

        val transport = transportOf(channel)
        val target = pick(cm, transport) ?: return "skip:no-network"
        val current = try { cm.activeNetwork } catch (_: Throwable) { null }
        if (current != null && current == target) return "already:default"

        val rootPath = allowRoot && PrefUtils.isEnhancedModeEnabled(AppContext.get()) && RootUtils.isRootAvailable()
        if (rootPath) {
            val netId = netIdOf(target)
            if (netId > 0) {
                val out = RootUtils.exec("ndc network default set " + netId)
                if (out != null) {
                    LogBuffer.add("NET", "promote root netId=" + netId + " out=" + out.lineSequence().first())
                    if (out.isEmpty() || out.contains("200")) return "root:set:" + netId
                }
            }
        }

        return try {
            cm.reportNetworkConnectivity(target, true)
            "requested"
        } catch (e: Throwable) {
            "denied:" + e.javaClass.simpleName
        }
    }

    /** 把本进程流量绑定到指定网络；失败返回 false，不影响后续逻辑。 */
    @Synchronized
    fun bindProcessTo(network: Network?): Boolean {
        val cm = connectivityManager ?: return false
        if (network == null) {
            unbindProcess()
            return false
        }
        // 未通过系统验证的 WiFi（刚登录/门户未放行）不能作为进程默认网络，
        // 否则 App 自身所有请求都会落到一个没有 Internet 的网上，表现为“网络不可达”。
        if (!isProcessBindable(cm, network)) {
            LogBuffer.add("NET", "bind_process skip reason=not_validated")
            unbindProcess()
            return false
        }
        return try {
            cm.bindProcessToNetwork(network)
            processBinding = true
            processBoundNetwork = network
            LogBuffer.add("NET", "bind_process target=" + (ifaceOf(cm, network).ifEmpty { "na" }))
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 解除进程绑定；网络丢失/服务停止/通道切换时必须调用，避免绑死在失效 Network 上。 */
    @Synchronized
    fun unbindProcess() {
        if (!processBinding) return
        try {
            connectivityManager?.bindProcessToNetwork(null)
            LogBuffer.add("NET", "bind_process unbind=1")
        } catch (_: Throwable) {
        } finally {
            processBinding = false
            processBoundNetwork = null
        }
    }

    private fun isProcessBindable(cm: ConnectivityManager, network: Network): Boolean {
        return try {
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Throwable) {
            false
        }
    }

    /** 网络对象变化后同步进程绑定：换到新 Network；没有可用网络则解绑。 */
    @Synchronized
    private fun syncProcessBinding(target: Network?) {
        if (!processBinding) return
        if (target == null) {
            unbindProcess()
            return
        }
        if (target == processBoundNetwork) return
        val cm = connectivityManager ?: return
        if (!isProcessBindable(cm, target)) {
            unbindProcess()
            return
        }
        try {
            cm.bindProcessToNetwork(target)
            processBoundNetwork = target
            LogBuffer.add("NET", "bind_process rebind=" + (ifaceOf(cm, target).ifEmpty { "na" }))
        } catch (_: Throwable) {
            unbindProcess()
        }
    }

    /** 通过反射拿 netId（隐藏字段，拿不到返回 -1，直接跳过 root 路径）。 */
    private fun netIdOf(n: Network): Int {
        return try {
            val f = Network::class.java.getDeclaredField("netId")
            f.isAccessible = true
            f.getInt(n)
        } catch (_: Throwable) {
            -1
        }
    }
}
