package com.kernel.browser.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kernel.browser.extensions.ExtensionViewModel
import com.kernel.browser.settings.SettingsViewModel
import com.kernel.browser.tabs.TabsViewModel
import com.kernel.browser.ui.screens.BrowserScreen
import com.kernel.browser.ui.screens.ExtensionsScreen
import com.kernel.browser.ui.screens.SettingsScreen
import com.kernel.browser.ui.screens.TabsScreen

@Composable
fun KernelApp(
    tabsViewModel: TabsViewModel,
    extensionViewModel: ExtensionViewModel,
    settingsViewModel: SettingsViewModel,
    onGeckoViewVisibility: (Boolean) -> Unit,
) {
    val navController = rememberNavController()
    val searchEngine by settingsViewModel.searchEngine.collectAsState()

    NavHost(
        navController = navController,
        startDestination = Screen.Browser.route,
        enterTransition = { slideInHorizontally(spring(stiffness = 700f)) { it / 3 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally(spring(stiffness = 700f)) { it / 3 } + fadeOut() },
    ) {
        composable(Screen.Browser.route) {
            onGeckoViewVisibility(true)
            BrowserScreen(
                tabsViewModel = tabsViewModel,
                extensionViewModel = extensionViewModel,
                searchEngineUrl = searchEngine,
                onNavigateToTabs = { navController.navigate(Screen.Tabs.route) },
                onNavigateToExtensions = { navController.navigate(Screen.Extensions.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
            )
        }

        composable(Screen.Tabs.route) {
            onGeckoViewVisibility(false)
            TabsScreen(
                tabsViewModel = tabsViewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.Extensions.route) {
            onGeckoViewVisibility(false)
            ExtensionsScreen(
                extensionViewModel = extensionViewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.Settings.route) {
            onGeckoViewVisibility(false)
            SettingsScreen(
                settingsViewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
