package com.kernel.browser

import android.content.Context
import java.nio.charset.StandardCharsets
import java.util.Base64

class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("browser_sessions", Context.MODE_PRIVATE)

    data class Entry(
        val title: String,
        val url: String,
        val state: String
    )

    fun restore(): List<Entry> {
        return SessionStoreCodec.decode(preferences.getString(KEY_NORMAL_TABS, ""))
    }

    fun save(entries: List<Entry>) {
        val payload = SessionStoreCodec.encode(entries)
        preferences.edit().putString(KEY_NORMAL_TABS, payload).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_NORMAL_TABS).apply()
    }

    private companion object {
        const val KEY_NORMAL_TABS = "normal_tabs"
    }
}

internal object SessionStoreCodec {
    const val MAX_TABS = 50

    fun encode(entries: List<SessionStore.Entry>): String {
        return entries
            .filter { it.url.isNotBlank() || it.state.isNotBlank() }
            .take(MAX_TABS)
            .joinToString(separator = "\n") { entry ->
                "${encodeSessionField(entry.title)}\t${encodeSessionField(entry.url)}\t${encodeSessionField(entry.state)}"
            }
    }

    fun decode(raw: String?): List<SessionStore.Entry> {
        return raw.orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 3) return@mapNotNull null
                val title = decodeSessionField(parts[0])
                val url = decodeSessionField(parts[1])
                val state = decodeSessionField(parts[2])
                if (url.isBlank() && state.isBlank()) null else SessionStore.Entry(title, url, state)
            }
            .take(MAX_TABS)
            .toList()
    }
}

private fun encodeSessionField(value: String): String {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}

private fun decodeSessionField(value: String): String {
    return runCatching {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }.getOrDefault("")
}
