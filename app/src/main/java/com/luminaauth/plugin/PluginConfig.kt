package com.luminaauth.plugin

/**
 * 插件解析失败时抛出的可读异常。真正的 YAML 解析与校验在 C++ plugin_engine.cpp。
 */
class PluginParseException(message: String) : IllegalArgumentException(message)
