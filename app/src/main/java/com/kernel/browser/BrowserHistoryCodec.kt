package com.kernel.browser

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * One line per page: base64url(title) TAB base64url(url) [TAB visitedAtMillis]. The time field
 * was added later; lines without it still decode, with [Entry.visitedAt] = 0 ("unknown").
 */
object BrowserHistoryCodec {
    const val MAX_ENTRIES = 100

    data class Entry(val title: String, val url: String, val visitedAt: Long = 0L) {
        fun toPair(): Pair<String, String> = title to url
    }

    fun decode(raw: String?): List<Pair<String, String>> = decodeEntries(raw).map { it.toPair() }

    fun encode(entries: List<Pair<String, String>>): String =
        encodeEntries(entries.map { (title, url) -> Entry(title, url) })

    fun record(entries: List<Pair<String, String>>, title: String, url: String): List<Pair<String, String>> {
        return recordEntry(entries.map { (t, u) -> Entry(t, u) }, title, url, visitedAt = 0L).map { it.toPair() }
    }

    fun decodeEntries(raw: String?): List<Entry> {
        return runCatching {
            raw.orEmpty()
                .lineSequence()
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size != 2 && parts.size != 3) return@mapNotNull null
                    val title = decodeField(parts[0])
                    val url = decodeField(parts[1])
                    val visitedAt = parts.getOrNull(2)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    if (title.isNotBlank() && url.isNotBlank()) {
                        Entry(title, url, visitedAt)
                    } else {
                        null
                    }
                }
                .take(MAX_ENTRIES)
                .toList()
        }.getOrElse { emptyList() }
    }

    fun encodeEntries(entries: List<Entry>): String {
        return entries
            .take(MAX_ENTRIES)
            .filter { it.title.isNotBlank() && it.url.isNotBlank() }
            .joinToString(separator = "\n") { entry ->
                val fields = "${encodeField(entry.title)}\t${encodeField(entry.url)}"
                if (entry.visitedAt > 0L) "$fields\t${entry.visitedAt}" else fields
            }
    }

    /** Moves (or adds) [url] to the top with [visitedAt], dropping the oldest past [MAX_ENTRIES]. */
    fun recordEntry(entries: List<Entry>, title: String, url: String, visitedAt: Long): List<Entry> {
        if (url.isBlank()) return entries.take(MAX_ENTRIES)
        val entryTitle = title.ifBlank { url }
        return entries
            .filterNot { it.url == url }
            .toMutableList()
            .apply {
                add(0, Entry(entryTitle, url, visitedAt))
                while (size > MAX_ENTRIES) {
                    removeAt(lastIndex)
                }
            }
    }

    private fun encodeField(value: String): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeField(value: String): String {
        return String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }
}
