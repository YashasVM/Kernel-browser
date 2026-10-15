package com.kernel.browser

import android.content.Context

class BrowserHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("browser_history", Context.MODE_PRIVATE)
    private var cache: List<Pair<String, String>>? = null
    // Pages report each visit several times (location, title, load); write off the UI thread.
    private val writer = LatestPreferenceWrite(preferences, KEY_ENTRIES)

    fun entries(): List<Pair<String, String>> {
        return cache ?: BrowserHistoryCodec.decode(preferences.getString(KEY_ENTRIES, "[]")).also { cache = it }
    }

    fun record(title: String, url: String) {
        val current = entries()
        // Title, location, and load-finished callbacks all report the same visit; only write once.
        if (current.firstOrNull() == (title.ifBlank { url } to url)) return
        save(BrowserHistoryCodec.record(current, title, url))
    }

    fun clear() {
        cache = emptyList()
        writer.remove()
    }

    private fun save(entries: List<Pair<String, String>>) {
        cache = entries
        writer.put { BrowserHistoryCodec.encode(entries) }
    }

    private companion object {
        const val KEY_ENTRIES = "entries"
    }
}
