package com.kernel.browser.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kernel.browser.extensions.ExtensionViewModel
import com.kernel.browser.tabs.TabsViewModel
import com.kernel.browser.ui.components.BottomNav
import com.kernel.browser.ui.components.KernelUrlBar
import com.kernel.browser.utils.UrlUtils

@Composable
fun BrowserScreen(
    tabsViewModel: TabsViewModel,
    extensionViewModel: ExtensionViewModel,
    searchEngineUrl: String,
    onNavigateToTabs: () -> Unit,
    onNavigateToExtensions: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentTab by tabsViewModel.currentTab.collectAsState()
    val tabCount by tabsViewModel.tabCount.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        // URL Bar at top
        KernelUrlBar(
            url = currentTab?.url ?: "about:blank",
            isLoading = currentTab?.isLoading ?: false,
            progress = currentTab?.progress ?: 0,
            onUrlSubmitted = { input ->
                val loadUrl = UrlUtils.toLoadableUrl(input, searchEngineUrl)
                tabsViewModel.loadUrl(loadUrl)
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
        )

        // Bottom Navigation
        BottomNav(
            canGoBack = currentTab?.canGoBack ?: false,
            canGoForward = currentTab?.canGoForward ?: false,
            tabCount = tabCount,
            onBack = { tabsViewModel.goBack() },
            onForward = { tabsViewModel.goForward() },
            onRefresh = { tabsViewModel.reload() },
            onTabs = onNavigateToTabs,
            onExtensions = onNavigateToExtensions,
            onSettings = onNavigateToSettings,
            onNewTab = { tabsViewModel.createTab() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}
