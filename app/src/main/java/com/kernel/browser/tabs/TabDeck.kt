package com.kernel.browser.tabs

data class TabRecord(
    val id: Long,
    val mode: TabMode
)

class TabDeck {
    private val normal = mutableListOf<TabRecord>()
    private val privateTabs = mutableListOf<TabRecord>()

    var activeId: Long? = null
        private set

    fun add(tab: TabRecord): TabRecord {
        listFor(tab.mode).add(tab)
        activeId = tab.id
        return tab
    }

    fun remove(id: Long): Long? {
        val removedIndex = normal.indexOfFirst { it.id == id }.takeIf { it >= 0 }
            ?: privateTabs.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        val removedMode = normal.firstOrNull { it.id == id }?.mode
            ?: privateTabs.firstOrNull { it.id == id }?.mode
        if (removedIndex == null || removedMode == null) return activeId

        listFor(removedMode).removeAt(removedIndex)
        if (activeId == id) {
            activeId = normal.lastOrNull()?.id ?: privateTabs.lastOrNull()?.id
        }
        return activeId
    }

    fun closePrivateTabs() {
        privateTabs.clear()
        if (activeId != null && normal.none { it.id == activeId }) {
            activeId = normal.lastOrNull()?.id
        }
    }

    fun records(): List<TabRecord> = normal + privateTabs

    fun count(): Int = normal.size + privateTabs.size

    private fun listFor(mode: TabMode): MutableList<TabRecord> {
        return if (mode == TabMode.PRIVATE) privateTabs else normal
    }
}
