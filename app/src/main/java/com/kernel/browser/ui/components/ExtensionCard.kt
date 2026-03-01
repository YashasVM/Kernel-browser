package com.kernel.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kernel.browser.extensions.models.KernelExtension
import com.kernel.browser.ui.theme.KernelAccent
import com.kernel.browser.ui.theme.KernelBlack
import com.kernel.browser.ui.theme.KernelError
import com.kernel.browser.ui.theme.KernelMuted
import com.kernel.browser.ui.theme.KernelSurfaceVariant
import com.kernel.browser.ui.theme.KernelWhite

@Composable
fun ExtensionCard(
    extension: KernelExtension,
    isInstalling: Boolean = false,
    onToggle: (() -> Unit)? = null,
    onUninstall: (() -> Unit)? = null,
    onInstall: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(KernelSurfaceVariant)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(KernelAccent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Extension,
                contentDescription = null,
                tint = KernelAccent,
                modifier = Modifier.size(22.dp),
            )
        }

        // Info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = extension.name,
                style = MaterialTheme.typography.titleSmall,
                color = KernelWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = extension.description,
                style = MaterialTheme.typography.bodySmall,
                color = KernelMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Actions
        when {
            isInstalling -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = KernelAccent,
                    strokeWidth = 2.dp,
                )
            }
            extension.isInstalled -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (onToggle != null) {
                        Switch(
                            checked = extension.isEnabled,
                            onCheckedChange = { onToggle() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = KernelBlack,
                                checkedTrackColor = KernelAccent,
                                uncheckedThumbColor = KernelMuted,
                                uncheckedTrackColor = KernelSurfaceVariant,
                            ),
                        )
                    }
                    if (onUninstall != null) {
                        IconButton(onClick = onUninstall, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Uninstall",
                                tint = KernelError.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            else -> {
                if (onInstall != null) {
                    TextButton(onClick = onInstall) {
                        Text("INSTALL", color = KernelAccent, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
