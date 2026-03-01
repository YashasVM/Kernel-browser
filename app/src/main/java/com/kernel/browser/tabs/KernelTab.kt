package com.kernel.browser.tabs

import android.graphics.Bitmap

data class KernelTab(
    val id: String,
    val title: String = "New Tab",
    val url: String = "about:blank",
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val thumbnail: Bitmap? = null,
)
