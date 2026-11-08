package com.kernel.browser

import java.net.URI
import java.util.Locale

/**
 * Validation for the user's own search engine. A template must be an http(s) URL whose query
 * string carries the search terms as a whole parameter value (`...?q=%s`), which is what the
 * start page's search form and the address bar's search-terms display both rely on.
 */
object CustomSearchEngines {
    const val MAX_NAME_LENGTH = 40
    const val MAX_TEMPLATE_LENGTH = 2048

    /** Placeholders other browsers use for the query, accepted and rewritten to `%s`. */
    private val alternatePlaceholders = listOf("{searchTerms}", "%S")

    sealed class Result {
        data class Valid(val engine: SearchEngine) : Result()
        data class Invalid(val message: String) : Result()
    }

    fun validate(name: String, template: String): Result {
        val cleanName = name.trim().replace(Regex("\\s+"), " ")
        if (cleanName.isEmpty()) return Result.Invalid("Enter a name.")
        if (cleanName.length > MAX_NAME_LENGTH) return Result.Invalid("Use a shorter name.")

        var url = template.trim()
        alternatePlaceholders.forEach { url = url.replace(it, "%s") }
        if (url.isEmpty()) return Result.Invalid("Enter the search URL.")
        if (url.length > MAX_TEMPLATE_LENGTH || url.any { it.isWhitespace() }) {
            return Result.Invalid("That doesn't look like a web address.")
        }
        val lower = url.lowercase(Locale.ROOT)
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            return Result.Invalid("The URL must start with https://")
        }
        val parsed = runCatching { URI(url.replace("%s", "QUERY")) }.getOrNull()
        if (parsed == null || parsed.host.isNullOrBlank()) {
            return Result.Invalid("That doesn't look like a web address.")
        }
        val queryHasTerms = url.substringAfter('?', "").substringBefore('#')
            .split('&')
            .any { part -> part.substringAfter('=', "") == "%s" && part.substringBefore('=').isNotEmpty() }
        if (!queryHasTerms || url.substringBefore('?').contains("%s")) {
            return Result.Invalid("Put %s where the search terms go, for example https://example.com/search?q=%s")
        }
        return Result.Valid(SearchEngine(SearchEngine.CUSTOM_ID, cleanName, url))
    }

    /** The stored engine, or null when nothing (or something no longer valid) is stored. */
    fun create(name: String, template: String): SearchEngine? {
        if (name.isBlank() || template.isBlank()) return null
        return (validate(name, template) as? Result.Valid)?.engine
    }
}
