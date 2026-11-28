package com.luminaauth.plugin

import com.luminaauth.NativeBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin 侧只做 JNI 边界：把 C++ 插件引擎返回的描述 JSON 映射成 UI 需要的数据类。
 * YAML 解析、键名校验、沙盒主机校验、原始 request 拆分、check/login 执行全部在
 * app/src/main/cpp/plugin_engine.cpp，Kotlin 不再保留第二套解析逻辑。
 */
object PluginLoader {

    fun load(text: String, fileName: String, source: PluginSource): Plugin {
        val raw = try {
            NativeBridge.nativePluginLoad(text, fileName, source.name)
        } catch (t: Throwable) {
            throw PluginParseException("原生插件引擎不可用：${t.javaClass.simpleName}")
        }
        val result = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            throw PluginParseException("原生插件引擎返回了非法 JSON")
        }
        if (!result.optBoolean("ok", false)) {
            throw PluginParseException(result.optString("error", "插件解析失败"))
        }
        val p = result.optJSONObject("plugin")
            ?: throw PluginParseException("原生插件引擎缺少 plugin 描述")
        return mapPlugin(p, source, fileName)
    }

    private fun mapPlugin(p: JSONObject, source: PluginSource, fileName: String): Plugin {
        return Plugin(
            id = p.optString("id"),
            name = p.optString("name"),
            version = p.optInt("version", 1),
            description = p.optString("description"),
            ssidPatterns = strings(p.optJSONArray("ssids")),
            hosts = strings(p.optJSONArray("hosts")),
            fields = objects(p.optJSONArray("fields")).map { o ->
                FieldDef(
                    id = o.optString("id"),
                    label = o.optString("label"),
                    type = if (o.optString("type") == "password") FieldType.PASSWORD else FieldType.TEXT,
                    required = o.optBoolean("required", true),
                )
            },
            isps = objects(p.optJSONArray("isps")).map { o ->
                IspDef(
                    id = o.optString("id"),
                    label = o.optString("label"),
                    default = o.optBoolean("default", false),
                )
            },
            check = p.optJSONObject("check")?.let { o ->
                CheckDef(
                    method = o.optString("method", "GET"),
                    url = o.optString("url"),
                    onlineContains = o.stringOrNull("online"),
                    offlineContains = o.stringOrNull("offline"),
                    headers = headers(o.optJSONObject("headers")),
                    body = o.stringOrNull("body"),
                )
            },
            loginSteps = objects(p.optJSONArray("steps")).map { o ->
                StepDef(
                    name = o.optString("name"),
                    method = o.optString("method", "GET"),
                    url = o.optString("url"),
                    expectStatus = if (o.isNull("expectStatus")) null else o.optInt("expectStatus"),
                    success = match(o.optJSONObject("success")),
                    alreadyOnline = match(o.optJSONObject("alreadyOnline")),
                    captures = objects(o.optJSONArray("captures")).map { c ->
                        CaptureDef(
                            name = c.optString("name"),
                            jsonPath = c.stringOrNull("jsonPath"),
                            regex = c.stringOrNull("regex"),
                            group = c.optInt("group", 1),
                        )
                    },
                    failMessageJsonPath = o.stringOrNull("failJsonPath"),
                    headers = headers(o.optJSONObject("headers")),
                    body = o.stringOrNull("body"),
                )
            },
            source = source,
            fileName = fileName,
            headers = headers(p.optJSONObject("headers")),
            timing = p.optJSONObject("timing")?.let { o ->
                TimingDef(
                    ipPollMs = o.optLong("ipPollMs", 250L),
                    maxIpWaitMs = o.optLong("maxIpWaitMs", 3000L),
                    skipNetworkReadyOnNotify = o.optBoolean("skipNetworkReadyOnNotify", true),
                    connectTimeoutMs = o.optInt("connectTimeoutMs", 6000),
                    readTimeoutMs = o.optInt("readTimeoutMs", 6000),
                )
            } ?: TimingDef(),
            policy = p.optJSONObject("policy")?.let { o ->
                NetworkPolicyDef(
                    bindProcess = o.optBoolean("bindProcess", true),
                    promoteNetwork = o.optBoolean("promoteNetwork", true),
                    rootSetDefault = o.optBoolean("rootSetDefault", true),
                    preferredIfaces = strings(o.optJSONArray("preferredIfaces")),
                    authOverWifi = o.optBoolean("authOverWifi", true),
                )
            } ?: NetworkPolicyDef(),
            detect = p.optJSONObject("detect")?.let { o ->
                DetectDef(
                    retryCount = o.optInt("retryCount", 3),
                    retryDelayMs = o.optLong("retryDelayMs", 5000L),
                )
            } ?: DetectDef(),
        )
    }

    private fun match(o: JSONObject?): MatchDef? {
        if (o == null) return null
        return MatchDef(
            bodyContains = o.stringOrNull("contains"),
            bodyRegex = o.stringOrNull("regex"),
            jsonPath = o.stringOrNull("jsonPath"),
            jsonEquals = o.stringOrNull("jsonEquals"),
        )
    }

    private fun headers(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        o.keys().forEach { k -> out[k] = o.optString(k) }
        return out
    }

    private fun strings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }

    private fun objects(a: JSONArray?): List<JSONObject> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotEmpty() }
    }
}
