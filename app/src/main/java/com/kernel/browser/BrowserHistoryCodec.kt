package com.kernel.browser

import java.nio.charset.StandardCharsets
import java.util.Base64

object BrowserHistoryCodec {
    const val MAX_ENTRIES = 100

    fun decode(raw: String?): List<Pair<String, String>> {
        return runCatching {
            raw.orEmpty()
                .lineSequence()
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size != 2) return@mapNotNull null
                    val title = decodeField(parts[0])
                    val url = decodeField(parts[1])
                    if (title.isNotBlank() && url.isNotBlank()) {
                        title to url
                    } else {
                        null
                    }
                }
                .take(MAX_ENTRIES)
                .toList()
        }.getOrElse { emptyList() }
    }

    fun encode(entries: List<Pair<String, String>>): String {
        return entries
            .take(MAX_ENTRIES)
            .filter { (title, url) -> title.isNotBlank() && url.isNotBlank() }
            .joinToString(separator = "\n") { (title, url) ->
                "${encodeField(title)}\t${encodeField(url)}"
            }
    }

    fun record(entries: List<Pair<String, String>>, title: String, url: String): List<Pair<String, String>> {
        if (url.isBlank()) return entries.take(MAX_ENTRIES)
        val entryTitle = title.ifBlank { url }
        return entries
            .filterNot { it.second == url }
            .toMutableList()
            .apply {
                add(0, entryTitle to url)
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
