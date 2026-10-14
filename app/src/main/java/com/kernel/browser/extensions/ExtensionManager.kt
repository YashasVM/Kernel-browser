package com.kernel.browser.extensions

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.kernel.browser.SafeLog
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Owns every WebExtension the browser knows about: the bundled allowlist that ships
 * inside the APK and anything the user installs from addons.mozilla.org or a file.
 * GeckoView persists user installs in the profile; this class re-attaches delegates
 * on startup, mirrors the installed list for the UI, and routes install prompts and
 * extension-opened tabs to the [Host].
 */
class ExtensionManager(
    private val runtime: GeckoRuntime,
    private val preferences: ExtensionPreferences,
    private val actionRegistry: ExtensionActionRegistry
) {
    interface Host {
        fun confirmInstall(
            extension: WebExtension,
            permissions: List<String>,
            origins: List<String>,
            onResult: (granted: Boolean, allowInPrivate: Boolean) -> Unit
        )

        fun confirmOptionalPermissions(extension: WebExtension, lines: List<String>, onResult: (Boolean) -> Unit)

        /** Returns an unopened session for a tab the extension asked to create. */
        fun newExtensionTab(url: String?, active: Boolean): GeckoSession

        fun openUrlInNewTab(url: String)

        fun showMessage(message: String)

        fun onExtensionsChanged()
    }

    var host: Host? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val installed = linkedMapOf<String, WebExtension>()
    private val icons = mutableMapOf<String, Bitmap>()
    private val pendingInstalls = mutableSetOf<String>()
    private val pendingEnabled = mutableMapOf<String, Boolean>()

    fun installed(): List<WebExtension> = installed.values.sortedWith(
        compareByDescending<WebExtension> { isEnabled(it) }.thenBy { displayName(it).lowercase() }
    )

    fun find(id: String): WebExtension? = installed[id]

    fun icon(id: String): Bitmap? = icons[id]

    fun isInstalling(): Boolean = pendingInstalls.isNotEmpty()

    fun enabledCount(): Int = installed.values.count(::isEnabled)

    /** The state the user last asked for, until GeckoView confirms it. */
    fun isEnabled(extension: WebExtension): Boolean = pendingEnabled[extension.id] ?: extension.metaData.enabled

    /** Bundled extensions the user has not turned on yet, offered as one-tap adds. */
    fun suggestions(): List<AllowedExtension> = AllowedExtensions.all.filter { allowed ->
        installed[allowed.id]?.let(::isEnabled) != true
    }

    fun start() {
        runtime.webExtensionController.setPromptDelegate(promptDelegate)
        runtime.webExtensionController.setAddonManagerDelegate(addonDelegate)
        syncBundled()
        refresh()
    }

    fun refresh() {
        runtime.webExtensionController.list().accept(
            { extensions ->
                installed.clear()
                extensions.orEmpty().forEach(::track)
                notifyChanged()
            },
            { error -> SafeLog.warning("Extension list failed", error) }
        )
    }

    fun install(uri: String, method: String = WebExtensionController.INSTALLATION_METHOD_MANAGER) {
        if (!pendingInstalls.add(uri)) return
        host?.showMessage("Downloading extension...")
        notifyChanged()
        runtime.webExtensionController.install(uri, method).accept(
            { extension ->
                pendingInstalls.remove(uri)
                if (extension != null) {
                    track(extension)
                    host?.showMessage("${displayName(extension)} added")
                }
                notifyChanged()
            },
            { error ->
                pendingInstalls.remove(uri)
                SafeLog.warning("Extension install failed: $uri", error)
                describeInstallError(error)?.let { host?.showMessage(it) }
                notifyChanged()
            }
        )
    }

    fun addBundled(allowed: AllowedExtension) {
        preferences.setEnabled(allowed, true)
        ensureBundled(allowed)
    }

    fun setEnabled(extension: WebExtension, enabled: Boolean) {
        AllowedExtensions.byId(extension.id)?.let { preferences.setEnabled(it, enabled) }
        pendingEnabled[extension.id] = enabled
        if (!enabled) actionRegistry.remove(extension.id)
        val controller = runtime.webExtensionController
        // Bundled extensions are switched with the APP source so they never pick up a
        // USER disable flag that a later APP enable could not clear.
        val source = if (AllowedExtensions.byId(extension.id) != null) {
            WebExtensionController.EnableSource.APP
        } else {
            WebExtensionController.EnableSource.USER
        }
        val result = if (enabled) controller.enable(extension, source) else controller.disable(extension, source)
        result.accept(
            { updated -> updated?.let(::track); notifyChanged() },
            { error ->
                SafeLog.warning("Extension toggle failed: ${extension.id}", error)
                pendingEnabled.remove(extension.id)
                refresh()
            }
        )
    }

    fun setAllowedInPrivate(extension: WebExtension, allowed: Boolean) {
        AllowedExtensions.byId(extension.id)?.let { preferences.setAllowedInPrivate(it, allowed) }
        runtime.webExtensionController.setAllowedInPrivateBrowsing(extension, allowed).accept(
            { updated -> updated?.let(::track); notifyChanged() },
            { error -> SafeLog.warning("Extension private toggle failed: ${extension.id}", error) }
        )
    }

    fun uninstall(extension: WebExtension) {
        val bundled = AllowedExtensions.byId(extension.id)
        if (bundled != null || extension.isBuiltIn) {
            // Built-in extensions live in the APK; "removing" one means turning it off.
            bundled?.let { preferences.setEnabled(it, false) }
            setEnabled(extension, false)
            return
        }
        actionRegistry.remove(extension.id)
        runtime.webExtensionController.uninstall(extension).accept(
            {
                installed.remove(extension.id)
                icons.remove(extension.id)
                host?.showMessage("${displayName(extension)} removed")
                notifyChanged()
            },
            { error ->
                SafeLog.warning("Extension uninstall failed: ${extension.id}", error)
                host?.showMessage("Could not remove ${displayName(extension)}")
            }
        )
    }

    fun openOptions(extension: WebExtension) {
        val url = extension.metaData.optionsPageUrl
        if (url.isNullOrBlank()) {
            host?.showMessage("${displayName(extension)} has no settings page")
            return
        }
        host?.openUrlInNewTab(url)
    }

    fun displayName(extension: WebExtension): String {
        return extension.metaData.name?.takeIf { it.isNotBlank() }
            ?: AllowedExtensions.byId(extension.id)?.displayName
            ?: extension.id
    }

    fun isRemovable(extension: WebExtension): Boolean {
        return !extension.isBuiltIn && AllowedExtensions.byId(extension.id) == null
    }

    private fun syncBundled() {
        AllowedExtensions.all.forEach { allowed ->
            if (preferences.isEnabled(allowed)) {
                ensureBundled(allowed)
            } else {
                runtime.webExtensionController.list().accept({ list ->
                    list.orEmpty().firstOrNull { it.id == allowed.id && it.metaData.enabled }?.let {
                        runtime.webExtensionController.disable(it, WebExtensionController.EnableSource.APP)
                    }
                }, { error -> SafeLog.warning("Extension list failed", error) })
            }
        }
    }

    private fun ensureBundled(allowed: AllowedExtension) {
        runtime.webExtensionController.ensureBuiltIn(allowed.assetUri, allowed.id).accept(
            { extension ->
                if (extension == null) {
                    SafeLog.warning("Extension install returned no instance: ${allowed.slug}")
                    return@accept
                }
                if (extension.metaData.allowedInPrivateBrowsing != preferences.isAllowedInPrivate(allowed)) {
                    runtime.webExtensionController.setAllowedInPrivateBrowsing(extension, preferences.isAllowedInPrivate(allowed))
                }
                if (!extension.metaData.enabled) {
                    runtime.webExtensionController
                        .enable(extension, WebExtensionController.EnableSource.APP)
                        .accept({ updated -> updated?.let(::track); notifyChanged() }, {})
                }
                track(extension)
                notifyChanged()
                SafeLog.status("Extension ready: ${allowed.slug}")
            },
            { error -> SafeLog.warning("Extension install failed: ${allowed.slug}", error) }
        )
    }

    private fun track(extension: WebExtension) {
        installed[extension.id] = extension
        if (pendingEnabled[extension.id] == extension.metaData.enabled) pendingEnabled.remove(extension.id)
        actionRegistry.register(extension)
        extension.setTabDelegate(tabDelegate)
        if (!icons.containsKey(extension.id)) {
            extension.metaData.icon?.getBitmap(ICON_SIZE_PX)?.accept({ bitmap ->
                if (bitmap != null) {
                    icons[extension.id] = bitmap
                    notifyChanged()
                }
            }, {})
        }
    }

    private fun notifyChanged() {
        mainHandler.post { host?.onExtensionsChanged() }
    }

    private fun describeInstallError(error: Throwable?): String? {
        val install = error as? WebExtension.InstallException ?: return "Could not install extension"
        val name = install.extensionName?.takeIf { it.isNotBlank() } ?: "This extension"
        return when (install.code) {
            WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED -> null
            WebExtension.InstallException.ErrorCodes.ERROR_NETWORK_FAILURE -> "Download failed. Check your connection and try again."
            WebExtension.InstallException.ErrorCodes.ERROR_SIGNEDSTATE_REQUIRED -> "$name is not signed by Mozilla, so it can't be installed."
            WebExtension.InstallException.ErrorCodes.ERROR_INCOMPATIBLE -> "$name does not support this browser version."
            WebExtension.InstallException.ErrorCodes.ERROR_BLOCKLISTED,
            WebExtension.InstallException.ErrorCodes.ERROR_SOFT_BLOCKED -> "$name was blocked for security or stability reasons."
            WebExtension.InstallException.ErrorCodes.ERROR_CORRUPT_FILE -> "The extension file is damaged or not a valid .xpi."
            WebExtension.InstallException.ErrorCodes.ERROR_UNSUPPORTED_ADDON_TYPE -> "This add-on type isn't supported (only extensions are)."
            else -> "Could not install $name"
        }
    }

    private val promptDelegate = object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>,
            dataCollectionPermissions: Array<String>
        ): GeckoResult<WebExtension.PermissionPromptResponse> {
            val result = GeckoResult<WebExtension.PermissionPromptResponse>()
            val activeHost = host
            if (activeHost == null) {
                result.complete(WebExtension.PermissionPromptResponse(false, false, false))
                return result
            }
            mainHandler.post {
                activeHost.confirmInstall(extension, permissions.toList(), origins.toList()) { granted, private ->
                    result.complete(WebExtension.PermissionPromptResponse(granted, private, false))
                }
            }
            return result
        }

        override fun onOptionalPrompt(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>,
            dataCollectionPermissions: Array<String>
        ): GeckoResult<AllowOrDeny> {
            val result = GeckoResult<AllowOrDeny>()
            val activeHost = host
            val lines = ExtensionPermissions.describe(permissions.toList(), origins.toList())
            if (activeHost == null) {
                result.complete(AllowOrDeny.DENY)
                return result
            }
            mainHandler.post {
                activeHost.confirmOptionalPermissions(extension, lines) { allowed ->
                    result.complete(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                }
            }
            return result
        }
    }

    private val addonDelegate = object : WebExtensionController.AddonManagerDelegate {
        override fun onInstalled(extension: WebExtension) {
            track(extension)
            notifyChanged()
        }

        override fun onUninstalled(extension: WebExtension) {
            installed.remove(extension.id)
            actionRegistry.remove(extension.id)
            notifyChanged()
        }

        override fun onEnabled(extension: WebExtension) {
            track(extension)
            notifyChanged()
        }

        override fun onDisabled(extension: WebExtension) {
            track(extension)
            actionRegistry.remove(extension.id)
            notifyChanged()
        }

        override fun onReady(extension: WebExtension) {
            track(extension)
            notifyChanged()
        }

        override fun onInstallationFailed(extension: WebExtension?, installException: WebExtension.InstallException) {
            describeInstallError(installException)?.let { message -> mainHandler.post { host?.showMessage(message) } }
        }
    }

    private val tabDelegate = object : WebExtension.TabDelegate {
        override fun onNewTab(
            source: WebExtension,
            createDetails: WebExtension.CreateTabDetails
        ): GeckoResult<GeckoSession> {
            val activeHost = host ?: return GeckoResult.fromValue(null)
            return GeckoResult.fromValue(activeHost.newExtensionTab(createDetails.url, createDetails.active != false))
        }

        override fun onOpenOptionsPage(source: WebExtension) {
            mainHandler.post { openOptions(source) }
        }
    }

    private companion object {
        const val ICON_SIZE_PX = 96
    }
}
