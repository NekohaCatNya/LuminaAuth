package com.luminaauth

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object NetworkUtils {
    @JvmStatic
    @Throws(Exception::class)
    fun get(urlString: String): String = get(urlString, null)

    @JvmStatic
    @Throws(Exception::class)
    fun get(urlString: String, referer: String?): String {
        if (NativeBridge.isNativeAvailable()) {
            val json = NativeBridge.nativeHttpRequest("GET", urlString, null, referer, 8000)
            val o = JSONObject(json)
            val code = o.optInt("code", 0)
            val err = o.optString("error", "")
            if (err.isNotEmpty()) throw IOException(err)
            if (code != 200) throw IOException("HTTP error: $code")
            return o.optString("body", "")
        }
        // Java fallback
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/153.0.0.0 Mobile Safari/537.36")
        if (referer != null) conn.setRequestProperty("Referer", referer)
        val code = conn.responseCode
        if (code != 200) throw IOException("HTTP error: $code")
        val r = BufferedReader(InputStreamReader(conn.inputStream))
        val sb = StringBuilder()
        var line: String?
        while (r.readLine().also { line = it } != null) sb.append(line)
        r.close()
        return sb.toString()
    }
}
