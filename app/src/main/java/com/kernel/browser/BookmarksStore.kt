package com.kernel.browser

import android.content.Context

class BookmarksStore(context: Context) {
    private val preferences = context.getSharedPreferences("browser_bookmarks", Context.MODE_PRIVATE)

    fun entries(): List<Pair<String, String>> {
        return BrowserHistoryCodec.decode(preferences.getString(KEY_ENTRIES, ""))
    }

    fun isBookmarked(url: String): Boolean {
        return url.isNotBlank() && entries().any { it.second == url }
    }

    fun add(title: String, url: String) {
        if (url.isBlank()) return
        val entryTitle = title.ifBlank { url }
        save(BrowserHistoryCodec.record(entries(), entryTitle, url))
    }

    fun remove(url: String) {
        save(entries().filterNot { it.second == url })
    }

    fun toggle(title: String, url: String): Boolean {
        return if (isBookmarked(url)) {
            remove(url)
            false
        } else {
            add(title, url)
            true
        }
    }

    fun clear() {
        preferences.edit().remove(KEY_ENTRIES).apply()
    }

    private fun save(entries: List<Pair<String, String>>) {
        preferences.edit().putString(KEY_ENTRIES, BrowserHistoryCodec.encode(entries)).apply()
    }

    private companion object {
        const val KEY_ENTRIES = "entries"
    }
}
