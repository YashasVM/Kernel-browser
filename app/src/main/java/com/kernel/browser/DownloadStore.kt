package com.kernel.browser

import android.content.Context
import java.nio.charset.StandardCharsets
import java.util.Base64

class DownloadStore(context: Context) {
    private val preferences = context.getSharedPreferences("browser_downloads", Context.MODE_PRIVATE)

    data class Entry(
        val id: Long,
        val title: String,
        val url: String,
        val fileName: String
    )

    fun entries(): List<Entry> {
        return DownloadStoreCodec.decode(preferences.getString(KEY_ENTRIES, ""))
    }

    fun record(id: Long, title: String, url: String, fileName: String) {
        if (url.isBlank()) return
        val entry = Entry(id, title.ifBlank { fileName.ifBlank { url } }, url, fileName)
        val updated = entries()
            .filterNot { it.id == id || it.url == url }
            .toMutableList()
            .apply { add(0, entry) }
        save(updated)
    }

    fun remove(id: Long) {
        save(entries().filterNot { it.id == id })
    }

    fun clear() {
        preferences.edit().remove(KEY_ENTRIES).apply()
    }

    private fun save(entries: List<Entry>) {
        val payload = DownloadStoreCodec.encode(entries)
        preferences.edit().putString(KEY_ENTRIES, payload).apply()
    }

    private companion object {
        const val KEY_ENTRIES = "entries"
    }
}

internal object DownloadStoreCodec {
    const val MAX_ENTRIES = 100
    private const val FIELD_COUNT = 4

    fun encode(entries: List<DownloadStore.Entry>): String {
        return entries
            .take(MAX_ENTRIES)
            .joinToString(separator = "\n", transform = ::encodeEntry)
    }

    fun decode(raw: String?): List<DownloadStore.Entry> {
        return raw.orEmpty()
            .lineSequence()
            .mapNotNull(::decodeEntry)
            .take(MAX_ENTRIES)
            .toList()
    }

    private fun encodeEntry(entry: DownloadStore.Entry): String {
        return listOf(
            entry.id.toString(),
            entry.title,
            entry.url,
            entry.fileName
        ).joinToString(separator = "\t") { encodeDownloadField(it) }
    }

    private fun decodeEntry(line: String): DownloadStore.Entry? {
        val parts = line.split('\t')
        if (parts.size == 2) {
            val title = decodeDownloadField(parts[0])
            val url = decodeDownloadField(parts[1])
            return DownloadStore.Entry(-1L, title, url, title)
        }
        if (parts.size != FIELD_COUNT) return null
        val values = parts.map(::decodeDownloadField)
        val id = values[0].toLongOrNull() ?: -1L
        val title = values[1]
        val url = values[2]
        val fileName = values[3]
        if (url.isBlank()) return null
        return DownloadStore.Entry(id, title.ifBlank { fileName.ifBlank { url } }, url, fileName)
    }
}

private fun encodeDownloadField(value: String): String {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}

private fun decodeDownloadField(value: String): String {
    return runCatching {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }.getOrDefault("")
}
