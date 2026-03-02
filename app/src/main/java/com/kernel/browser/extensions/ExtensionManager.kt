package com.kernel.browser.extensions

import android.net.Uri
import com.kernel.browser.extensions.models.ExtensionAction
import com.kernel.browser.extensions.models.KernelExtension
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtensionManager @Inject constructor(
    private val runtime: GeckoRuntime,
    private val installer: ExtensionInstaller,
) {

    private val controller: WebExtensionController
        get() = runtime.webExtensionController

    private val _installedExtensions = MutableStateFlow<List<KernelExtension>>(emptyList())
    val installedExtensions: StateFlow<List<KernelExtension>> = _installedExtensions.asStateFlow()

    private val _actions = MutableStateFlow<List<ExtensionAction>>(emptyList())
    val actions: StateFlow<List<ExtensionAction>> = _actions.asStateFlow()

    private val webExtensions = mutableMapOf<String, WebExtension>()

    fun initialize() {
        controller.list().accept { extensions ->
            extensions?.forEach { ext -> registerExtension(ext) }
        }
    }

    fun installFromAmo(amoUrl: String, onResult: (Boolean) -> Unit) {
        controller.install(amoUrl).accept { ext ->
            if (ext != null) {
                registerExtension(ext)
                onResult(true)
            } else {
                onResult(false)
            }
        }
    }

    fun installFromFile(uri: Uri, onResult: (Boolean) -> Unit) {
        val fileUri = installer.copyToCache(uri)
        if (fileUri == null) {
            onResult(false)
            return
        }
        controller.install(fileUri).accept { ext ->
            if (ext != null) {
                registerExtension(ext)
                onResult(true)
            } else {
                onResult(false)
            }
        }
    }

    fun setEnabled(extensionId: String, enabled: Boolean, onResult: (Boolean) -> Unit) {
        val ext = webExtensions[extensionId] ?: run {
            onResult(false)
            return
        }
        if (enabled) {
            controller.enable(ext, WebExtensionController.EnableSource.USER).accept { updated ->
                if (updated != null) {
                    webExtensions[extensionId] = updated
                    updateExtensionState(extensionId) { it.copy(isEnabled = true) }
                }
                onResult(updated != null)
            }
        } else {
            controller.disable(ext, WebExtensionController.EnableSource.USER).accept { updated ->
                if (updated != null) {
                    webExtensions[extensionId] = updated
                    updateExtensionState(extensionId) { it.copy(isEnabled = false) }
                }
                onResult(updated != null)
            }
        }
    }

    fun uninstall(extensionId: String, onResult: (Boolean) -> Unit) {
        val ext = webExtensions[extensionId] ?: run {
            onResult(false)
            return
        }
        controller.uninstall(ext).accept {
            webExtensions.remove(extensionId)
            _installedExtensions.update { list -> list.filter { it.id != extensionId } }
            _actions.update { list -> list.filter { it.extensionId != extensionId } }
            onResult(true)
        }
    }

    private fun registerExtension(ext: WebExtension) {
        webExtensions[ext.id] = ext

        val meta = ext.metaData
        val kernelExt = KernelExtension(
            id = ext.id,
            name = meta?.name ?: ext.id,
            description = meta?.description ?: "",
            version = meta?.version ?: "",
            isEnabled = meta?.enabled ?: true,
            isInstalled = true,
        )

        _installedExtensions.update { list ->
            list.filter { it.id != ext.id } + kernelExt
        }

        ext.setActionDelegate(object : WebExtension.ActionDelegate {
            override fun onBrowserAction(
                extension: WebExtension,
                session: org.mozilla.geckoview.GeckoSession?,
                action: WebExtension.Action,
            ) {
                handleAction(ext.id, action)
            }

            override fun onPageAction(
                extension: WebExtension,
                session: org.mozilla.geckoview.GeckoSession?,
                action: WebExtension.Action,
            ) {
                handleAction(ext.id, action)
            }
        })
    }

    private fun handleAction(extensionId: String, action: WebExtension.Action) {
        val extAction = ExtensionAction(
            extensionId = extensionId,
            title = action.title,
            badgeText = action.badgeText,
            badgeColor = action.badgeBackgroundColor,
            enabled = action.enabled ?: true,
        )
        _actions.update { list ->
            list.filter { it.extensionId != extensionId } + extAction
        }
    }

    private fun updateExtensionState(id: String, transform: (KernelExtension) -> KernelExtension) {
        _installedExtensions.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }
}
