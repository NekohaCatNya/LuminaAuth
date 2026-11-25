package com.luminaauth.ui.glass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Dock 下沉信号：只在“弹对话框 / 打开子页面”时置为 true。
 * 页面切换（主页/日志/设置）不再影响 Dock。
 */
object DockVisibility {
    var hidden: Boolean by mutableStateOf(false)
        private set

    @JvmStatic
    fun update(value: Boolean) {
        hidden = value
    }
}
