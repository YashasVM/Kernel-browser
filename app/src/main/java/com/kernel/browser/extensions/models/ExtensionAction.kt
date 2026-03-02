package com.kernel.browser.extensions.models

data class ExtensionAction(
    val extensionId: String,
    val title: String?,
    val badgeText: String?,
    val badgeColor: Int?,
    val enabled: Boolean = true,
    val popupUrl: String? = null,
)
