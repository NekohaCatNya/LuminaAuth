#pragma once

#include <string>

namespace lumina {

// 解析并校验一份 YAML 插件，成功时把插件对象留在原生注册表并返回描述 JSON。
// 失败时返回 {"ok":false,"error":"..."}，Kotlin 侧只负责异常展示。
std::string pluginLoadJson(const std::string& yaml,
                           const std::string& fileName,
                           const std::string& source);

// 执行 check / login。pluginId 必须来自 pluginLoadJson 成功的插件。
// stateJson 只携带运行期变量，插件数据留在 C++，不来回穿过 JNI。
std::string pluginCheckJson(const std::string& pluginId, const std::string& stateJson);
std::string pluginLoginJson(const std::string& pluginId, const std::string& stateJson);

} // namespace lumina
