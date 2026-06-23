package com.kernel.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserHistoryCodecTest {
    @Test
    fun `records newest entry first and dedupes by url`() {
        val initial = listOf("Old title" to "https://example.com", "Other" to "https://other.test")

        val updated = BrowserHistoryCodec.record(initial, "New title", "https://example.com")

        assertEquals("New title" to "https://example.com", updated.first())
        assertEquals(2, updated.size)
    }

    @Test
    fun `uses url as title fallback`() {
        val updated = BrowserHistoryCodec.record(emptyList(), "", "https://example.com")

        assertEquals("https://example.com" to "https://example.com", updated.single())
    }

    @Test
    fun `limits history to max entries`() {
        val entries = (0..120).map { "Title $it" to "https://example.com/$it" }

        val encoded = BrowserHistoryCodec.encode(entries)
        val decoded = BrowserHistoryCodec.decode(encoded)

        assertEquals(BrowserHistoryCodec.MAX_ENTRIES, decoded.size)
    }

    @Test
    fun `ignores corrupt history json`() {
        assertTrue(BrowserHistoryCodec.decode("{not-json").isEmpty())
    }
}
