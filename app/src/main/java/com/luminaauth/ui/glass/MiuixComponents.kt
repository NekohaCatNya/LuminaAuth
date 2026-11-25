package com.luminaauth.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.SolidColor
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun CollapsingPage(
    title: String,
    subtitle: String = "",
    actionIcon: ImageVector? = null,
    actionDescription: String = "",
    onAction: (() -> Unit)? = null,
    snackbarHost: @Composable () -> Unit = {},
    horizontalContentPadding: Dp = 16.dp,
    topContentPadding: Dp = 8.dp,
    bottomContentPadding: Dp = 28.dp,
    overlay: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurEnabled = LocalBarBlurEnabled.current
    BarBlurHost(enabled = blurEnabled) {
        Scaffold(
            topBar = {
                BlurredBar(topGradient = true) {
                    TopAppBar(
                        title = title,
                        largeTitle = title,
                        subtitle = subtitle,
                        color = Color.Transparent,
                        scrollBehavior = scrollBehavior,
                        actions = {
                            if (actionIcon != null && onAction != null) {
                                IconButton(onClick = onAction) {
                                    Icon(actionIcon, actionDescription)
                                }
                            }
                        },
                    )
                }
            },
            snackbarHost = snackbarHost,
        ) { padding ->
            BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    PageList(
                        padding = padding,
                        scrollBehavior = scrollBehavior,
                        horizontalContentPadding = horizontalContentPadding,
                        topContentPadding = topContentPadding,
                        bottomContentPadding = bottomContentPadding,
                        content = content,
                    )
                    overlay()
                }
            }
        }
    }
}

@Composable
private fun GlassPageTopBar(
    title: String,
    scrollBehavior: ScrollBehavior,
    onBack: () -> Unit,
    actionIcon: ImageVector? = null,
    actionDescription: String = "",
    onAction: (() -> Unit)? = null,
) {
    val backdrop = uiGlassBackdrop()
    val scrolled = remember(scrollBehavior) {
        derivedStateOf { scrollBehavior.state.contentOffset < 0f }
    }
    GlassTopAppBar(
        title = title,
        largeTitle = title,
        isContentScrolled = scrolled.value,
        modifier = if (backdrop == null) {
            Modifier.background(GlassTopAppBarDefaults.bandBrush(MiuixTheme.colorScheme.surface))
        } else Modifier,
        progressiveBlur = backdrop != null,
        backdrop = backdrop,
        bandBrush = SolidColor(Color.Transparent),
        bandOverhang = 0.dp,
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            GlassIconButton(onClick = onBack) {
                Icon(MiuixIcons.Back, "返回")
            }
        },
        actions = {
            if (actionIcon != null && onAction != null) {
                GlassIconButton(onClick = onAction) {
                    Icon(actionIcon, actionDescription)
                }
            }
        },
    )
}

@Composable
internal fun DetailPage(
    title: String,
    onBack: () -> Unit,
    actionIcon: ImageVector? = null,
    actionDescription: String = "",
    onAction: (() -> Unit)? = null,
    snackbarHost: @Composable () -> Unit = {},
    overlay: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurEnabled = LocalBarBlurEnabled.current
    BarBlurHost(enabled = blurEnabled) {
        Scaffold(
            topBar = {
                GlassPageTopBar(
                    title = title,
                    scrollBehavior = scrollBehavior,
                    onBack = onBack,
                    actionIcon = actionIcon,
                    actionDescription = actionDescription,
                    onAction = onAction,
                )
            },
            snackbarHost = snackbarHost,
        ) { padding ->
            BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    PageList(
                        padding = padding,
                        scrollBehavior = scrollBehavior,
                        content = content,
                    )
                    overlay()
                }
            }
        }
    }
}

@Composable
private fun PageList(
    padding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    horizontalContentPadding: Dp = 16.dp,
    topContentPadding: Dp = 8.dp,
    bottomContentPadding: Dp = 28.dp,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(
            start = horizontalContentPadding,
            top = padding.calculateTopPadding() + topContentPadding,
            end = horizontalContentPadding,
            bottom = bottomContentPadding + LocalRootBottomBarPadding.current,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun SectionTitle(title: String) {
    SmallTitle(
        text = title,
        modifier = Modifier.padding(top = 4.dp),
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
    )
}

@Composable
internal fun SettingsAction(
    title: String,
    icon: ImageVector? = null,
    summary: String? = null,
    endIcon: ImageVector? = null,
    endIconSize: Dp = 20.dp,
    endContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        startAction = icon?.let { image -> { SettingsIcon(image) } },
        endActions = {
            if (endContent != null) {
                endContent()
            } else if (endIcon != null) {
                Icon(
                    imageVector = endIcon,
                    contentDescription = null,
                    modifier = Modifier.size(endIconSize),
                    tint = if (enabled) {
                        MiuixTheme.colorScheme.onSurfaceVariantActions
                    } else {
                        MiuixTheme.colorScheme.disabledOnSurface
                    },
                )
            }
        },
        insideMargin = SettingsItemMargin,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
internal fun SettingsActionWithArrow(
    title: String,
    icon: ImageVector,
    summary: String? = null,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        startAction = { SettingsIcon(icon) },
        endActions = {
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(width = 10.dp, height = 16.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        },
        insideMargin = SettingsItemMargin,
        onClick = onClick,
    )
}

@Composable
internal fun SettingsIcon(imageVector: ImageVector) {
    Icon(
        modifier = Modifier.padding(end = 16.dp),
        imageVector = imageVector,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onBackground,
    )
}

internal val SettingsItemMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp)


/** HyperIsland 同款高光玻璃圆形按钮（顶部栏返回 / 刷新用） */
@Composable
internal fun GlassCircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 64.dp,
) {
    val scheme = MiuixTheme.colorScheme
    val isDark = scheme.surface.luminance() < 0.5f
    // 与 HyperIsland 一致：一颗实心白玻璃圆，细白边 + 柔和大阴影，图标用 onSurface
    Box(
        modifier = modifier
            .size(diameter)
            .shadow(10.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(
                if (isDark) Color(0xFF2C2C2C).copy(alpha = 0.60f)
                else Color.White.copy(alpha = 0.94f),
            )
            .border(
                1.dp,
                if (isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.92f),
                CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            tint = scheme.onSurface,
        )
    }
}

