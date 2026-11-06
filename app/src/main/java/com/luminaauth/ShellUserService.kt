package com.luminaauth

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.InputStream
import java.util.concurrent.TimeUnit

class ShellUserService : Service() {
    private val binder = object : IShell.Stub() {
        @Throws(android.os.RemoteException::class)
        override fun exec(command: String): String? {
            var process: Process? = null
            return try {
                process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val finished = process.waitFor(3000, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroy()
                    null
                } else {
                    readAll(process.inputStream)
                }
            } catch (_: Throwable) {
                null
            } finally {
                try { process?.destroy() } catch (_: Throwable) {}
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun readAll(input: InputStream): String {
        val buf = ByteArray(8192)
        val sb = StringBuilder()
        var total = 0
        while (true) {
            val len = input.read(buf)
            if (len <= 0) break
            sb.append(String(buf, 0, len))
            total += len
            if (total > 65536) break
        }
        return sb.toString()
    }
}
