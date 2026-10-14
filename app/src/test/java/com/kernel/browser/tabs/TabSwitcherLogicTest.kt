package com.kernel.browser.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSwitcherLogicTest {
    @Test
    fun displayHostStripsSchemeWwwPathAndPort() {
        assertEquals("example.com", TabSwitcherLogic.displayHost("https://www.example.com/a/b?c=d#e"))
        assertEquals("news.ycombinator.com", TabSwitcherLogic.displayHost("http://news.ycombinator.com:8080/item"))
        assertEquals("example.com", TabSwitcherLogic.displayHost("https://user:pw@Example.COM/"))
        assertEquals("[::1]", TabSwitcherLogic.displayHost("http://[::1]:3000/"))
    }

    @Test
    fun displayHostKeepsNonHierarchicalUrls() {
        assertEquals("", TabSwitcherLogic.displayHost("  "))
        assertEquals("about:blank", TabSwitcherLogic.displayHost("about:blank"))
        assertEquals("data:", TabSwitcherLogic.displayHost("data:text/html,<b>hi</b>"))
    }

    @Test
    fun displayTitleFallsBack() {
        assertEquals("Docs", TabSwitcherLogic.displayTitle(" Docs ", "https://x.org", false))
        assertEquals("x.org", TabSwitcherLogic.displayTitle("", "https://x.org", false))
        assertEquals("New tab", TabSwitcherLogic.displayTitle("", "", false))
        assertEquals("Private tab", TabSwitcherLogic.displayTitle("", "", true))
    }

    @Test
    fun columnsScaleWithWidth() {
        assertEquals(2, TabSwitcherLogic.columnsFor(360))
        assertEquals(3, TabSwitcherLogic.columnsFor(700))
        assertEquals(4, TabSwitcherLogic.columnsFor(1000))
    }

    @Test
    fun dismissNeedsDistanceOrMatchingFling() {
        assertFalse(TabSwitcherLogic.shouldDismiss(30f, 100))
        assertTrue(TabSwitcherLogic.shouldDismiss(35f, 100))
        assertTrue(TabSwitcherLogic.shouldDismiss(-40f, 100))
        assertFalse(TabSwitcherLogic.shouldDismiss(50f, 0))
        assertTrue(TabSwitcherLogic.shouldDismiss(10f, 100, velocityX = 2000f, flingThreshold = 1000f))
        assertFalse(TabSwitcherLogic.shouldDismiss(10f, 100, velocityX = -2000f, flingThreshold = 1000f))
        assertFalse(TabSwitcherLogic.shouldDismiss(10f, 100, velocityX = 500f, flingThreshold = 1000f))
    }

    @Test
    fun dragAlphaFadesAndClamps() {
        assertEquals(1f, TabSwitcherLogic.dragAlpha(0f, 100), 0.001f)
        assertEquals(0.625f, TabSwitcherLogic.dragAlpha(-50f, 100), 0.001f)
        assertEquals(0.25f, TabSwitcherLogic.dragAlpha(500f, 100), 0.001f)
    }

    @Test
    fun horizontalDragNeedsSlopAndDominantAxis() {
        assertFalse(TabSwitcherLogic.isHorizontalDrag(5f, 0f, 8))
        assertTrue(TabSwitcherLogic.isHorizontalDrag(20f, 5f, 8))
        assertFalse(TabSwitcherLogic.isHorizontalDrag(20f, 18f, 8))
    }
}
