package com.luminaauth.plugin

import com.luminaauth.LogBuffer
import com.luminaauth.NativeBridge
import com.luminaauth.NetworkChannel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Sandboxed HTTP transport: the single network egress point for plugins.
 *
 * Guarantees:
 *  - only http/https requests
 *  - the resolved host[:port] must match the plugin's declared allow-list
 *  - redirects are never followed, so a request cannot be steered off-list
 *  - bounded connect/read timeouts
 * The response body is captured even for non-200 statuses so the runtime can
 * evaluate server messages.
 */
object SandboxHttp {

    data class Response(
        val status: Int,
        val body: String,
        val transportError: String?,
    ) {
        val ok: Boolean get() = transportError == null
    }

    /** 仅当插件没有声明 User-Agent 时使用的兜底值，保证老插件行为不变。 */
    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/153.0.0.0 Mobile Safari/537.36"

    /** 不能由 HttpURLConnection 手动设置的受限头，交给系统自己算。 */
    private val RESTRICTED_HEADERS = setOf(
        "host", "content-length", "connection", "transfer-encoding", "content-encoding",
    )

    fun execute(
        plugin: Plugin,
        method: String,
        urlString: String,
        body: String? = null,
        connectTimeoutMs: Int = 8000,
        readTimeoutMs: Int = 8000,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val uri = try {
            URI(urlString)
        } catch (e: Exception) {
            return Response(-1, "", "malformed_url: ${e.message}")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Response(-1, "", "scheme_denied: $scheme")
        }
        val host = uri.host?.lowercase()
            ?: return Response(-1, "", "no_host")
        val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        if (!isHostAllowed(plugin, host, uri.port)) {
            return Response(-1, "", "host_denied: $host:$port")
        }

        val normalized = normalizeHeaders(headers)
        val referer = normalized.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        val iface = NetworkChannel.boundIface
        // http 请求优先走原生 socket：SO_BINDTODEVICE 直接锁网卡出站，
        // 不受系统默认路由（未认证 WiFi 常常没有默认路由）影响。
        if (scheme == "http" && iface.isNotEmpty()) {
            val native = executeNative(
                method, urlString, body,
                maxOf(connectTimeoutMs, readTimeoutMs),
                referer = referer,
                headers = normalized,
            )
            if (native != null && native.ok) return native
            LogBuffer.addDetail(
                "PLUGIN",
                "native_http fallback iface=$iface err=${native?.transportError ?: "na"}"
            )
        }

        var conn: HttpURLConnection? = null
        try {
            val urlObj = uri.toURL()
            // 选中通道对应 Network：未认证门户下系统默认路由可能仍落在移动数据上，
            // 必须显式从该 Network 建连接，否则内网认证服务器会走蜂窝出口而超时不可达。
            val net = NetworkChannel.boundNetwork
            conn = ((net?.openConnection(urlObj) ?: urlObj.openConnection())
                as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                // 请求头全部来自插件；只有插件没写时才补默认 UA / Accept
                val hasUa = normalized.keys.any { it.equals("User-Agent", true) }
                if (!hasUa) setRequestProperty("User-Agent", FALLBACK_UA)
                normalized.forEach { (k, v) ->
                    if (k.lowercase() !in RESTRICTED_HEADERS) setRequestProperty(k, v)
                }
            }
            if (method == "POST" && body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val os: OutputStream = conn.outputStream
                os.write(body.toByteArray(Charsets.UTF_8))
                os.close()
            }
            val status = conn.responseCode
            val stream = if (status in 200..399) conn.inputStream else conn.errorStream
            val sb = StringBuilder()
            if (stream != null) {
                val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                reader.use { r ->
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        sb.append(line)
                        // 上限防御：异常巨大的门户页面不应把内存打满
                        if (sb.length >= MAX_BODY_CHARS) break
                    }
                }
            }
            return Response(status, sb.toString(), null)
        } catch (e: Throwable) {
            // 兜底范围必须是 Throwable：Error（OOM / StackOverflow / 类初始化失败）
            // 若逃逸出登录线程就会成为未捕获异常并直接终止进程（点击登录后闪退）。
            return Response(-1, "", "transport: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    /** 单次响应最多读取的字符数，防止异常巨大的页面耗尽内存。 */
    private const val MAX_BODY_CHARS = 262_144

    /**
     * 原生 socket 传输（C++ `native_http.cpp`），返回 null 表示原生不可用。
     * 只做传输，安全策略（scheme / host 白名单）已在上层校验完毕。
     */
    private fun executeNative(
        method: String,
        url: String,
        body: String?,
        timeoutMs: Int,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response? {
        if (!NativeBridge.isNativeAvailable()) return null
        return try {
            val json = NativeBridge.nativeHttpRequest(
                method, url, body, referer,
                buildHeaderBlock(headers, dropReferer = !referer.isNullOrEmpty()),
                timeoutMs,
            )
            if (json.isEmpty()) return null
            val obj = org.json.JSONObject(json)
            val err = obj.optString("error")
            if (err.isNotEmpty()) {
                Response(-1, "", "native: $err")
            } else {
                val code = obj.optInt("code", -1)
                val text = obj.optString("body")
                if (code <= 0) Response(-1, "", "native: bad_response") else Response(code, text, null)
            }
        } catch (e: Throwable) {
            Response(-1, "", "native: ${e.javaClass.simpleName}")
        }
    }


    /** 合并、去重、过滤换行（头注入防御）；键名保持插件写法。 */
    private fun normalizeHeaders(headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>(headers.size)
        headers.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim().replace("\r", "").replace("\n", "")
            val value = rawValue.trim().replace("\r", "").replace("\n", "")
            if (key.isEmpty() || value.isEmpty()) return@forEach
            // 同名（忽略大小写）以最后一次声明为准
            val existing = out.keys.firstOrNull { it.equals(key, true) }
            if (existing != null) out.remove(existing)
            out[key] = value
        }
        return out
    }

    /** 原生侧需要的是整块 "Name: Value\r\n" 文本。 */
    private fun buildHeaderBlock(headers: Map<String, String>, dropReferer: Boolean): String {
        if (headers.isEmpty()) return ""
        val sb = StringBuilder()
        headers.forEach { (k, v) ->
            if (dropReferer && k.equals("Referer", true)) return@forEach
            if (k.equals("Host", true) || k.equals("Content-Length", true) ||
                k.equals("Connection", true)) return@forEach
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        return sb.toString()
    }

    private fun isHostAllowed(plugin: Plugin, host: String, declaredPort: Int): Boolean {
        val effectivePort = if (declaredPort >= 0) declaredPort else null
        return plugin.hosts.any { entry ->
            val colon = entry.indexOf(':')
            if (colon < 0) {
                entry == host
            } else {
                val eh = entry.substring(0, colon)
                val ep = entry.substring(colon + 1).toIntOrNull() ?: return@any false
                eh == host && (effectivePort == null || ep == effectivePort)
            }
        }
    }
}
