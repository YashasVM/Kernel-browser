package com.kernel.browser

import android.content.Context

class BrowserHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("browser_history", Context.MODE_PRIVATE)
    private var cache: List<BrowserHistoryCodec.Entry>? = null
    private var pairs: List<Pair<String, String>>? = null
    // Pages report each visit several times (location, title, load); write off the UI thread.
    private val writer = LatestPreferenceWrite(preferences, KEY_ENTRIES)

    /** Newest first, as (title, url). */
    fun entries(): List<Pair<String, String>> {
        return pairs ?: timedEntries().map { it.toPair() }.also { pairs = it }
    }

    /** Newest first, with visit times (0 for visits recorded before times were kept). */
    fun timedEntries(): List<BrowserHistoryCodec.Entry> {
        return cache ?: BrowserHistoryCodec.decodeEntries(preferences.getString(KEY_ENTRIES, "")).also { cache = it }
    }

    fun record(title: String, url: String) {
        val current = timedEntries()
        // Title, location, and load-finished callbacks all report the same visit; only write once.
        val first = current.firstOrNull()
        if (first != null && first.url == url && first.title == title.ifBlank { url }) return
        save(BrowserHistoryCodec.recordEntry(current, title, url, System.currentTimeMillis()))
    }

    fun remove(url: String) {
        val current = timedEntries()
        if (current.none { it.url == url }) return
        save(current.filterNot { it.url == url })
    }

    fun clear() {
        cache = emptyList()
        pairs = emptyList()
        writer.remove()
    }

    private fun save(entries: List<BrowserHistoryCodec.Entry>) {
        cache = entries
        pairs = null
        // [entries] is a fresh list nobody mutates, so encoding it on the writer thread is safe.
        writer.put { BrowserHistoryCodec.encodeEntries(entries) }
    }

    private companion object {
        const val KEY_ENTRIES = "entries"
    }
}
