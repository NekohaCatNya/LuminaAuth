package com.luminaauth

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** 崩溃捕获：写到 files/crash.txt，下次启动时读出来直接显示在屏幕上 */
object CrashLog {
    @Volatile
    private var pending: String? = null

    fun install(context: Context) {
        pending = readFile(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val text = "thread=" + thread.name + "\n" + sw.toString()
                pending = text
                File(context.filesDir, "crash.txt").writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun peek(): String? = pending

    fun consume(context: Context): String? {
        val value = pending ?: readFile(context)
        pending = null
        runCatching { File(context.filesDir, "crash.txt").delete() }
        return value
    }

    private fun readFile(context: Context): String? = runCatching {
        val file = File(context.filesDir, "crash.txt")
        if (file.exists()) file.readText() else null
    }.getOrNull()
}
