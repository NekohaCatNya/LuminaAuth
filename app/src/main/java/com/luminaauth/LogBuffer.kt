package com.luminaauth

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogBuffer {
    private const val MAX_LINE_LENGTH = 512
    private val SDF = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var listener: LogListener? = null

    interface LogListener {
        fun onLogUpdated()
    }

    @JvmStatic
    fun setListener(l: LogListener?) { listener = l }

    private fun format(tag: String, message: String): String {
        var line = SDF.format(Date()) + " " + tag + " " + message
        if (line.length > MAX_LINE_LENGTH) line = line.substring(0, MAX_LINE_LENGTH)
        return line
    }

    @JvmStatic
    fun add(tag: String, message: String) {
        val line = format(tag, message)
        NativeBridge.nativeAddLog(line, false)
        listener?.onLogUpdated()
    }

    @JvmStatic
    fun addDetail(tag: String, message: String) {
        val line = format(tag, message)
        NativeBridge.nativeAddLog(line, true)
        listener?.onLogUpdated()
    }

    @JvmStatic
    fun getAllText(): String = NativeBridge.nativeGetAllText(false)

    @JvmStatic
    fun getDetailAllText(): String = NativeBridge.nativeGetAllText(true)

    @JvmStatic
    fun clear() {
        NativeBridge.nativeClear(false)
        listener?.onLogUpdated()
    }

    @JvmStatic
    fun clearDetail() {
        NativeBridge.nativeClear(true)
        listener?.onLogUpdated()
    }
}
