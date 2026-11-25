package com.luminaauth.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

@Composable
internal fun PreferenceSwitch(
    title: String,
    summary: String?,
    icon: ImageVector?,
    checked: Boolean,
    enabled: Boolean = true,
    insideMargin: PaddingValues = SettingsItemMargin,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        enabled = enabled,
        startAction = icon?.let { image -> { SettingsIcon(image) } },
        insideMargin = insideMargin,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
internal fun PreferenceDropdown(
    title: String,
    summary: String?,
    icon: ImageVector?,
    items: List<String>,
    selectedIndex: Int,
    enabled: Boolean = true,
    insideMargin: PaddingValues = SettingsItemMargin,
    onSelectedIndexChange: (Int) -> Unit,
) {
    // 直接调用 HyperIsland 同款官方组件 GlassDropdownPreference，不再自绘
    top.yukonga.miuix.kmp.glass.GlassDropdownPreference(
        title = title,
        summary = summary,
        items = items,
        selectedIndex = selectedIndex,
        backdrop = uiGlassBackdrop(),
        enabled = enabled,
        renderInRootScaffold = false,
        startAction = icon?.let { image -> { SettingsIcon(image) } },
        insideMargin = insideMargin,
        onSelectedIndexChange = onSelectedIndexChange,
    )
}

@Composable
internal fun PreferenceSlider(
    title: String,
    summary: String? = null,
    icon: ImageVector?,
    value: Float,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    showKeyPoints: Boolean = false,
    keyPoints: List<Float>? = null,
    resetVisible: Boolean = false,
    enabled: Boolean = true,
    onReset: (() -> Unit)? = null,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    var showInputDialog by remember { mutableStateOf(false) }

    SliderPreference(
        title = title,
        summary = summary,
        value = value,
        valueText = valueText.takeIf { onReset == null },
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        showKeyPoints = showKeyPoints,
        keyPoints = keyPoints,
        endActions = onReset?.let {
            {
                SliderResetAction(
                    valueText = valueText,
                    visible = resetVisible,
                    onClick = it,
                )
            }
        },
        startAction = icon?.let { image -> { SettingsIcon(image) } },
        insideMargin = SettingsItemMargin,
        onClick = { showInputDialog = true },
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
    )

    SliderValueInputDialog(
        show = showInputDialog,
        title = title,
        value = value,
        valueRange = valueRange,
        steps = steps,
        onDismiss = { showInputDialog = false },
        onSave = { newValue ->
            onValueChange(newValue)
            onValueChangeFinished()
            showInputDialog = false
        },
    )
}

@Composable
internal fun SliderValueInputDialog(
    show: Boolean,
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onDismiss: () -> Unit,
    onSave: (Float) -> Unit,
) {
    val decimalPlaces = remember(valueRange, steps) { sliderDecimalPlaces(valueRange, steps) }
    var input by remember(show, value, decimalPlaces) {
        mutableStateOf(formatSliderValue(value, decimalPlaces))
    }
    val parsedValue = input.trim().replace(',', '.').toFloatOrNull()
    val validValue = parsedValue?.takeIf { it.isFinite() && it in valueRange }
    val rangeText = "范围 " + formatSliderValue(valueRange.start, decimalPlaces) +
        " ~ " + formatSliderValue(valueRange.endInclusive, decimalPlaces)

    fun saveIfValid() {
        validValue?.let { onSave(snapSliderValue(it, valueRange, steps)) }
    }

    GlassAlertDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = input,
                onValueChange = { candidate ->
                    if (candidate.isValidDecimalInput()) input = candidate
                },
                modifier = Modifier.fillMaxWidth(),
                label = "输入数值",
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { saveIfValid() }),
            )
            Text(
                text = rangeText,
                color = if (input.isNotBlank() && validValue == null) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                fontSize = MiuixTheme.textStyles.body2.fontSize,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = ::saveIfValid,
                    enabled = validValue != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("保存")
                }
            }
        }
    }
}

private fun String.isValidDecimalInput(): Boolean {
    if (isEmpty()) return true
    var separatorSeen = false
    forEachIndexed { index, character ->
        when {
            character.isDigit() -> Unit
            character == '-' && index == 0 -> Unit
            (character == '.' || character == ',') && !separatorSeen -> separatorSeen = true
            else -> return false
        }
    }
    return true
}

private fun snapSliderValue(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    val coerced = value.coerceIn(valueRange)
    if (steps <= 0) return coerced
    val interval = (valueRange.endInclusive - valueRange.start) / (steps + 1)
    if (!interval.isFinite() || interval <= 0f) return coerced
    return (valueRange.start + round((coerced - valueRange.start) / interval) * interval)
        .coerceIn(valueRange)
}

private fun sliderDecimalPlaces(
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Int {
    if (steps <= 0) return 2
    val interval = (valueRange.endInclusive - valueRange.start) / (steps + 1)
    for (decimals in 0..4) {
        val scale = 10.0.pow(decimals)
        if (abs(interval * scale - round(interval * scale)) < 0.0001) return decimals
    }
    return 4
}

private fun formatSliderValue(value: Float, decimalPlaces: Int): String =
    String.format(Locale.ROOT, "%.${decimalPlaces}f", value)

@Composable
internal fun SliderResetAction(
    valueText: String? = null,
    visible: Boolean,
    alignToSliderEnd: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.offset(x = if (alignToSliderEnd) 8.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (valueText != null) {
            Text(
                text = valueText,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (visible) {
                IconButton(
                    onClick = onClick,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = "恢复默认",
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}
