package com.kernel.browser

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import android.widget.ImageView
import com.kernel.browser.databinding.ActivityMainBinding

/**
 * Light/dark support. The palette itself lives in resources (values/ = light,
 * values-night/ = dark); this object keeps the system bar icons readable on either one
 * and re-tints the main toolbar when the system theme flips while the app is open.
 *
 * MainActivity declares `uiMode` in configChanges so a theme switch does not recreate
 * it: recreation would drop every open GeckoSession, including private tabs that are
 * never written to disk.
 */
object KernelTheme {
    private fun nightBits(config: Configuration): Int = config.uiMode and Configuration.UI_MODE_NIGHT_MASK

    private fun usesLightBars(context: Context): Boolean =
        context.resources.getBoolean(R.bool.kernel_light_system_bars)

    /** Dark status/navigation bar icons on the light palette, light icons on the dark one. */
    fun applySystemBarAppearance(window: Window) {
        val context = window.context
        window.decorView.setTag(R.id.kernel_night_mode, nightBits(context.resources.configuration))
        val light = usesLightBars(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = lightSystemBarFlags(context)
        }
    }

    /** Pre-Android 11 visibility flags for the normal (non-fullscreen) window state. */
    @Suppress("DEPRECATION")
    fun lightSystemBarFlags(context: Context): Int {
        if (!usesLightBars(context)) return 0
        var flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        return flags
    }

    /**
     * True once per light/dark switch for [window]. The first value is recorded by
     * [applySystemBarAppearance], so rotations and other config changes return false.
     */
    fun consumeNightModeChange(window: Window, newConfig: Configuration): Boolean {
        val now = nightBits(newConfig)
        val before = window.decorView.getTag(R.id.kernel_night_mode) as? Int
        window.decorView.setTag(R.id.kernel_night_mode, now)
        return before != null && before != now
    }

    /**
     * Re-applies every palette-dependent attribute that activity_main.xml set at inflation.
     * Drawables are assigned directly (not via setBackgroundResource, which skips a
     * resource id the view already has) so they are re-read in the new configuration.
     * Keep this in sync with activity_main.xml.
     */
    fun refreshChrome(binding: ActivityMainBinding) {
        val context = binding.root.context
        val icons = context.getColorStateList(R.color.nav_button_icon)
        val text = context.getColor(R.color.kernel_text)
        val muted = context.getColor(R.color.kernel_muted)

        binding.root.setBackgroundColor(context.getColor(R.color.kernel_background))
        binding.pullIndicator.background = context.getDrawable(R.drawable.pull_indicator_background)
        binding.suggestionsPanel.background = context.getDrawable(R.drawable.browser_chrome_background)
        binding.bottomChrome.background = context.getDrawable(R.drawable.browser_chrome_background)

        // MainActivity.updateUi() picks the normal or private pill background again.
        binding.addressPill.setBackgroundResource(0)
        binding.addressBar.setTextColor(text)
        binding.addressBar.setHintTextColor(muted)
        binding.progressBar.progressDrawable = context.getDrawable(R.drawable.address_progress)

        listOf<ImageView>(binding.securityIcon, binding.reloadButton, binding.findPrevious, binding.findNext, binding.findClose)
            .forEach { button ->
                button.background = context.getDrawable(R.drawable.circle_button_background)
                button.imageTintList = icons
            }
        listOf<ImageView>(binding.backButton, binding.forwardButton, binding.homeButton, binding.extensionsButton, binding.settingsButton)
            .forEach { button ->
                button.background = context.getDrawable(R.drawable.toolbar_button_background)
                button.imageTintList = icons
            }
        binding.tabsButton.background = context.getDrawable(R.drawable.toolbar_button_background)
        binding.tabsCount.background = context.getDrawable(R.drawable.tab_count_background)
        binding.tabsCount.setTextColor(text)

        binding.findBar.background = context.getDrawable(R.drawable.address_bar_background)
        binding.findInput.setTextColor(text)
        binding.findInput.setHintTextColor(muted)
        binding.findCount.setTextColor(muted)
    }
}
