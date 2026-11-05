package com.luminaauth.about

import androidx.compose.runtime.Immutable

@Immutable
data class AboutUiState(
    val title: String,
    val appName: String,
    val versionName: String,
    val infoItems: List<InfoItem>,
    val openSourceItems: List<OpenSourceItem>,
)

@Immutable
data class AboutScreenActions(
    val onBack: () -> Unit,
    val onOpenLink: (String) -> Unit,
)

@Immutable
data class InfoItem(
    val label: String,
    val value: String
)

@Immutable
data class OpenSourceItem(
    val name: String,
    val description: String,
    val url: String,
    val iconRes: Int = 0
)
