package com.kernel.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kernel.browser.tabs.TabsViewModel
import com.kernel.browser.extensions.ExtensionViewModel
import com.kernel.browser.settings.SettingsViewModel
import com.kernel.browser.ui.navigation.KernelApp
import com.kernel.browser.ui.theme.KernelTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoView

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private lateinit var geckoView: GeckoView

    private val tabsViewModel: TabsViewModel by viewModels()
    private val extensionViewModel: ExtensionViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        geckoView = findViewById(R.id.gecko_view)
        val composeView = findViewById<ComposeView>(R.id.compose_overlay)

        observeCurrentSession()

        composeView.setContent {
            KernelTheme {
                KernelApp(
                    tabsViewModel = tabsViewModel,
                    extensionViewModel = extensionViewModel,
                    settingsViewModel = settingsViewModel,
                    onGeckoViewVisibility = { visible ->
                        geckoView.visibility = if (visible) {
                            android.view.View.VISIBLE
                        } else {
                            android.view.View.INVISIBLE
                        }
                    },
                )
            }
        }
    }

    private fun observeCurrentSession() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tabsViewModel.currentSession
                    .distinctUntilChanged()
                    .collect { session ->
                        try {
                            geckoView.releaseSession()
                        } catch (_: IllegalStateException) {
                            // No session attached
                        }
                        session?.let { geckoView.setSession(it) }
                    }
            }
        }
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        val currentTab = tabsViewModel.currentTab.value
        if (currentTab?.canGoBack == true) {
            tabsViewModel.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
