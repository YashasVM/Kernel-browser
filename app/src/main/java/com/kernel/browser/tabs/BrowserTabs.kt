package com.kernel.browser.tabs

import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

class BrowserTabs(
    private val runtime: GeckoRuntime,
    private val configureSession: (BrowserTab) -> Unit
) {
    private val deck = TabDeck()
    private val tabs = linkedMapOf<Long, BrowserTab>()
    private var nextId = 1L

    var activeTab: BrowserTab? = null
        private set

    fun create(mode: TabMode, open: Boolean = true): BrowserTab {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(mode == TabMode.PRIVATE)
            .build()
        val tab = BrowserTab(nextId++, GeckoSession(settings), mode)
        tabs[tab.id] = tab
        deck.add(TabRecord(tab.id, mode))
        configureSession(tab)
        if (open) {
            tab.session.open(runtime)
        }
        activeTab = tab
        return tab
    }

    fun select(id: Long): BrowserTab? {
        return tabs[id]?.also { activeTab = it }
    }

    fun close(id: Long): BrowserTab? {
        val tab = tabs.remove(id) ?: return activeTab
        runCatching { tab.session.close() }
        val newActiveId = deck.remove(id)
        activeTab = newActiveId?.let { tabs[it] }
        return activeTab
    }

    fun closePrivateTabs() {
        tabs.values.filter { it.isPrivate }.forEach { tab ->
            runCatching { tab.session.close() }
            tabs.remove(tab.id)
        }
        deck.closePrivateTabs()
        activeTab = deck.activeId?.let { tabs[it] }
    }

    fun all(): List<BrowserTab> = tabs.values.toList()

    fun count(): Int = deck.count()
}
