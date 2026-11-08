package com.kernel.browser.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabMemoryPolicyTest {
    private fun candidate(id: Long, lastUsed: Long, loaded: Boolean = true, pinned: Boolean = false) =
        TabMemoryPolicy.Candidate(id, lastUsed, loaded, pinned)

    @Test
    fun `nothing unloads while under the limit`() {
        val tabs = (1L..5L).map { candidate(it, it) }

        assertEquals(emptyList<Long>(), TabMemoryPolicy.tabsToUnload(tabs, 5))
    }

    @Test
    fun `least recently used tabs unload first`() {
        val tabs = listOf(candidate(1, 10), candidate(2, 3), candidate(3, 7), candidate(4, 1))

        assertEquals(listOf(2L, 4L), TabMemoryPolicy.tabsToUnload(tabs, 2))
    }

    @Test
    fun `pinned and already unloaded tabs are skipped and not counted`() {
        val tabs = listOf(
            candidate(1, 1, pinned = true),
            candidate(2, 2, loaded = false),
            candidate(3, 3),
            candidate(4, 4)
        )

        assertEquals(listOf(3L), TabMemoryPolicy.tabsToUnload(tabs, 1))
        assertEquals(listOf(4L, 3L), TabMemoryPolicy.tabsToUnload(tabs, 0))
        assertEquals(listOf(4L, 3L), TabMemoryPolicy.tabsToUnload(tabs, -1))
    }

    @Test
    fun `trim levels map to how many background tabs stay`() {
        assertEquals(3, TabMemoryPolicy.backgroundTabsToKeep(TabMemoryPolicy.TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(1, TabMemoryPolicy.backgroundTabsToKeep(TabMemoryPolicy.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(0, TabMemoryPolicy.backgroundTabsToKeep(TabMemoryPolicy.TRIM_MEMORY_RUNNING_CRITICAL))
        assertNull(TabMemoryPolicy.backgroundTabsToKeep(TabMemoryPolicy.TRIM_MEMORY_UI_HIDDEN))
        assertEquals(1, TabMemoryPolicy.backgroundTabsToKeep(TabMemoryPolicy.TRIM_MEMORY_BACKGROUND))
        assertEquals(0, TabMemoryPolicy.backgroundTabsToKeep(TabMemoryPolicy.TRIM_MEMORY_MODERATE))
        assertEquals(0, TabMemoryPolicy.backgroundTabsToKeep(TabMemoryPolicy.TRIM_MEMORY_COMPLETE))
        assertNull(TabMemoryPolicy.backgroundTabsToKeep(0))
    }

    @Test
    fun `thumbnails are dropped only under real pressure`() {
        assertFalse(TabMemoryPolicy.shouldDropThumbnails(TabMemoryPolicy.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(TabMemoryPolicy.shouldDropThumbnails(TabMemoryPolicy.TRIM_MEMORY_BACKGROUND))
        assertFalse(TabMemoryPolicy.shouldDropThumbnails(TabMemoryPolicy.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(TabMemoryPolicy.shouldDropThumbnails(TabMemoryPolicy.TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(TabMemoryPolicy.shouldDropThumbnails(TabMemoryPolicy.TRIM_MEMORY_MODERATE))
        assertTrue(TabMemoryPolicy.shouldDropThumbnails(TabMemoryPolicy.TRIM_MEMORY_COMPLETE))
    }

    @Test
    fun `portrait capture keeps the top of the page at a capped width`() {
        // 540 asked for, but THUMBNAIL_MAX_WIDTH_PX (480) caps it.
        val size = TabMemoryPolicy.thumbnailSize(1080, 2200, 540)!!

        assertEquals(480, size.width)
        assertEquals(1188, size.sourceHeight)
        assertEquals(528, size.height)
    }

    @Test
    fun `landscape capture uses the whole height`() {
        val size = TabMemoryPolicy.thumbnailSize(2000, 900, 400)!!

        assertEquals(400, size.width)
        assertEquals(900, size.sourceHeight)
        assertEquals(180, size.height)
    }

    @Test
    fun `thumbnail width never exceeds the hard cap or the source`() {
        assertEquals(TabMemoryPolicy.THUMBNAIL_MAX_WIDTH_PX, TabMemoryPolicy.thumbnailSize(1440, 3000, 2000)!!.width)
        assertEquals(300, TabMemoryPolicy.thumbnailSize(300, 600, 540)!!.width)
    }

    @Test
    fun `empty captures have no thumbnail`() {
        assertNull(TabMemoryPolicy.thumbnailSize(0, 100, 100))
        assertNull(TabMemoryPolicy.thumbnailSize(100, 0, 100))
        assertNull(TabMemoryPolicy.thumbnailSize(100, 100, 0))
    }
}
