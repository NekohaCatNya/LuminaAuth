package com.luminaauth.ui.glass

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.glass.GlassDialog

/**
 * 官方 miuix GlassDialog 的统一薄封装：
 * 外观、圆角、高光、动效全部来自 miuix-glass，不在本地自绘。
 */
@Composable
internal fun GlassAlertDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // 先让 Dock 下沉，再弹对话框：避免玻璃 Dock 压在对话框上
    var rendered by remember { mutableStateOf(false) }
    LaunchedEffect(show) {
        if (show) {
            com.luminaauth.ui.glass.DockVisibility.update(true)
            delay(260)
            rendered = true
        } else {
            rendered = false
            com.luminaauth.ui.glass.DockVisibility.update(false)
        }
    }
    if (!rendered) return

    GlassDialog(
        show = show,
        title = title,
        summary = summary,
        backdrop = uiGlassBackdrop(),
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}
