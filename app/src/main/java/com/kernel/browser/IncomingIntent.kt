package com.kernel.browser

import java.util.Locale

/**
 * Turns what other apps send Kernel into a page to open: a link to view, text shared with
 * "Share > Kernel", a web search, or "Search with Kernel" from a text-selection menu.
 *
 * Pure Kotlin so it can be unit tested; [IncomingIntentReader] pulls the fields out of the Intent.
 * Only http(s) results are ever returned, so another app can't make Kernel run a javascript:,
 * file:, or data: URI.
 */
object IncomingIntent {
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_WEB_SEARCH = "android.intent.action.WEB_SEARCH"
    const val ACTION_SEARCH = "android.intent.action.SEARCH"
    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"

    /** Longer text is cut before searching; search engines reject huge queries anyway. */
    const val MAX_QUERY_LENGTH = 500

    private val embeddedUrl = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}>'\""

    /**
     * The URL to load for an intent with [action], [data] (its data URI string), and [text]
     * (the shared, selected, or search text), or null when the intent carries nothing to open.
     */
    fun resolve(action: String?, data: String?, text: String?, engine: SearchEngine): String? {
        return when (action) {
            ACTION_VIEW -> webUrl(data)
            ACTION_SEND -> fromText(text, engine, allowEmbeddedUrl = true)
            ACTION_WEB_SEARCH, ACTION_SEARCH, ACTION_PROCESS_TEXT ->
                fromText(text, engine, allowEmbeddedUrl = false) ?: webUrl(data)
            else -> null
        }
    }

    /** [value] itself if it is an http(s) URL with a host, otherwise null. */
    fun webUrl(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        val lower = trimmed.lowercase(Locale.ROOT)
        val rest = when {
            lower.startsWith("https://") -> trimmed.substring(8)
            lower.startsWith("http://") -> trimmed.substring(7)
            else -> return null
        }
        if (rest.isEmpty() || rest.first() == '/' || trimmed.any { it.isWhitespace() }) return null
        return trimmed
    }

    /**
     * Opens [text] if it is a web address (or, when [allowEmbeddedUrl], contains one, as in
     * "Article title https://example.com/a" shared from a news app); otherwise searches for it.
     */
    fun fromText(text: String?, engine: SearchEngine, allowEmbeddedUrl: Boolean): String? {
        val clean = text?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (clean.isEmpty()) return null

        webUrl(clean)?.let { return it }
        if (allowEmbeddedUrl) {
            embeddedUrl.find(clean)?.value?.trimEnd { it in TRAILING_PUNCTUATION }?.let(::webUrl)?.let { return it }
        }
        if (!clean.contains(' ')) {
            // "example.com" or "192.168.1.1:8080" open directly, like typing them in the address bar.
            val normalized = UrlNormalizer.normalize(clean, engine)
            if (normalized != engine.searchUrlFor(clean) && webUrl(normalized) != null) {
                return normalized
            }
        }
        return engine.searchUrlFor(clean.take(MAX_QUERY_LENGTH))
    }
}
