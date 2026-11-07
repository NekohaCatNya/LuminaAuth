package com.luminaauth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest

object NetworkChannel {
    private var connectivityManager: ConnectivityManager? = null

    fun init(context: Context) {
        connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    // netChannel: 0 = WiFi, 1 = 移动数据
    // 返回绑定的接口名（如 "wlan0" / "rmnet0"），传给 C++ 做 SO_BINDTODEVICE
    fun requestChannel(netChannel: Int): String {
        val cm = connectivityManager ?: return ""
        return try {
            val transport = if (netChannel == 0)
                android.net.NetworkCapabilities.TRANSPORT_WIFI
            else
                android.net.NetworkCapabilities.TRANSPORT_CELLULAR

            val request = NetworkRequest.Builder()
                .addTransportType(transport)
                .build()

            var iface = ""
            val latch = java.util.concurrent.CountDownLatch(1)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val props = cm.getLinkProperties(network)
                    iface = props?.interfaceName ?: ""
                    latch.countDown()
                }
            }
            cm.requestNetwork(request, callback)
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            iface
        } catch (_: Exception) {
            ""
        }
    }
}
