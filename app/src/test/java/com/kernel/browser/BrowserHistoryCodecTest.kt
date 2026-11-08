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

    @Test
    fun `visit times round trip`() {
        val entries = listOf(
            BrowserHistoryCodec.Entry("New", "https://new.example", 1_760_000_000_000L),
            BrowserHistoryCodec.Entry("Old", "https://old.example", 0L)
        )

        assertEquals(entries, BrowserHistoryCodec.decodeEntries(BrowserHistoryCodec.encodeEntries(entries)))
    }

    @Test
    fun `history saved before visit times decodes with unknown time`() {
        val legacy = BrowserHistoryCodec.encode(listOf("Title" to "https://example.com"))

        val decoded = BrowserHistoryCodec.decodeEntries(legacy).single()

        assertEquals(BrowserHistoryCodec.Entry("Title", "https://example.com", 0L), decoded)
    }

    @Test
    fun `pair api still reads lines that carry a visit time`() {
        val encoded = BrowserHistoryCodec.encodeEntries(listOf(BrowserHistoryCodec.Entry("T", "https://t.example", 42L)))

        assertEquals(listOf("T" to "https://t.example"), BrowserHistoryCodec.decode(encoded))
    }

    @Test
    fun `recording an entry stamps it and moves it to the top`() {
        val initial = listOf(
            BrowserHistoryCodec.Entry("A", "https://a.example", 1L),
            BrowserHistoryCodec.Entry("B", "https://b.example", 2L)
        )

        val updated = BrowserHistoryCodec.recordEntry(initial, "B again", "https://b.example", 99L)

        assertEquals(listOf("https://b.example", "https://a.example"), updated.map { it.url })
        assertEquals(BrowserHistoryCodec.Entry("B again", "https://b.example", 99L), updated.first())
    }
}
