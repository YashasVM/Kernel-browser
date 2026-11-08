package com.kernel.browser

import java.util.Locale

/**
 * Decides which navigations Gecko should load and which belong to another Android app
 * (phone, mail, maps, Play Store, intent:// links from sites like Google Maps or Twitter).
 */
object ExternalLinks {
    enum class Kind {
        /** Loaded by Gecko as usual. */
        WEB,

        /** A well-known handler (dialer, mail, SMS, maps): open directly, the user tapped it. */
        DIRECT,

        /** An intent:// or app-specific scheme: confirm first, since sites can trigger these. */
        APP
    }

    private val geckoSchemes = setOf(
        "http", "https", "about", "data", "blob", "file", "javascript",
        "moz-extension", "resource", "chrome", "view-source", "ws", "wss"
    )

    private val directSchemes = setOf("tel", "mailto", "sms", "smsto", "mms", "mmsto", "geo", "sip")

    fun scheme(uri: String): String? {
        val colon = uri.indexOf(':')
        if (colon <= 0) return null
        val scheme = uri.substring(0, colon)
        if (!scheme.first().isLetter() || !scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        return scheme.lowercase(Locale.ROOT)
    }

    fun classify(uri: String): Kind {
        val scheme = scheme(uri) ?: return Kind.WEB
        return when (scheme) {
            in geckoSchemes -> Kind.WEB
            in directSchemes -> Kind.DIRECT
            else -> Kind.APP
        }
    }

    /**
     * The `S.browser_fallback_url` extra of an intent:// URI, if it is a web URL. Sites use it to
     * send people somewhere useful when the app is not installed.
     */
    fun fallbackUrl(intentUri: String): String? {
        val extras = intentUri.substringAfter("#Intent;", "")
        if (extras.isEmpty()) return null
        val raw = extras.split(';')
            .firstOrNull { it.startsWith("S.browser_fallback_url=") }
            ?.substringAfter('=')
            ?: return null
        val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: return null
        val lower = decoded.lowercase(Locale.ROOT)
        return decoded.takeIf { lower.startsWith("https://") || lower.startsWith("http://") }
    }
}
