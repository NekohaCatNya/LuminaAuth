package com.luminaauth.plugin

/**
 * Static data model for a YAML-defined auth plugin.
 * A plugin is pure data: there is no embedded code, so loading it cannot
 * execute anything. Runtime behaviour is fully determined by this model and
 * enforced by native plugin_engine.
 */
data class Plugin(
    val id: String,
    val name: String,
    val version: Int,
    val description: String,
    /** Substring patterns matched against the current SSID. Empty = any network. */
    val ssidPatterns: List<String>,
    /** Allow-list of host[:port] every plugin request must resolve to. */
    val hosts: List<String>,
    val fields: List<FieldDef>,
    val isps: List<IspDef>,
    val check: CheckDef?,
    val loginSteps: List<StepDef>,
    val source: PluginSource,
    val fileName: String,
    /** 全局默认请求头；具体 check / step 里的同名头优先级更高。 */
    val headers: Map<String, String> = emptyMap(),
    val timing: TimingDef = TimingDef(),
    val policy: NetworkPolicyDef = NetworkPolicyDef(),
    val detect: DetectDef = DetectDef(),
) {
    companion object {
        const val FIELD_USERNAME = "username"
        const val FIELD_PASSWORD = "password"
    }
}

enum class PluginSource { BUILTIN, EXTERNAL }

/** 认证时序：把原来写死在代码里的等待参数交给插件声明。 */
data class TimingDef(
    /** IP 就绪轮询步长（毫秒）。 */
    val ipPollMs: Long = 250L,
    /** 等待 IP 就绪的上限（毫秒）。 */
    val maxIpWaitMs: Long = 3000L,
    /** 通知检测模式触发时，直接跳过“等待网络就绪”整段。 */
    val skipNetworkReadyOnNotify: Boolean = true,
    /** 内网 Portal 的连接超时，别用 8 秒白等。 */
    val connectTimeoutMs: Int = 6000,
    val readTimeoutMs: Int = 6000,
)

/** 通道与出口策略。 */
data class NetworkPolicyDef(
    /** 认证成功后把本进程流量绑定到所选通道。 */
    val bindProcess: Boolean = true,
    /** 认证成功后请求系统对所选通道重评估（克制版，带冷却）。 */
    val promoteNetwork: Boolean = true,
    /** 增强模式 + root 时，一次性把默认网络切到所选通道（不做守护）。 */
    val rootSetDefault: Boolean = true,
    /** 优先网卡名（如 wlan0 / rmnet0 / eth0），为空则按传输类型挑。 */
    val preferredIfaces: List<String> = emptyList(),
    /** 认证/检测请求强制走当前 WiFi 网卡（内网 Portal 只能从 wlan0 可达）。 */
    val authOverWifi: Boolean = true,
)

/** 检测与重试。 */
data class DetectDef(
    val retryCount: Int = 3,
    val retryDelayMs: Long = 5000L,
)

enum class FieldType { TEXT, PASSWORD }

data class FieldDef(
    val id: String,
    val label: String,
    val type: FieldType,
    val required: Boolean,
)

/**
 * ISP identity offered by the plugin. [id] is the account suffix spliced into
 * the request (e.g. "after"); [label] is shown in the dock bar; [default]
 * marks the entry pre-selected by the ISP segmented dock.
 */
data class IspDef(
    val id: String,
    val label: String,
    val default: Boolean = false,
) {
    /** [label] 的别名：分段 Dock 的 options 文本统一取这里。 */
    val name: String get() = label
}

data class CheckDef(
    val method: String,
    val url: String,
    val onlineContains: String?,
    val offlineContains: String?,
    val headers: Map<String, String> = emptyMap(),
    /** 原始 request 块里的请求体（GET 通常为空）。 */
    val body: String? = null,
)

data class StepDef(
    val name: String,
    val method: String,
    val url: String,
    val expectStatus: Int?,
    val success: MatchDef?,
    val alreadyOnline: MatchDef?,
    val captures: List<CaptureDef>,
    val failMessageJsonPath: String?,
    val headers: Map<String, String> = emptyMap(),
    /** 原始 request 块里的请求体；POST 表单直接写在这里。 */
    val body: String? = null,
)

data class MatchDef(
    val bodyContains: String?,
    val bodyRegex: String?,
    val jsonPath: String?,
    val jsonEquals: String?,
)

data class CaptureDef(
    val name: String,
    val jsonPath: String?,
    val regex: String?,
    val group: Int,
)
