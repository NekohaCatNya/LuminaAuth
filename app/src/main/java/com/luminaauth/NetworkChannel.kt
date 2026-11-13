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

    @Volatile
    private var currentChannel: Int = -1

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
        return network?.let { n ->
            try {
                cm.getLinkProperties(n)?.interfaceName.orEmpty()
            } catch (_: Throwable) {
                ""
            }
        }.orEmpty()
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
                val iface = try { cm.getLinkProperties(n)?.interfaceName } catch (_: Throwable) { null }
                LogBuffer.add("NET", "watch avail ch=${labels.getOrElse(channel) { "na" }} iface=${iface ?: "na"}")
            }

            override fun onLost(n: Network) {
                if (boundNetwork != n) return
                boundNetwork = pick(cm, transport)
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

    /**
     * 增强模式专用：认证成功后催促系统重新评估网络，让默认网络尽快切到所选通道。
     * 只用 root 重新拉起该通道的连接，不改其它通道的开关状态。
     * 返回实际执行的命令，未执行返回 null。
     */
    fun nudgeDefaultNetwork(channel: Int): String? {
        val cmd = when (channel) {
            WIFI -> "cmd wifi reconnect"
            CELLULAR -> "svc data disable; sleep 1; svc data enable"
            else -> null
        } ?: return null
        val out = RootUtils.exec(cmd) ?: return null
        return cmd + (if (out.isEmpty()) "" else " => " + out.lineSequence().first())
    }
}
