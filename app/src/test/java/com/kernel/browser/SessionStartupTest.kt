package com.kernel.browser

import org.junit.Assert.*
import org.junit.Test

class SessionStartupTest {
    @Test fun restoresEverySavedTabBeforeExternalLinkCanSaveTheSession() {
        val live = mutableListOf<String>()
        val selected = mutableListOf<String>()
        var savedAtIncomingOpen = emptyList<String>()
        SessionStartup.restore(
            savedTabs = listOf("saved-a", "saved-b"),
            hasIncomingLink = true,
            home = { error("Must not create a home tab") },
            restoreTab = { entry, select -> live += entry; if (select) selected += entry },
            openIncomingLink = {
                live += "incoming"
                savedAtIncomingOpen = live.toList()
            }
        )
        assertEquals(listOf("saved-a", "saved-b", "incoming"), savedAtIncomingOpen)
        assertTrue(selected.isEmpty())
    }

    @Test fun anExternalLaunchWithoutSavedTabsCreatesOnlyTheIncomingTab() {
        val live = mutableListOf<String>()
        SessionStartup.restore(
            emptyList(), true, { error("Unexpected home") },
            { entry: String, _ -> live += entry }, { live += "incoming" }
        )
        assertEquals(listOf("incoming"), live)
    }

    @Test fun ordinaryLaunchRestoresAndSelectsLastSavedTab() {
        val restored = mutableListOf<Pair<String, Boolean>>()
        SessionStartup.restore(
            listOf("a", "b"), false, { error("Unexpected home") },
            { entry, selected -> restored += entry to selected }, { error("Unexpected incoming link") }
        )
        assertEquals(listOf("a" to false, "b" to true), restored)
    }

    @Test fun emptyOrdinaryLaunchCreatesAndSelectsHome() {
        val restored = mutableListOf<Pair<String, Boolean>>()
        SessionStartup.restore(
            emptyList(), false, { "home" },
            { entry, selected -> restored += entry to selected }, { error("Unexpected incoming link") }
        )
        assertEquals(listOf("home" to true), restored)
    }
}
