package com.kernel.browser.extensions

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.browser.extensions.models.ExtensionAction
import com.kernel.browser.extensions.models.KernelExtension
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ExtensionViewModel @Inject constructor(
    private val extensionManager: ExtensionManager,
) : ViewModel() {

    val installedExtensions: StateFlow<List<KernelExtension>> = extensionManager.installedExtensions
    val actions: StateFlow<List<ExtensionAction>> = extensionManager.actions

    private val _installInProgress = MutableStateFlow<Set<String>>(emptySet())
    val installInProgress: StateFlow<Set<String>> = _installInProgress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val recommendedExtensions: StateFlow<List<KernelExtension>> = installedExtensions
        .combine(_installInProgress) { installed, inProgress ->
            val installedIds = installed.map { it.id }.toSet()
            RecommendedExtensions.list.filter { it.id !in installedIds }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecommendedExtensions.list)

    init {
        extensionManager.initialize()
    }

    fun installRecommended(extension: KernelExtension) {
        val url = extension.amoUrl ?: return
        _installInProgress.value = _installInProgress.value + extension.id
        _error.value = null

        extensionManager.installFromAmo(url) { success ->
            _installInProgress.value = _installInProgress.value - extension.id
            if (!success) {
                _error.value = "Failed to install ${extension.name}"
            }
        }
    }

    fun installFromFile(uri: Uri) {
        _error.value = null
        extensionManager.installFromFile(uri) { success ->
            if (!success) {
                _error.value = "Failed to install extension from file"
            }
        }
    }

    fun toggleExtension(extensionId: String) {
        val ext = installedExtensions.value.find { it.id == extensionId } ?: return
        extensionManager.setEnabled(extensionId, !ext.isEnabled) { success ->
            if (!success) {
                _error.value = "Failed to toggle extension"
            }
        }
    }

    fun uninstallExtension(extensionId: String) {
        extensionManager.uninstall(extensionId) { success ->
            if (!success) {
                _error.value = "Failed to uninstall extension"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
