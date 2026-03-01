package com.kernel.browser.tabs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class TabsViewModel @Inject constructor(
    private val runtime: GeckoRuntime,
) : ViewModel() {

    private val _tabs = MutableStateFlow<List<KernelTab>>(emptyList())
    val tabs: StateFlow<List<KernelTab>> = _tabs.asStateFlow()

    private val _currentTabId = MutableStateFlow<String?>(null)
    val currentTabId: StateFlow<String?> = _currentTabId.asStateFlow()

    private val sessions = mutableMapOf<String, GeckoSession>()

    val currentSession: StateFlow<GeckoSession?> = _currentTabId.map { id ->
        id?.let { sessions[it] }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentTab: StateFlow<KernelTab?> = _currentTabId.map { id ->
        id?.let { tabId -> _tabs.value.find { it.id == tabId } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val tabCount: StateFlow<Int> = _tabs.map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        createTab("https://duckduckgo.com")
    }

    fun createTab(url: String = "about:blank"): String {
        val id = UUID.randomUUID().toString()
        val session = GeckoSession()
        session.open(runtime)

        session.contentDelegate = object : ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                updateTab(id) { it.copy(title = title ?: "") }
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {}
        }

        session.navigationDelegate = object : NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                updateTab(id) { it.copy(url = url ?: "") }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                updateTab(id) { it.copy(canGoBack = canGoBack) }
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                updateTab(id) { it.copy(canGoForward = canGoForward) }
            }
        }

        session.progressDelegate = object : ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                updateTab(id) { it.copy(isLoading = true, url = url) }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                updateTab(id) { it.copy(isLoading = false, progress = 100) }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                updateTab(id) { it.copy(progress = progress) }
            }
        }

        session.loadUri(url)
        sessions[id] = session

        val tab = KernelTab(id = id, url = url)
        _tabs.update { it + tab }
        selectTab(id)

        return id
    }

    fun selectTab(id: String) {
        _currentTabId.value?.let { sessions[it]?.setActive(false) }
        sessions[id]?.setActive(true)
        _currentTabId.value = id
    }

    fun closeTab(id: String) {
        sessions[id]?.close()
        sessions.remove(id)
        _tabs.update { tabs -> tabs.filter { it.id != id } }

        if (_currentTabId.value == id) {
            _currentTabId.value = _tabs.value.lastOrNull()?.id
        }

        if (_tabs.value.isEmpty()) {
            createTab()
        }
    }

    fun loadUrl(url: String) {
        _currentTabId.value?.let { id ->
            sessions[id]?.loadUri(url)
        }
    }

    fun goBack() {
        _currentTabId.value?.let { sessions[it]?.goBack() }
    }

    fun goForward() {
        _currentTabId.value?.let { sessions[it]?.goForward() }
    }

    fun reload() {
        _currentTabId.value?.let { sessions[it]?.reload() }
    }

    fun getSession(tabId: String): GeckoSession? = sessions[tabId]

    private fun updateTab(id: String, transform: (KernelTab) -> KernelTab) {
        _tabs.update { tabs ->
            tabs.map { if (it.id == id) transform(it) else it }
        }
    }

    override fun onCleared() {
        super.onCleared()
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}
