package com.luminaauth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

object NetworkChannel {
    private var connectivityManager: ConnectivityManager? = null
    private val callbacks = mutableMapOf<Int, ConnectivityManager.NetworkCallback>()
    val availableChannels = mutableListOf<ChannelInfo>()

    data class ChannelInfo(
        val transport: Int,
        val label: String,
        val iface: String,
    )

    fun init(context: Context) {
        connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    fun scanAvailable(): List<ChannelInfo> {
        val cm = connectivityManager ?: return emptyList()
        callbacks.values.forEach { runCatching { cm.unregisterNetworkCallback(it) } }
        callbacks.clear()
        availableChannels.clear()

        val allTransports = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "WiFi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "移动数据",
            NetworkCapabilities.TRANSPORT_ETHERNET to "以太网",
        )

        val latch = java.util.concurrent.CountDownLatch(allTransports.size)

        for ((transport, label) in allTransports) {
            try {
                val request = NetworkRequest.Builder()
                    .addTransportType(transport)
                    .build()
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        val props = cm.getLinkProperties(network)
                        val iface = props?.interfaceName ?: ""
                        synchronized(availableChannels) {
                            availableChannels.add(ChannelInfo(transport, label, iface))
                        }
                        latch.countDown()
                    }
                }
                cm.registerNetworkCallback(request, callback)
                callbacks[transport] = callback
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        return availableChannels.toList()
    }

    fun requestTransport(transport: Int): String {
        val cm = connectivityManager ?: return ""
        return try {
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
