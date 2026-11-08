package com.kernel.browser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class HistoryGroupingTest {
    private val zone: ZoneId = ZoneOffset.UTC

    private fun millis(date: String, hour: Int = 12): Long =
        LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun entry(title: String, at: Long) = BrowserHistoryCodec.Entry(title, "https://$title.example/", at)

    @Test
    fun `groups visits into today, yesterday, and dated sections`() {
        val now = millis("2026-10-15", hour = 18)
        val entries = listOf(
            entry("a", millis("2026-10-15", 17)),
            entry("b", millis("2026-10-15", 1)),
            entry("c", millis("2026-10-14")),
            entry("d", millis("2026-10-12")),
            entry("e", millis("2025-12-31")),
            entry("f", 0L)
        )

        val sections = HistoryGrouping.group(entries, now, zone, Locale.US)

        assertEquals(
            listOf("Today", "Yesterday", "Monday, October 12", "December 31, 2025", HistoryGrouping.EARLIER),
            sections.map { it.label }
        )
        assertEquals(listOf("a", "b"), sections[0].entries.map { it.title })
        assertEquals(listOf("f"), sections.last().entries.map { it.title })
    }

    @Test
    fun `day boundaries follow the time zone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        // 2026-10-14T20:00Z is already Oct 15 in Tokyo.
        val visit = LocalDate.parse("2026-10-14").atTime(20, 0).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        val now = LocalDate.parse("2026-10-14").atTime(22, 0).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("Today", HistoryGrouping.group(listOf(entry("a", visit)), now, tokyo, Locale.US).single().label)
    }

    @Test
    fun `search matches every word in title or address`() {
        val entries = listOf(
            BrowserHistoryCodec.Entry("Kotlin docs", "https://kotlinlang.org/docs", 1L),
            BrowserHistoryCodec.Entry("Android Developers", "https://developer.android.com", 1L),
            BrowserHistoryCodec.Entry("Coroutines guide", "https://kotlinlang.org/coroutines", 1L)
        )
        assertEquals(3, HistoryGrouping.filterEntries(entries, "  ").size)
        assertEquals(listOf("Kotlin docs", "Coroutines guide"), HistoryGrouping.filterEntries(entries, "KOTLINLANG").map { it.title })
        assertEquals(listOf("Coroutines guide"), HistoryGrouping.filterEntries(entries, "guide kotlin").map { it.title })
        assertEquals(emptyList<BrowserHistoryCodec.Entry>(), HistoryGrouping.filterEntries(entries, "firefox"))
    }

    @Test
    fun `generic filter works for bookmark pairs`() {
        val bookmarks = listOf("News" to "https://news.example", "Mail" to "https://mail.example")
        assertEquals(listOf("Mail" to "https://mail.example"), HistoryGrouping.filter(bookmarks, "mail", { it.first }, { it.second }))
    }
}
