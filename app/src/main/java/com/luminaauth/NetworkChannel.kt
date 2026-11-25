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
        val network = pick(cm, transport) ?: boundNetwork?.takeIf { cm.getNetworkCapabilities(it)?.hasTransport(transport) == true }
        boundNetwork = network
        val iface = ifaceOf(cm, network)
        boundIface = iface
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
                LogBuffer.add("NET", "watch avail ch=${labels.getOrElse(channel) { "na" }} iface=${iface.ifEmpty { "na" }}")
            }

            override fun onLost(n: Network) {
                if (boundNetwork != n) return
                boundNetwork = pick(cm, transport)
                boundIface = ifaceOf(cm, boundNetwork)
                LogBuffer.add("NET", "watch lost ch=${labels.getOrElse(channel) { "na" }}"
                        + " rebind=" + (boundNetwork != null))
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
        val target = pick(cm, transport) ?: boundNetwork ?: return "skip:no-network"
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
    fun bindProcessTo(network: Network?): Boolean {
        val cm = connectivityManager ?: return false
        if (network == null) return false
        return try {
            cm.bindProcessToNetwork(network)
        } catch (_: Throwable) {
            false
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
