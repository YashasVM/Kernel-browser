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

    /** The [url] the tab was on when [sessionState] was captured. */
    var sessionStateUrl: String = ""

    /** The tab that opened this one (window.open, target=_blank); Back returns there. */
    var parentId: Long? = null

    /**
     * Set while the tab has no live Gecko page: restored lazily at startup or unloaded to save
     * memory. The tab loads this when it is next shown; [title] and [url] stay valid meanwhile.
     */
    var pendingRestore: PendingRestore? = null

    /** Bumped whenever the tab is created or selected; the least recently used unload first. */
    var lastUsed: Long = 0L

    val isUnloaded: Boolean
        get() = pendingRestore != null

    val isPrivate: Boolean
        get() = mode == TabMode.PRIVATE

    /** [sessionState] when it belongs to [url]; blank for the start page or a stale capture. */
    fun restorableState(): String = if (url.isBlank() || sessionStateUrl != url) "" else sessionState

    fun label(): String {
        val privacyPrefix = if (isPrivate) "Private: " else ""
        val text = title.ifBlank { url.ifBlank { "New tab" } }
        return privacyPrefix + text
    }

    /** What an unloaded tab reloads: [state] if set, else [url], else (blank url) the start page. */
    data class PendingRestore(
        val url: String,
        val state: String
    )
}
