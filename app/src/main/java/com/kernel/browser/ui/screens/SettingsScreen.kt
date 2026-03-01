package com.kernel.browser.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kernel.browser.BuildConfig
import com.kernel.browser.settings.SearchEngine
import com.kernel.browser.settings.SettingsViewModel
import com.kernel.browser.ui.components.NoiseBackground
import com.kernel.browser.ui.theme.KernelAccent
import com.kernel.browser.ui.theme.KernelBlack
import com.kernel.browser.ui.theme.KernelBorder
import com.kernel.browser.ui.theme.KernelMuted
import com.kernel.browser.ui.theme.KernelSurfaceVariant
import com.kernel.browser.ui.theme.KernelWhite

@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchEngine by settingsViewModel.searchEngine.collectAsState()
    val darkTheme by settingsViewModel.darkTheme.collectAsState()
    val blockTrackers by settingsViewModel.blockTrackers.collectAsState()
    val blockCookies by settingsViewModel.blockCookies.collectAsState()
    val clearOnExit by settingsViewModel.clearOnExit.collectAsState()
    val jsEnabled by settingsViewModel.javascriptEnabled.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        NoiseBackground(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
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
                    text = "SETTINGS",
                    style = MaterialTheme.typography.headlineMedium,
                    color = KernelAccent,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Search Engine
                item {
                    SectionTitle("SEARCH ENGINE")
                }
                SearchEngine.entries.forEach { engine ->
                    item {
                        SearchEngineRow(
                            label = engine.label,
                            isSelected = searchEngine == engine.url,
                            onClick = { settingsViewModel.setSearchEngine(engine.url) },
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(20.dp)) }

                // Privacy
                item {
                    SectionTitle("PRIVACY")
                }
                item {
                    ToggleRow("Block Trackers", blockTrackers) {
                        settingsViewModel.setBlockTrackers(it)
                    }
                }
                item {
                    ToggleRow("Block Third-Party Cookies", blockCookies) {
                        settingsViewModel.setBlockCookies(it)
                    }
                }
                item {
                    ToggleRow("Clear Data on Exit", clearOnExit) {
                        settingsViewModel.setClearOnExit(it)
                    }
                }
                item {
                    ToggleRow("JavaScript", jsEnabled) {
                        settingsViewModel.setJavascriptEnabled(it)
                    }
                }

                item { Spacer(modifier = Modifier.height(20.dp)) }

                // Theme
                item {
                    SectionTitle("APPEARANCE")
                }
                item {
                    ToggleRow("Dark Theme", darkTheme) {
                        settingsViewModel.setDarkTheme(it)
                    }
                }

                item { Spacer(modifier = Modifier.height(20.dp)) }

                // About
                item {
                    SectionTitle("ABOUT")
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(KernelSurfaceVariant)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Version", color = KernelWhite, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            BuildConfig.VERSION_NAME,
                            color = KernelMuted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(40.dp)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = KernelMuted,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(KernelSurfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = KernelWhite, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = KernelBlack,
                checkedTrackColor = KernelAccent,
                uncheckedThumbColor = KernelMuted,
                uncheckedTrackColor = KernelSurfaceVariant,
            ),
        )
    }
}

@Composable
private fun SearchEngineRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) KernelAccent.copy(alpha = 0.1f) else KernelSurfaceVariant)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = if (isSelected) KernelAccent else KernelWhite,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Selected",
                tint = KernelAccent,
            )
        }
    }
}
