package com.kernel.browser.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kernel.browser.extensions.ExtensionViewModel
import com.kernel.browser.ui.components.ExtensionCard
import com.kernel.browser.ui.components.NoiseBackground
import com.kernel.browser.ui.theme.KernelAccent
import com.kernel.browser.ui.theme.KernelBorder
import com.kernel.browser.ui.theme.KernelMuted
import com.kernel.browser.ui.theme.KernelWhite

@Composable
fun ExtensionsScreen(
    extensionViewModel: ExtensionViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val installed by extensionViewModel.installedExtensions.collectAsState()
    val recommended by extensionViewModel.recommendedExtensions.collectAsState()
    val installing by extensionViewModel.installInProgress.collectAsState()
    val error by extensionViewModel.error.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                extensionViewModel.installFromFile(uri)
            }
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            extensionViewModel.clearError()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        NoiseBackground(modifier = Modifier.fillMaxSize())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = KernelWhite)
            }
            Text(
                text = "EXTENSIONS",
                style = MaterialTheme.typography.headlineMedium,
                color = KernelAccent,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Installed section
            if (installed.isNotEmpty()) {
                item {
                    SectionHeader("INSTALLED")
                }
                items(installed, key = { it.id }) { ext ->
                    ExtensionCard(
                        extension = ext,
                        onToggle = { extensionViewModel.toggleExtension(ext.id) },
                        onUninstall = { extensionViewModel.uninstallExtension(ext.id) },
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }

            // Recommended section
            if (recommended.isNotEmpty()) {
                item {
                    SectionHeader("RECOMMENDED")
                }
                items(recommended, key = { it.id }) { ext ->
                    ExtensionCard(
                        extension = ext,
                        isInstalling = ext.id in installing,
                        onInstall = { extensionViewModel.installRecommended(ext) },
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }

            // Import section
            item {
                SectionHeader("FROM FILE")
            }
            item {
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/x-xpinstall"
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                                "application/x-xpinstall",
                                "application/octet-stream",
                                "application/zip",
                            ))
                        }
                        filePicker.launch(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        Icons.Default.FileOpen,
                        contentDescription = null,
                        tint = KernelAccent,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text("Import .xpi file", color = KernelAccent)
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }

        SnackbarHost(hostState = snackbarHostState)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = KernelMuted,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
