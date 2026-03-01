package com.kernel.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kernel.browser.ui.theme.KernelAccent
import com.kernel.browser.ui.theme.KernelBlack
import com.kernel.browser.ui.theme.KernelMuted
import com.kernel.browser.ui.theme.KernelSurface
import com.kernel.browser.ui.theme.KernelWhite

@Composable
fun BottomNav(
    canGoBack: Boolean,
    canGoForward: Boolean,
    tabCount: Int,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onTabs: () -> Unit,
    onExtensions: () -> Unit,
    onSettings: () -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(KernelSurface.copy(alpha = 0.95f))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Back
            IconButton(onClick = onBack, enabled = canGoBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = if (canGoBack) KernelWhite else KernelMuted.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp),
                )
            }

            // Forward
            IconButton(onClick = onForward, enabled = canGoForward) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Forward",
                    tint = if (canGoForward) KernelWhite else KernelMuted.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp),
                )
            }

            // Refresh
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = KernelWhite,
                    modifier = Modifier.size(22.dp),
                )
            }

            // Tab count
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(KernelAccent.copy(alpha = 0.15f))
                    .clickable(onClick = onTabs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tabCount.toString(),
                    color = KernelAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Menu",
                        tint = KernelWhite,
                        modifier = Modifier.size(22.dp),
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(KernelSurface),
                ) {
                    DropdownMenuItem(
                        text = { Text("New Tab", color = KernelWhite) },
                        onClick = { showMenu = false; onNewTab() },
                    )
                    DropdownMenuItem(
                        text = { Text("Extensions", color = KernelWhite) },
                        onClick = { showMenu = false; onExtensions() },
                        leadingIcon = {
                            Icon(Icons.Default.Extension, contentDescription = null, tint = KernelAccent, modifier = Modifier.size(18.dp))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Settings", color = KernelWhite) },
                        onClick = { showMenu = false; onSettings() },
                    )
                }
            }
        }
    }
}
