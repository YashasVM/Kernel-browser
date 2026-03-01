package com.kernel.browser.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kernel_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val BLOCK_TRACKERS = booleanPreferencesKey("block_trackers")
        val BLOCK_COOKIES = booleanPreferencesKey("block_cookies")
        val CLEAR_ON_EXIT = booleanPreferencesKey("clear_on_exit")
        val JAVASCRIPT_ENABLED = booleanPreferencesKey("javascript_enabled")
    }

    val searchEngine: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SEARCH_ENGINE] ?: SearchEngine.DUCKDUCKGO.url
    }

    val darkTheme: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DARK_THEME] ?: true
    }

    val blockTrackers: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.BLOCK_TRACKERS] ?: true
    }

    val blockCookies: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.BLOCK_COOKIES] ?: false
    }

    val clearOnExit: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.CLEAR_ON_EXIT] ?: false
    }

    val javascriptEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.JAVASCRIPT_ENABLED] ?: true
    }

    suspend fun setSearchEngine(url: String) {
        context.dataStore.edit { it[Keys.SEARCH_ENGINE] = url }
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DARK_THEME] = enabled }
    }

    suspend fun setBlockTrackers(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BLOCK_TRACKERS] = enabled }
    }

    suspend fun setBlockCookies(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BLOCK_COOKIES] = enabled }
    }

    suspend fun setClearOnExit(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CLEAR_ON_EXIT] = enabled }
    }

    suspend fun setJavascriptEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.JAVASCRIPT_ENABLED] = enabled }
    }
}

enum class SearchEngine(val label: String, val url: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s"),
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query=%s"),
}
