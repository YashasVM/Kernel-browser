package com.kernel.browser.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val searchEngine: StateFlow<String> = repository.searchEngine
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchEngine.DUCKDUCKGO.url)

    val darkTheme: StateFlow<Boolean> = repository.darkTheme
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val blockTrackers: StateFlow<Boolean> = repository.blockTrackers
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val blockCookies: StateFlow<Boolean> = repository.blockCookies
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val clearOnExit: StateFlow<Boolean> = repository.clearOnExit
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val javascriptEnabled: StateFlow<Boolean> = repository.javascriptEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setSearchEngine(url: String) {
        viewModelScope.launch { repository.setSearchEngine(url) }
    }

    fun setDarkTheme(enabled: Boolean) {
        viewModelScope.launch { repository.setDarkTheme(enabled) }
    }

    fun setBlockTrackers(enabled: Boolean) {
        viewModelScope.launch { repository.setBlockTrackers(enabled) }
    }

    fun setBlockCookies(enabled: Boolean) {
        viewModelScope.launch { repository.setBlockCookies(enabled) }
    }

    fun setClearOnExit(enabled: Boolean) {
        viewModelScope.launch { repository.setClearOnExit(enabled) }
    }

    fun setJavascriptEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setJavascriptEnabled(enabled) }
    }
}
