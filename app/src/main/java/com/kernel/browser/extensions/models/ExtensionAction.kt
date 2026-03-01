package com.kernel.browser.extensions.models

import android.graphics.Bitmap

data class ExtensionAction(
    val extensionId: String,
    val title: String?,
    val icon: Bitmap?,
    val badgeText: String?,
    val badgeColor: Int?,
    val enabled: Boolean = true,
    val popupUrl: String? = null,
)
