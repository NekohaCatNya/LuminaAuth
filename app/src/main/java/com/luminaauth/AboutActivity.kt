package com.luminaauth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.luminaauth.abouteffect.LocalEnableBlur
import com.luminaauth.about.AboutScreenMiuix
import com.luminaauth.about.AboutScreenActions
import com.luminaauth.about.AboutUiState
import com.luminaauth.about.InfoItem
import com.luminaauth.about.OpenSourceItem
import com.luminaauth.theme.LuminaAuthTheme

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LuminaAuthTheme {
                CompositionLocalProvider(LocalEnableBlur provides true) {
                    val pkgInfo = packageManager.getPackageInfo(packageName, 0)
                    val realVersionCode = pkgInfo.longVersionCode
                    val realVersionName = pkgInfo.versionName
                    val state = AboutUiState(
                        title = "关于应用",
                        appName = "LuminaAuth",
                        versionName = "v$realVersionName · VersionCode $realVersionCode",
                        infoItems = listOf(
                            InfoItem("版本", "v$realVersionName (vc$realVersionCode)"),
                            InfoItem("最低系统", "Android 10 (API 29)"),
                            InfoItem("编译 SDK", "Android 16 (API 36)"),
                            InfoItem("技术栈", "Compose + Miuix"),
                            InfoItem("提权方式", "Root / Shizuku"),
                            InfoItem("开源协议", "GPL-3.0-or-later"),
                        ),
                        openSourceItems = listOf(
                            OpenSourceItem(
                                "Compose-MIUIX",
                                "界面的卡片、按钮与滑动条都由它提供",
                                "https://github.com/compose-miuix-ui/miuix",
                                R.drawable.ic_miuix
                            ),
                            OpenSourceItem(
                                "Shizuku",
                                "免 Root 提权，让后台服务跑在系统权限下",
                                "https://github.com/RikkaApps/Shizuku",
                                R.drawable.ic_shizuku
                            ),
                            OpenSourceItem(
                                "AndroidLiquidGlass",
                                "底部 Dock 的液态玻璃质感效果出自这里",
                                "https://github.com/Kyant0/AndroidLiquidGlass",
                                R.drawable.ic_liquid_glass
                            ),
                        )
                    )
                    val actions = AboutScreenActions(
                        onBack = { finish() },
                        onOpenLink = { url ->
                            if (url.isNotEmpty()) {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    )
                    AboutScreenMiuix(state = state, actions = actions)
                }
            }
        }
    }
}
