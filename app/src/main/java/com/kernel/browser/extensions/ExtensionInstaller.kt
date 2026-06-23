package com.kernel.browser.extensions

import com.kernel.browser.SafeLog
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

class ExtensionInstaller(
    private val runtime: GeckoRuntime,
    private val preferences: ExtensionPreferences,
    private val actionRegistry: ExtensionActionRegistry? = null
) {
    fun syncAllowlist() {
        AllowedExtensions.all.forEach { extension ->
            if (preferences.isEnabled(extension)) {
                ensureInstalled(extension)
            } else {
                disableIfInstalled(extension)
            }
        }
    }

    fun ensureInstalled(extension: AllowedExtension) {
        runtime.webExtensionController
            .ensureBuiltIn(extension.assetUri, extension.id)
            .accept(
                { installed ->
                    if (installed != null) {
                        actionRegistry?.register(installed)
                        applyPrivateMode(installed, preferences.isAllowedInPrivate(extension))
                        enable(installed)
                        SafeLog.status("Extension ready: ${extension.slug}")
                    } else {
                        SafeLog.warning("Extension install returned no instance: ${extension.slug}")
                    }
                },
                { error -> SafeLog.warning("Extension install failed: ${extension.slug}", error) }
            )
    }

    fun disableIfInstalled(extension: AllowedExtension) {
        findInstalled(extension.id) { installed ->
            if (installed != null) {
                actionRegistry?.register(installed)
                runtime.webExtensionController.disable(installed, WebExtensionController.EnableSource.APP)
            }
        }
    }

    fun applyPrivateMode(extension: AllowedExtension) {
        findInstalled(extension.id) { installed ->
            if (installed != null) {
                applyPrivateMode(installed, preferences.isAllowedInPrivate(extension))
            }
        }
    }

    private fun enable(extension: WebExtension) {
        if (!extension.metaData.enabled) {
            runtime.webExtensionController.enable(extension, WebExtensionController.EnableSource.APP)
        }
    }

    private fun applyPrivateMode(extension: WebExtension, allowed: Boolean) {
        if (extension.metaData.allowedInPrivateBrowsing != allowed) {
            runtime.webExtensionController.setAllowedInPrivateBrowsing(extension, allowed)
        }
    }

    private fun findInstalled(id: String, callback: (WebExtension?) -> Unit) {
        runtime.webExtensionController.list().accept(
            { extensions -> callback(extensions.orEmpty().firstOrNull { it.id == id }) },
            { error ->
                SafeLog.warning("Extension list failed", error)
                callback(null)
            }
        )
    }
}
