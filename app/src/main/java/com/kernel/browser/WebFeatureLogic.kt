package com.kernel.browser

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Pure helpers behind the media notification, picture-in-picture, home screen shortcuts,
 * text-selection search, and page translation. Kept free of Android types so they can be
 * unit tested on the JVM.
 */
object WebFeatureLogic {
    /**
     * Android rejects picture-in-picture aspect ratios wider than 2.39:1 or taller than 1:2.39.
     * Stay just inside that range so float rounding can never push us over it.
     */
    const val PIP_MAX_RATIO = 2.38
    private const val MAX_SELECTION_QUERY = 1_000
    private const val MAX_SHORT_LABEL = 24

    /**
     * Picture-in-picture aspect ratio for a video of [width] x [height], as a (numerator,
     * denominator) pair already clamped to what Android accepts, or null when the size is unknown.
     */
    fun pipAspectRatio(width: Long, height: Long): Pair<Int, Int>? {
        if (width <= 0L || height <= 0L) return null
        val ratio = width.toDouble() / height.toDouble()
        if (ratio > PIP_MAX_RATIO) return 238 to 100
        if (ratio < 1.0 / PIP_MAX_RATIO) return 100 to 238
        var w = width
        var h = height
        val divisor = gcd(w, h)
        w /= divisor
        h /= divisor
        // Keep both terms comfortably inside Int range; the ratio barely changes.
        while (w > 10_000L || h > 10_000L) {
            w = (w / 2).coerceAtLeast(1L)
            h = (h / 2).coerceAtLeast(1L)
        }
        return w.toInt() to h.toInt()
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    /** The host of [url] without "www.", or null for URLs without one (data:, about:, ...). */
    fun displayHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = runCatching { URI(url.trim()).host }.getOrNull() ?: return null
        return host.removePrefix("www.").takeIf { it.isNotBlank() }
    }

    /** Media notification title: the page's media metadata, then the page title, then the host. */
    fun mediaTitle(metadataTitle: String?, pageTitle: String?, url: String?, isPrivate: Boolean): String {
        if (isPrivate) return "Private tab"
        return metadataTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: pageTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: displayHost(url)
            ?: "Media"
    }

    /** Media notification subtitle: artist (and album when known), otherwise the site. */
    fun mediaSubtitle(artist: String?, album: String?, url: String?, isPrivate: Boolean): String {
        if (isPrivate) return "Playing media"
        val parts = listOfNotNull(
            artist?.trim()?.takeIf { it.isNotEmpty() },
            album?.trim()?.takeIf { it.isNotEmpty() }
        )
        if (parts.isNotEmpty()) return parts.joinToString(" · ")
        return displayHost(url) ?: "Kernel"
    }

    /** True for the pages a launcher shortcut can reopen (MainActivity only handles http/https VIEW). */
    fun canPinShortcut(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        return (value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)) &&
            displayHost(value) != null
    }

    /** The short launcher label: the page title (trimmed to fit) or the host. */
    fun shortcutLabel(title: String?, url: String?): String {
        val text = title?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
            ?: displayHost(url)
            ?: "Kernel"
        return if (text.length <= MAX_SHORT_LABEL) text else text.take(MAX_SHORT_LABEL - 1).trimEnd() + "…"
    }

    /** The letter drawn on a generated shortcut icon: first letter or digit of the host. */
    fun shortcutLetter(title: String?, url: String?): String {
        val source = displayHost(url) ?: title.orEmpty()
        return source.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "K"
    }

    /** A stable shortcut id per page, so pinning the same page twice updates one shortcut. */
    fun shortcutId(url: String): String = "page-" + Integer.toHexString(url.trim().hashCode())

    /** Selected page text cleaned up for a web search, or null when there is nothing to search. */
    fun selectionQuery(text: String?): String? {
        val query = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (query.isEmpty()) return null
        return query.take(MAX_SELECTION_QUERY)
    }

    /** A search URL for [query] built from a "%s" template such as SearchEngine.searchUrl. */
    fun searchUrl(template: String, query: String): String =
        template.format(URLEncoder.encode(query, StandardCharsets.UTF_8.name()))

    /** The primary subtag of a BCP 47 tag, lowercased ("pt-BR" -> "pt"), or null when blank. */
    fun primaryLanguage(tag: String?): String? =
        tag?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase()?.takeIf { it.isNotEmpty() }

    /** True when translating [from] into [to] would change nothing (same primary language). */
    fun isSameLanguage(from: String?, to: String?): Boolean {
        val a = primaryLanguage(from) ?: return false
        return a == primaryLanguage(to)
    }
}
