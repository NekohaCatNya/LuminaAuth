package com.luminaauth

object NativeBridge {
    private var sNativeAvailable = false

    init {
        try {
            System.loadLibrary("lumina-native")
            nativeInit()
            sNativeAvailable = true
        } catch (_: UnsatisfiedLinkError) {
            sNativeAvailable = false
        }
    }

    @JvmStatic
    fun isNativeAvailable(): Boolean = sNativeAvailable

    @JvmStatic external fun nativeInit()
    @JvmStatic external fun nativeAddLog(line: String, isDetail: Boolean)
    @JvmStatic external fun nativeGetAllText(isDetail: Boolean): String
    @JvmStatic external fun nativeClear(isDetail: Boolean)
    @JvmStatic external fun nativeParseDrcom(body: String): String
    /** 原生 socket HTTP：返回 {"code":200,"body":"...","error":""}，error 非空即传输层失败。 */
    @JvmStatic external fun nativeHttpRequest(
        method: String, url: String, body: String?, referer: String?, timeoutMs: Int
    ): String
    @JvmStatic external fun nativeTcpPing(host: String, port: Int): Boolean
    @JvmStatic external fun nativeResolveHost(host: String): String
    @JvmStatic external fun nativeSetBindIface(iface: String): Unit
}
