package com.kernel.browser

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Search and day sections for the History page ("Today", "Yesterday", "Monday, October 12"). */
object HistoryGrouping {
    const val EARLIER = "Earlier"

    data class Section(val label: String, val entries: List<BrowserHistoryCodec.Entry>)

    /** Entries whose title or address contains every word of [query], ignoring case. */
    fun <T> filter(items: List<T>, query: String, title: (T) -> String, url: (T) -> String): List<T> {
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return items
        return items.filter { item ->
            val haystack = (title(item) + " " + url(item)).lowercase(Locale.ROOT)
            words.all { haystack.contains(it) }
        }
    }

    fun filterEntries(entries: List<BrowserHistoryCodec.Entry>, query: String): List<BrowserHistoryCodec.Entry> =
        filter(entries, query, { it.title }, { it.url })

    /**
     * Splits newest-first [entries] into consecutive day sections. Visits with no recorded time
     * (from before times were kept) go into a final "Earlier" section.
     */
    fun group(
        entries: List<BrowserHistoryCodec.Entry>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault()
    ): List<Section> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val dated = LinkedHashMap<LocalDate, MutableList<BrowserHistoryCodec.Entry>>()
        val undated = mutableListOf<BrowserHistoryCodec.Entry>()
        entries.forEach { entry ->
            if (entry.visitedAt <= 0L) {
                undated += entry
            } else {
                val day = Instant.ofEpochMilli(entry.visitedAt).atZone(zone).toLocalDate()
                dated.getOrPut(day) { mutableListOf() }.add(entry)
            }
        }
        val sections = dated.entries
            .sortedByDescending { it.key }
            .map { (day, items) -> Section(dayLabel(day, today, locale), items) }
        return if (undated.isEmpty()) sections else sections + Section(EARLIER, undated)
    }

    fun dayLabel(day: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String {
        return when {
            day == today -> "Today"
            day == today.minusDays(1) -> "Yesterday"
            day.year == today.year -> DateTimeFormatter.ofPattern("EEEE, MMMM d", locale).format(day)
            else -> DateTimeFormatter.ofPattern("MMMM d, yyyy", locale).format(day)
        }
    }
}
