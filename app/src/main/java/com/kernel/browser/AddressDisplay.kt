package com.kernel.browser

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * What the address bar shows while you are not editing it: the site's host for web pages, and
 * your search terms on a results page, the way Safari does. Editing always shows the full URL.
 */
object AddressDisplay {
    fun format(url: String, engine: SearchEngine): String {
        if (url.isBlank()) return ""
        searchTerms(url, engine)?.let { return it }
        if (url.startsWith("about:reader?", ignoreCase = true)) {
            // about: URIs are opaque to java.net.URI, so read the query by hand.
            val inner = query(url.substringAfter('?'))["url"]
            return inner?.let { format(it, engine) } ?: url
        }
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return url
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return url
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return url
        return if (uri.port >= 0) "$host:${uri.port}" else host
    }

    /** The query typed into [engine] if [url] is that engine's results page, otherwise null. */
    fun searchTerms(url: String, engine: SearchEngine): String? {
        val template = runCatching { URI(engine.searchUrl.replace("%s", "QUERY")) }.getOrNull() ?: return null
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (!uri.host.equals(template.host, ignoreCase = true)) return null
        if ((uri.path ?: "").trimEnd('/') != (template.path ?: "").trimEnd('/')) return null
        val name = query(template.rawQuery).entries.firstOrNull { it.value == "QUERY" }?.key ?: return null
        return query(uri.rawQuery)[name]?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            val key = part.substringBefore('=')
            if (key.isEmpty()) return@mapNotNull null
            decode(key) to decode(part.substringAfter('=', ""))
        }.toMap()
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}
