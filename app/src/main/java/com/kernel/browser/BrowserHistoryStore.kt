package com.kernel.browser

import android.content.Context

class BrowserHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("browser_history", Context.MODE_PRIVATE)
    private var cache: List<Pair<String, String>>? = null

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
        preferences.edit().remove(KEY_ENTRIES).apply()
    }

    private fun save(entries: List<Pair<String, String>>) {
        cache = entries
        preferences.edit().putString(KEY_ENTRIES, BrowserHistoryCodec.encode(entries)).apply()
    }

    private companion object {
        const val KEY_ENTRIES = "entries"
    }
}
