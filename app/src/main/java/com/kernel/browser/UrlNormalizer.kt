package com.kernel.browser

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object UrlNormalizer {
    private val schemePattern = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val hostLikePattern = Regex(
        "^((localhost)|(\\[[0-9a-fA-F:]+])|([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,})(:\\d{1,5})?(/.*)?$"
    )
    private val ipv4Pattern = Regex("^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)(:\\d{1,5})?(/.*)?$")

    fun normalize(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return "https://www.google.com"
        }

        if (!trimmed.contains(' ') && (hostLikePattern.matches(trimmed) || ipv4Pattern.matches(trimmed))) {
            return "https://$trimmed"
        }

        if (schemePattern.containsMatchIn(trimmed)) {
            return trimmed
        }

        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
        return "https://www.google.com/search?q=$encoded"
    }
}
