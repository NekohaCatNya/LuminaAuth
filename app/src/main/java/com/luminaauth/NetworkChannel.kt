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
     * 应用选中通道：解析出网卡名并缓存对应 [Network]。
     * 返回网卡名（如 "wlan0"），供 C++ `SO_BINDTODEVICE` 使用；失败返回空串表示不绑定。
     */
    fun applyChannel(channel: Int): String {
        val cm = connectivityManager ?: run {
            boundNetwork = null
            return ""
        }
        return try {
            val request = NetworkRequest.Builder()
                .addTransportType(transportOf(channel))
                .build()
            var iface = ""
            var network: Network? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    iface = cm.getLinkProperties(n)?.interfaceName ?: ""
                    network = n
                    latch.countDown()
                }
            }
            cm.requestNetwork(request, callback)
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            boundNetwork = network
            iface
        } catch (_: Exception) {
            boundNetwork = null
            ""
        }
    }
}
