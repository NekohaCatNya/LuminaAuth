package com.luminaauth.plugin

import android.content.Context
import com.luminaauth.LogBuffer
import com.luminaauth.LoginService
import com.luminaauth.NativeBridge
import org.json.JSONObject

/**
 * Kotlin 只负责把 SessionState 打包成 JNI 入参，再把 C++ 插件引擎的结果写回状态。
 * check/login 的模板展开、HTTP、匹配、capture、失败原因都在 plugin_engine.cpp。
 */
object PluginRuntime {

    fun prepare(context: Context, state: SessionState) {
        if (state.ip == null) state.ip = LoginService.getLocalIPv4(context)
    }

    fun getEffectiveLoginUrl(context: Context, customInputUrl: String): String =
        if (customInputUrl.isNotBlank()) customInputUrl
        else PluginManager.defaultLoginUrl(context)

    fun check(state: SessionState): Outcome = run(state, login = false)

    fun login(state: SessionState): Outcome = run(state, login = true)

    private fun run(state: SessionState, login: Boolean): Outcome {
        return try {
            val stateJson = stateJson(state)
            val raw = if (login) {
                NativeBridge.nativePluginLogin(state.plugin.id, stateJson)
            } else {
                NativeBridge.nativePluginCheck(state.plugin.id, stateJson)
            }
            applyResult(state, raw)
        } catch (t: Throwable) {
            state.reason = "plugin_error"
            state.detail = "插件运行异常 ${t.javaClass.simpleName}"
            state.outcome = Outcome.ERROR
            LogBuffer.addDetail("PLUGIN", "runtime_fail ${t.javaClass.simpleName}")
            Outcome.ERROR
        }
    }

    private fun stateJson(state: SessionState): String {
        val o = JSONObject()
        val inputs = JSONObject()
        state.inputs.forEach { (k, v) -> inputs.put(k, v) }
        o.put("inputs", inputs)
        o.put("isp", state.ispSuffix)
        o.put("ip", state.ip ?: "")
        o.put("mac", state.mac)
        o.put("rand", state.rand)
        state.loginUrlOverride?.let { o.put("loginUrlOverride", it) }
        return o.toString()
    }

    private fun applyResult(state: SessionState, raw: String): Outcome {
        val o = try { JSONObject(raw) } catch (_: Throwable) { JSONObject() }
        val outcome = when (o.optString("outcome")) {
            "ONLINE" -> Outcome.ONLINE
            "OFFLINE" -> Outcome.OFFLINE
            "UNREACHABLE" -> Outcome.UNREACHABLE
            "SUCCESS" -> Outcome.SUCCESS
            "ALREADY_ONLINE" -> Outcome.ALREADY_ONLINE
            "FAIL" -> Outcome.FAIL
            else -> Outcome.ERROR
        }
        state.outcome = outcome
        state.reason = o.optString("reason", "plugin_error")
        state.detail = o.optString("detail", "")
        val vars = o.optJSONObject("vars")
        if (vars != null) {
            vars.keys().forEach { k -> state.vars[k] = vars.optString(k) }
        }
        LogBuffer.addDetail("PLUGIN", "runtime result=${state.reason} outcome=${outcome.name.lowercase()}")
        return outcome
    }
}
