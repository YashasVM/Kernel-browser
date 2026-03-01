package com.kernel.browser.engine

import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

class ExtensionContentDelegate(
    private val onBrowserAction: (WebExtension, WebExtension.Action) -> Unit = { _, _ -> },
    private val onPageAction: (WebExtension, WebExtension.Action) -> Unit = { _, _ -> },
) : WebExtension.ActionDelegate {

    override fun onBrowserAction(
        extension: WebExtension,
        session: GeckoSession?,
        action: WebExtension.Action,
    ) {
        onBrowserAction(extension, action)
    }

    override fun onPageAction(
        extension: WebExtension,
        session: GeckoSession?,
        action: WebExtension.Action,
    ) {
        onPageAction(extension, action)
    }
}
