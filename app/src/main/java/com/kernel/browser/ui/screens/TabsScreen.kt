package com.kernel.browser.ui.screens

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kernel.browser.tabs.TabsViewModel
import com.kernel.browser.ui.components.NoiseBackground
import com.kernel.browser.ui.components.TabCard
import com.kernel.browser.ui.theme.KernelAccent
import com.kernel.browser.ui.theme.KernelBlack
import com.kernel.browser.ui.theme.KernelWhite

@Composable
fun TabsScreen(
    tabsViewModel: TabsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs by tabsViewModel.tabs.collectAsState()
    val currentTabId by tabsViewModel.currentTabId.collectAsState()

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
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = KernelWhite,
                    )
                }
                Text(
                    text = "TABS",
                    style = MaterialTheme.typography.headlineMedium,
                    color = KernelAccent,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${tabs.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = KernelWhite.copy(alpha = 0.6f),
                    modifier = Modifier.padding(end = 16.dp),
                )
            }

            // Tab grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(tabs, key = { _, tab -> tab.id }) { index, tab ->
                    TabCard(
                        tab = tab,
                        isSelected = tab.id == currentTabId,
                        onClick = {
                            tabsViewModel.selectTab(tab.id)
                            onBack()
                        },
                        onClose = { tabsViewModel.closeTab(tab.id) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(durationMillis = 200, delayMillis = index * 40),
                            fadeOutSpec = tween(durationMillis = 150),
                            placementSpec = spring(stiffness = 400f),
                        ),
                    )
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = {
                tabsViewModel.createTab()
                onBack()
            },
            containerColor = KernelAccent,
            contentColor = KernelBlack,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .navigationBarsPadding(),
        ) {
            Icon(Icons.Default.Add, contentDescription = "New Tab")
        }
    }
}
