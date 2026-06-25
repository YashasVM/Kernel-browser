package com.kernel.browser

import android.content.Context

class BrowserPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("browser_preferences", Context.MODE_PRIVATE)

    var searchEngineId: String
        get() = preferences.getString(KEY_SEARCH_ENGINE, SearchEngine.GOOGLE.id) ?: SearchEngine.GOOGLE.id
        set(value) {
            preferences.edit().putString(KEY_SEARCH_ENGINE, SearchEngine.byId(value).id).apply()
        }

    var homepageUrl: String
        get() = preferences.getString(KEY_HOMEPAGE_URL, DEFAULT_HOMEPAGE) ?: DEFAULT_HOMEPAGE
        set(value) {
            preferences.edit().putString(KEY_HOMEPAGE_URL, UrlNormalizer.normalize(value)).apply()
        }

    var desktopMode: Boolean
        get() = preferences.getBoolean(KEY_DESKTOP_MODE, false)
        set(value) {
            preferences.edit().putBoolean(KEY_DESKTOP_MODE, value).apply()
        }

    var trackingProtection: Boolean
        get() = preferences.getBoolean(KEY_TRACKING_PROTECTION, true)
        set(value) {
            preferences.edit().putBoolean(KEY_TRACKING_PROTECTION, value).apply()
        }

    var blockThirdPartyCookies: Boolean
        get() = preferences.getBoolean(KEY_BLOCK_THIRD_PARTY_COOKIES, true)
        set(value) {
            preferences.edit().putBoolean(KEY_BLOCK_THIRD_PARTY_COOKIES, value).apply()
        }

    var loginAutofill: Boolean
        get() = preferences.getBoolean(KEY_LOGIN_AUTOFILL, true)
        set(value) {
            preferences.edit().putBoolean(KEY_LOGIN_AUTOFILL, value).apply()
        }

    var javascriptEnabled: Boolean
        get() = preferences.getBoolean(KEY_JAVASCRIPT_ENABLED, true)
        set(value) {
            preferences.edit().putBoolean(KEY_JAVASCRIPT_ENABLED, value).apply()
        }

    var pageZoomPercent: Int
        get() = preferences.getInt(KEY_PAGE_ZOOM_PERCENT, 100).coerceIn(70, 150)
        set(value) {
            preferences.edit().putInt(KEY_PAGE_ZOOM_PERCENT, value.coerceIn(70, 150)).apply()
        }

    val searchEngine: SearchEngine
        get() = SearchEngine.byId(searchEngineId)

    fun resetHomepage() {
        preferences.edit().remove(KEY_HOMEPAGE_URL).apply()
    }

    companion object {
        const val DEFAULT_HOMEPAGE = "https://www.google.com"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_HOMEPAGE_URL = "homepage_url"
        private const val KEY_DESKTOP_MODE = "desktop_mode"
        private const val KEY_TRACKING_PROTECTION = "tracking_protection"
        private const val KEY_BLOCK_THIRD_PARTY_COOKIES = "block_third_party_cookies"
        private const val KEY_LOGIN_AUTOFILL = "login_autofill"
        private const val KEY_JAVASCRIPT_ENABLED = "javascript_enabled"
        private const val KEY_PAGE_ZOOM_PERCENT = "page_zoom_percent"
    }
}

enum class SearchEngine(
    val id: String,
    val displayName: String,
    val searchUrl: String,
    val suggestionsUrl: String? = null
) {
    GOOGLE(
        id = "google",
        displayName = "Google",
        searchUrl = "https://www.google.com/search?q=%s",
        suggestionsUrl = "https://suggestqueries.google.com/complete/search?client=firefox&q=%s"
    ),
    DUCKDUCKGO(
        id = "duckduckgo",
        displayName = "DuckDuckGo",
        searchUrl = "https://duckduckgo.com/?q=%s"
    ),
    BRAVE(
        id = "brave",
        displayName = "Brave",
        searchUrl = "https://search.brave.com/search?q=%s"
    ),
    BING(
        id = "bing",
        displayName = "Bing",
        searchUrl = "https://www.bing.com/search?q=%s"
    );

    companion object {
        val all: List<SearchEngine> = entries

        fun byId(id: String): SearchEngine {
            return all.firstOrNull { it.id == id } ?: GOOGLE
        }
    }
}
