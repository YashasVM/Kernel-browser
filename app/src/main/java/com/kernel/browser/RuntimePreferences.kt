package com.kernel.browser

import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.StorageController

/** Pushes [BrowserPreferences] into Gecko's runtime-wide settings. */
object RuntimePreferences {
    /**
     * What "Delete browsing data on quit" removes from Gecko: cookies, site storage, sign-in
     * sessions, and caches. Site permissions are kept, as in Firefox's default selection.
     */
    val QUIT_CLEAR_FLAGS: Long =StorageController.ClearFlags.COOKIES or
        StorageController.ClearFlags.DOM_STORAGES or
        StorageController.ClearFlags.AUTH_SESSIONS or
        StorageController.ClearFlags.ALL_CACHES

    fun apply(runtime: GeckoRuntime, preferences: BrowserPreferences) {
        val tracking = preferences.trackingProtection
        val strict = tracking && preferences.strictTrackingProtection

        runtime.settings
            .setFontSizeFactor(preferences.pageZoomPercent / 100f)
            .setLoginAutofillEnabled(preferences.loginAutofill)
            .setJavaScriptEnabled(preferences.javascriptEnabled)
            .setGlobalPrivacyControl(tracking)
            .setFingerprintingProtection(tracking)
            .setAllowInsecureConnections(httpsOnlyValue(preferences.httpsOnly))
            .setPreferredColorScheme(colorSchemeValue(preferences.websiteTheme))
            .setForceUserScalableEnabled(preferences.forceEnableZoom)

        val cookieBannerMode = if (preferences.cookieBannerReduction) {
            ContentBlocking.CookieBannerMode.COOKIE_BANNER_MODE_REJECT
        } else {
            ContentBlocking.CookieBannerMode.COOKIE_BANNER_MODE_DISABLED
        }

        runtime.settings.contentBlocking
            .setAntiTracking(
                when {
                    strict -> ContentBlocking.AntiTracking.STRICT
                    tracking -> ContentBlocking.AntiTracking.DEFAULT
                    else -> ContentBlocking.AntiTracking.NONE
                }
            )
            .setSafeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
            .setCookieBehavior(
                if (preferences.blockThirdPartyCookies || strict) {
                    ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
                } else {
                    ContentBlocking.CookieBehavior.ACCEPT_ALL
                }
            )
            .setCookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .setCookiePurging(tracking)
            .setStrictSocialTrackingProtection(strict)
            .setEnhancedTrackingProtectionLevel(
                when {
                    strict -> ContentBlocking.EtpLevel.STRICT
                    tracking -> ContentBlocking.EtpLevel.DEFAULT
                    else -> ContentBlocking.EtpLevel.NONE
                }
            )
            .setEnhancedTrackingProtectionCategory(
                when {
                    strict -> ContentBlocking.EtpCategory.STRICT
                    tracking -> ContentBlocking.EtpCategory.STANDARD
                    else -> ContentBlocking.EtpCategory.CUSTOM
                }
            )
            .setCookieBannerMode(cookieBannerMode)
            .setCookieBannerModePrivateBrowsing(cookieBannerMode)
    }

    private fun httpsOnlyValue(mode: HttpsOnlyMode): Int = when (mode) {
        HttpsOnlyMode.OFF -> GeckoRuntimeSettings.ALLOW_ALL
        HttpsOnlyMode.PRIVATE_TABS -> GeckoRuntimeSettings.HTTPS_ONLY_PRIVATE
        HttpsOnlyMode.ALL_TABS -> GeckoRuntimeSettings.HTTPS_ONLY
    }

    private fun colorSchemeValue(theme: WebsiteTheme): Int = when (theme) {
        WebsiteTheme.SYSTEM -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
        WebsiteTheme.LIGHT -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
        WebsiteTheme.DARK -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
    }

    /**
     * Runs "Delete browsing data on quit": forgets history and the saved tab session right away,
     * and asks Gecko to drop cookies, site storage, and caches.
     */
    fun clearOnQuit(runtime: GeckoRuntime, historyStore: BrowserHistoryStore, sessionStore: SessionStore) {
        historyStore.clear()
        sessionStore.clear()
        runtime.storageController.clearData(QUIT_CLEAR_FLAGS).accept(
            { },
            { SafeLog.warning("Clearing browsing data on quit failed", it) }
        )
    }
}
