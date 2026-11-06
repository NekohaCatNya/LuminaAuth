package com.luminaauth

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object RootUtils {
    private val SHELL_LOCK = Any()
    private val OUT_LOCK = Any()
    private val outBuf = StringBuilder()
    private var shellProcess: Process? = null
    private var shellIn: BufferedWriter? = null
    private var seq = 0

    private fun ensureShell(): Process? {
        synchronized(SHELL_LOCK) {
            val p = shellProcess
            if (p != null && p.isAlive && shellIn != null) return p
            closeShell()
            return try {
                val proc = ProcessBuilder("su").redirectErrorStream(true).start()
                val writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
                val reader = Thread {
                    try {
                        val r = BufferedReader(InputStreamReader(proc.inputStream))
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            synchronized(OUT_LOCK) {
                                outBuf.append(line).append('\n')
                                (OUT_LOCK as Object).notifyAll()
                            }
                        }
                    } catch (_: Exception) {}
                }
                reader.isDaemon = true
                reader.start()
                shellProcess = proc
                shellIn = writer
                proc
            } catch (_: Throwable) {
                closeShell()
                null
            }
        }
    }

    private fun closeShell() {
        try { shellIn?.close() } catch (_: Exception) {}
        try { shellProcess?.destroy() } catch (_: Exception) {}
        shellProcess = null
        shellIn = null
    }

    @JvmStatic
    fun isRootAvailable(): Boolean = ensureShell() != null

    @JvmStatic
    fun resetCache() = closeShell()

    @JvmStatic
    fun exec(command: String): String? {
        synchronized(SHELL_LOCK) {
            val p = ensureShell() ?: return null
            val writer = shellIn ?: return null
            val marker = "__LUMINA_" + (++seq) + "__"
            synchronized(OUT_LOCK) { outBuf.setLength(0) }
            try {
                writer.write(command + "\n")
                writer.write("echo " + marker + " $?\n")
                writer.flush()
            } catch (_: Exception) {
                closeShell()
                return null
            }
            val result = StringBuilder()
            val deadline = System.currentTimeMillis() + 10000
            var done = false
            synchronized(OUT_LOCK) {
                while (System.currentTimeMillis() < deadline) {
                    val buf = outBuf.toString()
                    val idx = buf.indexOf(marker)
                    if (idx >= 0) {
                        result.append(buf, 0, idx)
                        outBuf.delete(0, idx + marker.length)
                        done = true
                        break
                    }
                    val remain = deadline - System.currentTimeMillis()
                    if (remain <= 0) break
                    try { (OUT_LOCK as Object).wait(Math.min(1000, remain)) } catch (_: InterruptedException) { break }
                }
            }
            if (!done) { closeShell(); return null }
            val r = result.toString().trim()
            return r.ifEmpty { null }
        }
    }

    @JvmStatic
    fun boostProcessPriority(): Boolean {
        return try {
            val pid = android.os.Process.myPid()
            exec("echo -17 > /proc/$pid/oom_score_adj")
            exec("renice -n -20 -p $pid")
            true
        } catch (_: Throwable) { false }
    }

    @JvmStatic
    fun getWifiSsidViaRoot(): String? {
        var out = exec("cmd wifi status")
        var ssid = parseConnectedSsid(out)
        if (!ssid.isNullOrEmpty()) return ssid
        out = exec("dumpsys wifi")
        if (out != null) {
            val m = Regex("SSID:\\s*\"?([^\",\\s]+)").find(out)
            if (m != null) return m.groupValues[1].trim()
        }
        return null
    }

    private fun parseConnectedSsid(out: String?): String? {
        if (out == null) return null
        val m = Regex("connected\\s+to\\s+[\"']?([^\"'\\n]+)[\"']?", RegexOption.IGNORE_CASE).find(out)
        return m?.groupValues?.get(1)?.trim()
    }
}
