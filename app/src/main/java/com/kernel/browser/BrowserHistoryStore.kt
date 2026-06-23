package com.kernel.browser

import android.content.Context

class BrowserHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("browser_history", Context.MODE_PRIVATE)

    fun entries(): List<Pair<String, String>> {
        return BrowserHistoryCodec.decode(preferences.getString(KEY_ENTRIES, "[]"))
    }

    fun record(title: String, url: String) {
        save(BrowserHistoryCodec.record(entries(), title, url))
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
