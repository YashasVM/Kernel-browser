package com.kernel.browser.extensions.models

import android.graphics.Bitmap

data class KernelExtension(
    val id: String,
    val name: String,
    val description: String,
    val version: String = "",
    val icon: Bitmap? = null,
    val iconUrl: String? = null,
    val isEnabled: Boolean = true,
    val isInstalled: Boolean = false,
    val isBuiltIn: Boolean = false,
    val hasPopup: Boolean = false,
    val popupUrl: String? = null,
    val amoUrl: String? = null,
)
