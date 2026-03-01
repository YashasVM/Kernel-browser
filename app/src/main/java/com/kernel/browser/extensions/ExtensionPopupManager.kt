package com.kernel.browser.extensions

import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtensionPopupManager @Inject constructor(
    private val runtime: GeckoRuntime,
) {

    private var popupSession: GeckoSession? = null

    fun createPopupSession(popupUrl: String): GeckoSession {
        closePopupSession()

        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .build()

        val session = GeckoSession(settings)
        session.open(runtime)
        session.loadUri(popupUrl)

        popupSession = session
        return session
    }

    fun closePopupSession() {
        popupSession?.close()
        popupSession = null
    }

    fun isPopupOpen(): Boolean = popupSession != null
}
