package com.kernel.browser.ui.navigation

sealed class Screen(val route: String) {
    data object Browser : Screen("browser")
    data object Tabs : Screen("tabs")
    data object Extensions : Screen("extensions")
    data object Settings : Screen("settings")
}
