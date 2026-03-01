package com.kernel.browser.utils

import android.util.Patterns

object UrlUtils {

    fun isUrl(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.contains(" ")) return false
        if (trimmed.startsWith("about:")) return true
        if (trimmed.startsWith("file://")) return true
        if (Patterns.WEB_URL.matcher(trimmed).matches()) return true
        if (trimmed.contains(".") && !trimmed.contains(" ")) {
            return Patterns.WEB_URL.matcher("https://$trimmed").matches()
        }
        return false
    }

    fun toLoadableUrl(input: String, searchEngine: String = "https://duckduckgo.com/?q=%s"): String {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("about:") || trimmed.startsWith("file://") -> trimmed
            isUrl(trimmed) -> "https://$trimmed"
            else -> searchEngine.replace("%s", java.net.URLEncoder.encode(trimmed, "UTF-8"))
        }
    }

    fun displayUrl(url: String): String {
        return url
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')
    }

    fun extractDomain(url: String): String {
        return try {
            java.net.URI(url).host?.removePrefix("www.") ?: url
        } catch (_: Exception) {
            url
        }
    }
}
