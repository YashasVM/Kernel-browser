package com.kernel.browser

/** Restores the saved session before an external launch can cause it to be saved again. */
internal object SessionStartup {
    fun <T> restore(
        savedTabs: List<T>,
        hasIncomingLink: Boolean,
        home: () -> T,
        restoreTab: (entry: T, select: Boolean) -> Unit,
        openIncomingLink: () -> Unit
    ) {
        val entries = if (savedTabs.isEmpty() && !hasIncomingLink) listOf(home()) else savedTabs
        entries.forEachIndexed { index, entry ->
            restoreTab(entry, !hasIncomingLink && index == entries.lastIndex)
        }
        if (hasIncomingLink) openIncomingLink()
    }
}
