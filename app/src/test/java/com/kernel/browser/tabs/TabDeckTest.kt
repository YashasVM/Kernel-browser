package com.kernel.browser.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabDeckTest {
    @Test
    fun `add makes tab active`() {
        val deck = TabDeck()

        deck.add(TabRecord(1, TabMode.NORMAL))
        deck.add(TabRecord(2, TabMode.PRIVATE))

        assertEquals(2L, deck.activeId)
        assertEquals(2, deck.count())
    }

    @Test
    fun `closing active tab selects most recent normal tab first`() {
        val deck = TabDeck()
        deck.add(TabRecord(1, TabMode.NORMAL))
        deck.add(TabRecord(2, TabMode.PRIVATE))

        deck.remove(2)

        assertEquals(1L, deck.activeId)
        assertEquals(1, deck.count())
    }

    @Test
    fun `closing private tabs leaves normal tab active`() {
        val deck = TabDeck()
        deck.add(TabRecord(1, TabMode.NORMAL))
        deck.add(TabRecord(2, TabMode.PRIVATE))

        deck.closePrivateTabs()

        assertEquals(1L, deck.activeId)
        assertEquals(1, deck.count())
    }

    @Test
    fun `removing final tab clears active id`() {
        val deck = TabDeck()
        deck.add(TabRecord(1, TabMode.NORMAL))

        deck.remove(1)

        assertNull(deck.activeId)
        assertEquals(0, deck.count())
    }
}
