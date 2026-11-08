package com.kernel.browser

import android.content.Context
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class BrowserPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("browser_preferences", Context.MODE_PRIVATE)

    var searchEngineId: String
        get() = preferences.getString(KEY_SEARCH_ENGINE, SearchEngine.GOOGLE.id) ?: SearchEngine.GOOGLE.id
        set(value) {
            val id = if (value == SearchEngine.CUSTOM_ID && customSearchEngine != null) value else SearchEngine.byId(value).id
            preferences.edit().putString(KEY_SEARCH_ENGINE, id).apply()
        }

    /** The engine added in Settings > Search engine, or null when there is none. */
    val customSearchEngine: SearchEngine?
        get() = CustomSearchEngines.create(
            preferences.getString(KEY_CUSTOM_SEARCH_NAME, null).orEmpty(),
            preferences.getString(KEY_CUSTOM_SEARCH_URL, null).orEmpty()
        )

    /** Stores [engine] as the custom engine and makes it the default. */
    fun saveCustomSearchEngine(engine: SearchEngine) {
        preferences.edit()
            .putString(KEY_CUSTOM_SEARCH_NAME, engine.displayName)
            .putString(KEY_CUSTOM_SEARCH_URL, engine.searchUrl)
            .putString(KEY_SEARCH_ENGINE, SearchEngine.CUSTOM_ID)
            .apply()
    }

    fun removeCustomSearchEngine() {
        val editor = preferences.edit().remove(KEY_CUSTOM_SEARCH_NAME).remove(KEY_CUSTOM_SEARCH_URL)
        if (searchEngineId == SearchEngine.CUSTOM_ID) {
            editor.putString(KEY_SEARCH_ENGINE, SearchEngine.GOOGLE.id)
        }
        editor.apply()
    }

    var homepageUrl: String
        get() = preferences.getString(KEY_HOMEPAGE_URL, DEFAULT_HOMEPAGE) ?: DEFAULT_HOMEPAGE
        set(value) {
            preferences.edit().putString(KEY_HOMEPAGE_URL, UrlNormalizer.normalize(value)).apply()
        }

    /** Show the native start page for new tabs and Home instead of loading [homepageUrl]. */
    var newTabPage: Boolean
        get() = preferences.getBoolean(KEY_NEW_TAB_PAGE, true)
        set(value) {
            preferences.edit().putBoolean(KEY_NEW_TAB_PAGE, value).apply()
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

    /** Strict instead of Standard Enhanced Tracking Protection; only used while [trackingProtection] is on. */
    var strictTrackingProtection: Boolean
        get() = preferences.getBoolean(KEY_STRICT_TRACKING_PROTECTION, false)
        set(value) {
            preferences.edit().putBoolean(KEY_STRICT_TRACKING_PROTECTION, value).apply()
        }

    var blockThirdPartyCookies: Boolean
        get() = preferences.getBoolean(KEY_BLOCK_THIRD_PARTY_COOKIES, true)
        set(value) {
            preferences.edit().putBoolean(KEY_BLOCK_THIRD_PARTY_COOKIES, value).apply()
        }

    var httpsOnly: HttpsOnlyMode
        get() = HttpsOnlyMode.byId(preferences.getString(KEY_HTTPS_ONLY, null))
        set(value) {
            preferences.edit().putString(KEY_HTTPS_ONLY, value.id).apply()
        }

    /** Automatically refuse cookie consent banners where Gecko knows how to. */
    var cookieBannerReduction: Boolean
        get() = preferences.getBoolean(KEY_COOKIE_BANNER_REDUCTION, true)
        set(value) {
            preferences.edit().putBoolean(KEY_COOKIE_BANNER_REDUCTION, value).apply()
        }

    /** Delete history, open tabs, cookies, and cache when the browser is quit. */
    var clearDataOnQuit: Boolean
        get() = preferences.getBoolean(KEY_CLEAR_DATA_ON_QUIT, false)
        set(value) {
            preferences.edit().putBoolean(KEY_CLEAR_DATA_ON_QUIT, value).apply()
        }

    var websiteTheme: WebsiteTheme
        get() = WebsiteTheme.byId(preferences.getString(KEY_WEBSITE_THEME, null))
        set(value) {
            preferences.edit().putString(KEY_WEBSITE_THEME, value.id).apply()
        }

    /** Allow pinch-zoom even on pages that ask to disable it. */
    var forceEnableZoom: Boolean
        get() = preferences.getBoolean(KEY_FORCE_ENABLE_ZOOM, false)
        set(value) {
            preferences.edit().putBoolean(KEY_FORCE_ENABLE_ZOOM, value).apply()
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

    var autoplay: AutoplayPolicy
        get() = AutoplayPolicy.byId(preferences.getString(KEY_AUTOPLAY, AutoplayPolicy.BLOCK_AUDIBLE.id))
        set(value) {
            preferences.edit().putString(KEY_AUTOPLAY, value.id).apply()
        }

    val searchEngine: SearchEngine
        get() {
            val id = searchEngineId
            return if (id == SearchEngine.CUSTOM_ID) customSearchEngine ?: SearchEngine.GOOGLE else SearchEngine.byId(id)
        }

    fun resetHomepage() {
        preferences.edit().remove(KEY_HOMEPAGE_URL).apply()
    }

    companion object {
        const val DEFAULT_HOMEPAGE = "https://www.google.com"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_CUSTOM_SEARCH_NAME = "custom_search_name"
        private const val KEY_CUSTOM_SEARCH_URL = "custom_search_url"
        private const val KEY_HOMEPAGE_URL = "homepage_url"
        private const val KEY_NEW_TAB_PAGE = "new_tab_page"
        private const val KEY_DESKTOP_MODE = "desktop_mode"
        private const val KEY_TRACKING_PROTECTION = "tracking_protection"
        private const val KEY_STRICT_TRACKING_PROTECTION = "strict_tracking_protection"
        private const val KEY_BLOCK_THIRD_PARTY_COOKIES = "block_third_party_cookies"
        private const val KEY_HTTPS_ONLY = "https_only"
        private const val KEY_COOKIE_BANNER_REDUCTION = "cookie_banner_reduction"
        private const val KEY_CLEAR_DATA_ON_QUIT = "clear_data_on_quit"
        private const val KEY_WEBSITE_THEME = "website_theme"
        private const val KEY_FORCE_ENABLE_ZOOM = "force_enable_zoom"
        private const val KEY_LOGIN_AUTOFILL = "login_autofill"
        private const val KEY_JAVASCRIPT_ENABLED = "javascript_enabled"
        private const val KEY_PAGE_ZOOM_PERCENT = "page_zoom_percent"
        private const val KEY_AUTOPLAY = "autoplay"
    }
}

/**
 * A search provider. The built-in engines are fixed; one custom engine can be added in Settings
 * (see [CustomSearchEngines]). [searchUrl] and [suggestionsUrl] use `%s` for the URL-encoded query.
 * Stored preference ids ("google", "duckduckgo", ...) are unchanged from when this was an enum.
 */
data class SearchEngine(
    val id: String,
    val displayName: String,
    val searchUrl: String,
    val suggestionsUrl: String? = null
) {
    val isCustom: Boolean
        get() = id == CUSTOM_ID

    /**
     * The results page for [query]. Uses a literal replace rather than String.format so a custom
     * template may contain other percent-escapes (for example `%20`).
     */
    fun searchUrlFor(query: String): String {
        return searchUrl.replace("%s", URLEncoder.encode(query, StandardCharsets.UTF_8.name()))
    }

    companion object {
        const val CUSTOM_ID = "custom"

        val GOOGLE = SearchEngine(
            id = "google",
            displayName = "Google",
            searchUrl = "https://www.google.com/search?q=%s",
            suggestionsUrl = "https://suggestqueries.google.com/complete/search?client=firefox&q=%s"
        )
        val DUCKDUCKGO = SearchEngine(
            id = "duckduckgo",
            displayName = "DuckDuckGo",
            searchUrl = "https://duckduckgo.com/?q=%s",
            // type=list answers in the OpenSearch ["query", ["suggestion", ...]] shape, like Google's.
            suggestionsUrl = "https://duckduckgo.com/ac/?q=%s&type=list"
        )
        val BRAVE = SearchEngine(
            id = "brave",
            displayName = "Brave",
            searchUrl = "https://search.brave.com/search?q=%s",
            suggestionsUrl = "https://search.brave.com/api/suggest?q=%s"
        )
        val BING = SearchEngine(
            id = "bing",
            displayName = "Bing",
            searchUrl = "https://www.bing.com/search?q=%s",
            suggestionsUrl = "https://api.bing.com/osjson.aspx?query=%s"
        )

        /** The built-in engines, in the order Settings lists them. */
        val all: List<SearchEngine> = listOf(GOOGLE, DUCKDUCKGO, BRAVE, BING)

        fun byId(id: String): SearchEngine {
            return all.firstOrNull { it.id == id } ?: GOOGLE
        }
    }
}

enum class AutoplayPolicy(val id: String, val displayName: String, val summary: String) {
    ALLOW_ALL("allow_all", "Allow audio and video", "Videos can start playing with sound."),
    BLOCK_AUDIBLE("block_audible", "Block audio only", "Muted previews play; sound waits for a tap."),
    BLOCK_ALL("block_all", "Block audio and video", "Nothing plays until you tap it.");

    companion object {
        fun byId(id: String?): AutoplayPolicy = entries.firstOrNull { it.id == id } ?: BLOCK_AUDIBLE
    }
}

enum class HttpsOnlyMode(val id: String, val displayName: String, val summary: String) {
    OFF("off", "Off", "Sites without a secure version load over plain HTTP."),
    PRIVATE_TABS("private", "Private tabs only", "Upgrade connections to HTTPS in private tabs."),
    ALL_TABS("all", "All tabs", "Upgrade every connection to HTTPS and warn before insecure sites.");

    companion object {
        fun byId(id: String?): HttpsOnlyMode = entries.firstOrNull { it.id == id } ?: OFF
    }
}

enum class WebsiteTheme(val id: String, val displayName: String, val summary: String) {
    SYSTEM("system", "Follow device", "Sites match your device's light or dark setting."),
    LIGHT("light", "Light", "Ask sites for their light appearance."),
    DARK("dark", "Dark", "Ask sites for their dark appearance.");

    companion object {
        fun byId(id: String?): WebsiteTheme = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}
