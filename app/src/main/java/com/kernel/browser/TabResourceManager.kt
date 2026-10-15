package com.kernel.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.kernel.browser.tabs.BrowserTab
import com.kernel.browser.tabs.BrowserTabs
import com.kernel.browser.tabs.TabMemoryPolicy
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

/**
 * Keeps per-tab cost down the way Firefox and Chrome do: only the shown tab is active, background
 * tabs are throttled, the least recently used ones are unloaded to their saved state, and
 * unloaded (or lazily restored) tabs load again when they are next shown.
 */
class TabResourceManager(
    private val tabs: BrowserTabs,
    private val runtime: GeckoRuntime,
    private val isPlayingMedia: (BrowserTab) -> Boolean,
    /** Fullscreen, or still the tab extensions see as current: never unloaded. */
    private val mustStayLoaded: (BrowserTab) -> Boolean,
    private val loadHome: (BrowserTab) -> Unit
) {
    private var appVisible = true
    private val handler = Handler(Looper.getMainLooper())

    // GeckoView deactivates its session when the surface goes away, which happens after onStop;
    // apply the hidden-state decision again once that has run.
    private val reapplyHidden = Runnable { if (!appVisible) updateShownTabActivity() }

    /** Open tabs already told they are in the background, so switching doesn't resend it to all. */
    private val backgroundIds = mutableSetOf<Long>()

    /** Loads a tab that was restored lazily or unloaded. Returns false if it was already live. */
    fun ensureLoaded(tab: BrowserTab): Boolean {
        val pending = tab.pendingRestore ?: return false
        tab.pendingRestore = null
        backgroundIds -= tab.id
        if (!tab.session.isOpen) tab.session.open(runtime)
        val state = pending.state.takeIf { it.isNotBlank() }
            ?.let { runCatching { GeckoSession.SessionState.fromString(it) }.getOrNull() }
        when {
            state != null -> {
                // Keep the state saveable until Gecko reports its own.
                tab.sessionState = pending.state
                tab.sessionStateUrl = tab.url
                tab.session.restoreState(state)
            }
            pending.url.isBlank() -> loadHome(tab)
            else -> tab.session.loadUri(pending.url)
        }
        return true
    }

    /**
     * Closes a background tab's Gecko page, keeping its title, URL and saved state so it comes
     * back when shown. Returns false for the shown tab or one already unloaded.
     */
    fun unload(tab: BrowserTab): Boolean {
        if (tab == tabs.activeTab || tab.pendingRestore != null) return false
        tab.pendingRestore = BrowserTab.PendingRestore(tab.url, tab.restorableState())
        backgroundIds -= tab.id
        tab.isLoading = false
        tab.progress = 0
        if (tab.session.isOpen) runCatching { tab.session.close() }
        return true
    }

    /** Call after a tab is shown: throttles the others and unloads the oldest past the limit. */
    fun onTabAttached() {
        val active = tabs.activeTab
        val all = tabs.all()
        backgroundIds.retainAll(all.map { it.id }.toSet())
        all.forEach { if (it != active) sendToBackground(it) }
        if (active != null && active.session.isOpen) {
            backgroundIds -= active.id
            active.session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
            if (appVisible) active.session.setActive(true)
        }
        unloadOldTabs(TabMemoryPolicy.MAX_LIVE_BACKGROUND_TABS)
    }

    /** Marks a tab that isn't shown (e.g. opened in the background) as inactive and low priority. */
    fun sendToBackground(tab: BrowserTab) {
        if (tab == tabs.activeTab || !tab.session.isOpen || !backgroundIds.add(tab.id)) return
        tab.session.setActive(false)
        tab.session.setFocused(false)
        tab.session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
    }

    /**
     * The activity was stopped or started. While hidden the shown tab goes inactive too, unless
     * it is playing media, which keeps playing in the background.
     */
    fun onAppVisibilityChanged(visible: Boolean) {
        appVisible = visible
        updateShownTabActivity()
        handler.removeCallbacks(reapplyHidden)
        if (!visible) handler.post(reapplyHidden)
    }

    /** Media started or stopped somewhere; matters only while the app is hidden. */
    fun onMediaStateChanged() {
        if (!appVisible) updateShownTabActivity()
    }

    fun onTrimMemory(level: Int) {
        val keep = TabMemoryPolicy.backgroundTabsToKeep(level) ?: return
        unloadOldTabs(keep)
    }

    private fun updateShownTabActivity() {
        val active = tabs.activeTab?.takeIf { it.session.isOpen } ?: return
        active.session.setActive(appVisible || isPlayingMedia(active))
    }

    private fun unloadOldTabs(keepLoaded: Int) {
        val active = tabs.activeTab
        val background = tabs.all().filter { it != active }
        val candidates = background.map { tab ->
            TabMemoryPolicy.Candidate(
                id = tab.id,
                lastUsed = tab.lastUsed,
                loaded = tab.pendingRestore == null && tab.session.isOpen,
                pinned = isPlayingMedia(tab) || mustStayLoaded(tab)
            )
        }
        val ids = TabMemoryPolicy.tabsToUnload(candidates, keepLoaded).toSet()
        if (ids.isEmpty()) return
        background.filter { it.id in ids }.forEach { unload(it) }
    }

    companion object {
        /** Width of a tab thumbnail before the [TabMemoryPolicy.THUMBNAIL_MAX_WIDTH_PX] cap. */
        const val THUMBNAIL_WIDTH_DP = 180

        /**
         * A small opaque copy of the top of a page capture for the tab switcher: RGB_565 at card
         * size is about a tenth of the full-screen ARGB capture. Null if the capture is empty.
         */
        fun compactThumbnail(source: Bitmap, maxWidth: Int): Bitmap? {
            val size = TabMemoryPolicy.thumbnailSize(source.width, source.height, maxWidth) ?: return null
            val thumbnail = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.RGB_565)
            Canvas(thumbnail).drawBitmap(
                source,
                Rect(0, 0, source.width, size.sourceHeight),
                Rect(0, 0, size.width, size.height),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            )
            return thumbnail
        }
    }
}
