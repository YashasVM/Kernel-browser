package com.kernel.browser.tabs

import org.mozilla.geckoview.GeckoSession

class BrowserTab(
    val id: Long,
    val session: GeckoSession,
    val mode: TabMode
) {
    var title: String = ""
    var url: String = ""
    var isLoading: Boolean = false
    var progress: Int = 0
    var canGoBack: Boolean = false
    var canGoForward: Boolean = false
    var isSecure: Boolean = false
    var securityHost: String = ""
    var sessionState: String = ""

    val isPrivate: Boolean
        get() = mode == TabMode.PRIVATE

    fun label(): String {
        val privacyPrefix = if (isPrivate) "Private: " else ""
        val text = title.ifBlank { url.ifBlank { "New tab" } }
        return privacyPrefix + text
    }
}
