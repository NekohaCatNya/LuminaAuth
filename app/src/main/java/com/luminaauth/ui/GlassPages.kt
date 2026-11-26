package com.luminaauth.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.luminaauth.AboutActivity
import com.luminaauth.AdvancedSettingsActivity
import com.luminaauth.AuthGlobalState
import com.luminaauth.AuthStatus
import com.luminaauth.AutoLoginService
import com.luminaauth.GatewayNotificationListener
import com.luminaauth.LogBuffer
import com.luminaauth.LoginService
import com.luminaauth.NativeBridge
import com.luminaauth.NetworkChannel
import com.luminaauth.PrefUtils
import com.luminaauth.RootUtils
import com.luminaauth.ShizukuUtils
import com.luminaauth.theme.ThemeSettingsActivity
import com.luminaauth.ui.glass.CollapsingPage
import com.luminaauth.ui.glass.PreferenceDropdown
import com.luminaauth.ui.glass.PreferenceSlider
import com.luminaauth.ui.glass.PreferenceSwitch
import com.luminaauth.ui.glass.SectionTitle
import com.luminaauth.ui.glass.SettingsActionWithArrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ==================== 主页（登录） ====================

@Composable
fun LoginPageGlass(
    username: String,
    password: String,
    rememberPwd: Boolean,
    autoAuth: Boolean,
    statusText: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRememberChange: (Boolean) -> Unit,
    onAutoAuthChange: (Boolean) -> Unit,
    onLogin: () -> Unit,
) {
    val onSurface = MiuixTheme.colorScheme.onSurface
    CollapsingPage(
        title = "LuminaAuth",
        subtitle = "连接校园网后自动完成认证",
    ) {
        item {
            SectionTitle("账号")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    TextField(
                        value = username,
                        onValueChange = onUsernameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = "用户名",
                    )
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = "密码",
                    )
                }
            }
        }
        item {
            SectionTitle("行为")
            Card(Modifier.fillMaxWidth()) {
                PreferenceSwitch(
                    title = "记住密码",
                    summary = "保存账号凭据，下次自动填充",
                    icon = null,
                    checked = rememberPwd,
                    onCheckedChange = onRememberChange,
                )
                PreferenceSwitch(
                    title = "自动认证",
                    summary = "连接校园网 WiFi 自动登录",
                    icon = null,
                    checked = autoAuth,
                    onCheckedChange = onAutoAuthChange,
                )
            }
        }
        item {
            Button(
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text("登录", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (statusText.isNotEmpty()) {
            item {
                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    color = onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ==================== 日志 ====================

@Composable
fun LogPageGlass(logVersion: Int) {
    val context = LocalContext.current
    val onSurface = MiuixTheme.colorScheme.onSurface
    val boxBg = MiuixTheme.colorScheme.surfaceContainer
    val logText = remember(logVersion) {
        val main = LogBuffer.getAllText()
        val detail = LogBuffer.getDetailAllText()
        if (detail.isBlank()) main else (main + "\n" + detail)
    }
    val scrollState = rememberScrollState()
    var autoScroll by remember { mutableStateOf(true) }
    LaunchedEffect(logVersion, autoScroll) {
        if (autoScroll) scrollState.scrollTo(scrollState.maxValue)
    }

    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    fun writeTo(uri: Uri, text: String) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (_: Exception) {
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) writeTo(uri, logText) }

    CollapsingPage(title = "日志") {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassChip(if (autoScroll) "暂停滚动" else "自动滚动", Modifier.weight(1f)) {
                    autoScroll = !autoScroll
                }
                GlassChip("导出", Modifier.weight(1f)) {
                    exportLauncher.launch("lumina_run_${stamp()}.log")
                }
                GlassChip("清空", Modifier.weight(1f)) {
                    LogBuffer.clear()
                    LogBuffer.clearDetail()
                }
            }
        }
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(560.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(boxBg)
                    .padding(12.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        logText,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        color = onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = MiuixTheme.colorScheme.primary
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, color = accent, fontWeight = FontWeight.Medium)
    }
}

// ==================== 权限行 ====================

@Composable
private fun GlassPermRow(
    label: String,
    desc: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    val onSurface = MiuixTheme.colorScheme.onSurface
    val secondary = MiuixTheme.colorScheme.onSurfaceContainerVariant
    val accent = MiuixTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, color = onSurface)
            Text(desc, fontSize = 12.sp, color = secondary)
        }
        Text(
            if (granted) "已授权" else "去授权",
            fontSize = 13.sp,
            color = if (granted) secondary else accent,
        )
    }
}

@Composable
private fun GlassDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(start = 18.dp)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
    )
}

// ==================== 设置 ====================

@Composable
fun SettingsPageGlass(
    autoAuth: Boolean,
    pollSec: Float,
    onPollChange: (Float) -> Unit,
    onAutoAuthChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var enhancedMode by remember { mutableStateOf(PrefUtils.isEnhancedModeEnabled(context)) }
    var detectMode by remember { mutableStateOf(PrefUtils.getDetectMode(context)) }
    val notifyDetect = detectMode == PrefUtils.MODE_NOTIFY
    var netChannel by remember {
        mutableStateOf(PrefUtils.getNetChannel(context).coerceIn(0, NetworkChannel.labels.lastIndex))
    }
    var waitSec by remember { mutableFloatStateOf(PrefUtils.getWaitTimeMs(context) / 1000f) }
    var hasRoot by remember { mutableStateOf(false) }
    var hasShizuku by remember { mutableStateOf(false) }
    val realVersion = remember {
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "v" + pi.versionName + " · VersionCode " + pi.longVersionCode
        } catch (_: Exception) {
            "v1.1.0"
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val root = RootUtils.isRootAvailable()
            val shizuku = ShizukuUtils.isReady()
            withContext(Dispatchers.Main) {
                hasRoot = root
                hasShizuku = shizuku
            }
        }
    }

    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val lifecycleOwner = LocalLifecycleOwner.current
    var permTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val locGranted = remember(permTick) { isLocationGrantedCompat(context) }
    val notifGranted = remember(permTick) { isNotificationGrantedCompat(context) }
    val battGranted = remember(permTick) { isBatteryOptimizationIgnoredCompat(context) }
    val bootGranted = remember(permTick) { isAutoStartGrantedCompat(context) }
    val notifyAccessGranted = remember(permTick) { GatewayNotificationListener.isAccessGranted(context) }

    LaunchedEffect(notifyAccessGranted) {
        if (!notifyAccessGranted && notifyDetect) {
            detectMode = PrefUtils.MODE_EVENT
            PrefUtils.setDetectMode(context, PrefUtils.MODE_EVENT)
            LogBuffer.add("UI", "notifyaccess=revoked detect=event")
            AutoLoginService.restartPolling(context)
            LogBuffer.addDetail("NTFY", "access=revoked detect=force_off")
        }
    }
    LaunchedEffect(notifyDetect, notifyAccessGranted) {
        if (notifyDetect && notifyAccessGranted && !GatewayNotificationListener.isListenerConnected()) {
            LogBuffer.addDetail("NTFY", "settings listener=0 act=rebind")
            GatewayNotificationListener.requestRebind(context)
        }
    }
    var listenerConnected by remember { mutableStateOf(GatewayNotificationListener.isListenerConnected()) }
    LaunchedEffect(notifyDetect, notifyAccessGranted, permTick) {
        while (notifyDetect && notifyAccessGranted) {
            listenerConnected = GatewayNotificationListener.isListenerConnected()
            delay(3000)
        }
        listenerConnected = GatewayNotificationListener.isListenerConnected()
    }

    val shizukuReqCode = 1001
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                enhancedMode = true
                PrefUtils.setEnhancedModeEnabled(context, true)
                AutoLoginService.restartPolling(context)
                ShizukuUtils.init(context)
                LogBuffer.add("UI", "enhanced=on via=shizuku")
                AutoLoginService.boostPriorityIfEnhanced(context)
            } else {
                enhancedMode = false
                PrefUtils.setEnhancedModeEnabled(context, false)
                AutoLoginService.restartPolling(context)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    CollapsingPage(title = "设置") {
        item {
            SectionTitle("权限")
            Card(Modifier.fillMaxWidth()) {
                GlassPermRow("定位权限", "识别校园网 WiFi 必需", locGranted) {
                    locLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                GlassDivider()
                GlassPermRow("通知权限", "后台认证状态提醒", notifGranted) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                GlassDivider()
                GlassPermRow("电池优化", "允许后台运行不被省电杀掉", battGranted) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                        try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:" + context.packageName),
                                )
                            )
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }
                }
                GlassDivider()
                GlassPermRow("自启动", "开机后自动运行认证服务", bootGranted) {
                    openAutoStartSettingsCompat(context)
                }
            }
        }

        item {
            SectionTitle("检测")
            Card(Modifier.fillMaxWidth()) {
                PreferenceSlider(
                    title = "检测速度",
                    summary = "两次检测之间的间隔",
                    icon = MiuixIcons.Refresh,
                    value = pollSec,
                    valueText = String.format(Locale.US, "%.1f 秒", pollSec),
                    valueRange = 0.5f..10f,
                    steps = 94,
                    onValueChange = onPollChange,
                    onValueChangeFinished = {},
                )
                PreferenceSlider(
                    title = "等待时间",
                    summary = if (notifyDetect) "通知检测模式已开启，此等待被跳过" else "网络就绪后、认证前的稳定等待",
                    icon = null,
                    value = waitSec,
                    valueText = if (notifyDetect) "0 秒" else String.format(Locale.US, "%.0f 秒", waitSec),
                    valueRange = 0f..15f,
                    steps = 14,
                    enabled = !notifyDetect,
                    onValueChange = { sec ->
                        waitSec = sec
                        PrefUtils.setWaitTimeMs(context, sec.roundToInt().toLong() * 1000L)
                    },
                    onValueChangeFinished = {},
                )
            }
        }

        item {
            SectionTitle("认证")
            Card(Modifier.fillMaxWidth()) {
                PreferenceSwitch(
                    title = "自动认证",
                    summary = "检测到校园网时自动登录",
                    icon = null,
                    checked = autoAuth,
                    onCheckedChange = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
                        onAutoAuthChange(it)
                    },
                )
                PreferenceSwitch(
                    title = when {
                        hasRoot -> "增强模式（root）"
                        hasShizuku -> "增强模式（Shizuku）"
                        else -> "增强模式（需 root 或 Shizuku）"
                    },
                    summary = "认证成功后催系统重评估网络并切到所选通道",
                    icon = null,
                    checked = enhancedMode,
                    onCheckedChange = { on ->
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
                        if (on) {
                            enhancedMode = true
                            scope.launch(Dispatchers.IO) {
                                val rootOk = RootUtils.isRootAvailable()
                                val shizukuReady = ShizukuUtils.isReady()
                                withContext(Dispatchers.Main) {
                                    when {
                                        rootOk -> {
                                            hasRoot = true
                                            PrefUtils.setEnhancedModeEnabled(context, true)
                                            AutoLoginService.restartPolling(context)
                                            LogBuffer.add("UI", "enhanced=on via=root")
                                            AutoLoginService.boostPriorityIfEnhanced(context)
                                        }
                                        shizukuReady -> {
                                            hasShizuku = true
                                            PrefUtils.setEnhancedModeEnabled(context, true)
                                            AutoLoginService.restartPolling(context)
                                            ShizukuUtils.init(context)
                                            LogBuffer.add("UI", "enhanced=on via=shizuku")
                                            AutoLoginService.boostPriorityIfEnhanced(context)
                                        }
                                        ShizukuUtils.isShizukuAvailable() -> {
                                            try {
                                                Shizuku.requestPermission(shizukuReqCode)
                                            } catch (e: Exception) {
                                                enhancedMode = false
                                                PrefUtils.setEnhancedModeEnabled(context, false)
                                                AutoLoginService.restartPolling(context)
                                                LogBuffer.add("UI", "enhanced shizuku grant_err=" + e.message)
                                            }
                                        }
                                        else -> {
                                            enhancedMode = false
                                            PrefUtils.setEnhancedModeEnabled(context, false)
                                            AutoLoginService.restartPolling(context)
                                        }
                                    }
                                }
                            }
                        } else {
                            enhancedMode = false
                            PrefUtils.setEnhancedModeEnabled(context, false)
                            AutoLoginService.restartPolling(context)
                            LogBuffer.add("UI", "enhanced=off")
                        }
                    },
                )
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                PreferenceDropdown(
                    title = "发送通道",
                    summary = "认证请求从哪个网卡出站",
                    icon = null,
                    items = NetworkChannel.labels,
                    selectedIndex = netChannel,
                    onSelectedIndexChange = {
                        netChannel = it
                        PrefUtils.setNetChannel(context, it)
                        val iface = NetworkChannel.applyChannel(it)
                        NativeBridge.nativeSetBindIface(iface)
                        LogBuffer.add(
                            "NET",
                            "CHANNEL: " + NetworkChannel.labels.getOrElse(it) { "na" } +
                                (if (iface.isEmpty()) " (failed)" else " iface=$iface"),
                        )
                    },
                )
                // 检测方式：与发送通道同属一个卡片
                PreferenceDropdown(
                    title = "检测方式",
                    summary = when {
                        enhancedMode -> "增强模式已开启：仍保留轮询兜底"
                        detectMode == PrefUtils.MODE_NOTIFY && !notifyAccessGranted ->
                            "未授予通知使用权，点按切换会跳转系统授权页"
                        detectMode == PrefUtils.MODE_NOTIFY && !listenerConnected ->
                            "监听未连接，切换回本项可请求重新绑定"
                        detectMode == PrefUtils.MODE_NOTIFY ->
                            "由通知栏推送触发认证，比轮询更快"
                        else ->
                            "网络变化时重新读取 SSID，命中目标列表才认证"
                    },
                    icon = null,
                    items = listOf("事件驱动", "通知检测"),
                    selectedIndex = if (detectMode == PrefUtils.MODE_NOTIFY) 1 else 0,
                    onSelectedIndexChange = { index ->
                        haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate
                        )
                        val mode = if (index == 1) PrefUtils.MODE_NOTIFY else PrefUtils.MODE_EVENT
                        detectMode = mode
                        PrefUtils.setDetectMode(context, mode)
                        LogBuffer.add("UI", "detect_mode=$mode")
                        if (mode == PrefUtils.MODE_NOTIFY) {
                            if (!notifyAccessGranted) {
                                // 没有通知使用权：先记下选择，再跳系统授权页
                                try {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                } catch (e: Exception) {
                                    LogBuffer.addDetail("NTFY", "open_settings err=" + e.message)
                                }
                            } else {
                                GatewayNotificationListener.requestRebind(context)
                            }
                        }
                        // 让前台服务重新装配触发源（事件驱动/轮询）
                        AutoLoginService.restartPolling(context)
                    },
                )
            }
        }

        item {
            SectionTitle("其他")
            Card(Modifier.fillMaxWidth()) {
                SettingsActionWithArrow("主题与配色", MiuixIcons.Theme, "深浅色模式与每类元素调色板") {
                    context.startActivity(Intent(context, ThemeSettingsActivity::class.java))
                }
                SettingsActionWithArrow("高级设置", MiuixIcons.Tune, null) {
                    context.startActivity(Intent(context, AdvancedSettingsActivity::class.java))
                }
                SettingsActionWithArrow("关于", MiuixIcons.Info, realVersion) {
                    context.startActivity(Intent(context, AboutActivity::class.java))
                }
            }
        }
    }
}

// ==================== 权限工具（原样保留） ====================

private fun openAutoStartSettingsCompat(context: Context) {
    try {
        context.startActivity(
            Intent().apply {
                component = android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                )
            }
        )
        return
    } catch (_: Exception) {
    }
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
        )
    } catch (_: Exception) {
    }
}

private fun isLocationGrantedCompat(context: Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun isNotificationGrantedCompat(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    return androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun isBatteryOptimizationIgnoredCompat(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun isAutoStartGrantedCompat(context: Context): Boolean {
    try {
        val miui = context.getSystemService("miui")
        if (miui != null) {
            val m = miui.javaClass.getMethod("isAppOnWhiteList", String::class.java)
            return m.invoke(miui, context.packageName) as? Boolean ?: false
        }
    } catch (_: Exception) {
    }
    return false
}
