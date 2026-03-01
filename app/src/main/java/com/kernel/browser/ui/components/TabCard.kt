package com.kernel.browser.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kernel.browser.tabs.KernelTab
import com.kernel.browser.ui.theme.KernelAccent
import com.kernel.browser.ui.theme.KernelBlack
import com.kernel.browser.ui.theme.KernelMuted
import com.kernel.browser.ui.theme.KernelSurfaceVariant
import com.kernel.browser.ui.theme.KernelWhite
import com.kernel.browser.utils.UrlUtils
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun TabCard(
    tab: KernelTab,
    isSelected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val animatedOffset by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = spring(stiffness = 600f),
        label = "swipe",
    )
    val dismissThreshold = with(LocalDensity.current) { 120.dp.toPx() }

    Box(
        modifier = modifier
            .offset { IntOffset(animatedOffset.roundToInt(), 0) }
            .alpha(1f - (abs(animatedOffset) / dismissThreshold).coerceIn(0f, 1f) * 0.5f)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) KernelAccent.copy(alpha = 0.12f)
                else KernelSurfaceVariant,
            )
            .clickable(onClick = onClick)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(offsetX) > dismissThreshold) {
                            onClose()
                        }
                        offsetX = 0f
                    },
                    onDragCancel = { offsetX = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        offsetX += dragAmount
                    },
                )
            },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Thumbnail area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .background(KernelBlack),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = UrlUtils.extractDomain(tab.url).take(2).uppercase(),
                    style = MaterialTheme.typography.headlineLarge,
                    color = KernelAccent.copy(alpha = 0.3f),
                )
            }

            // Info bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(end = 24.dp)) {
                    Text(
                        text = tab.title.ifEmpty { "New Tab" },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) KernelAccent else KernelWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = UrlUtils.displayUrl(tab.url),
                        style = MaterialTheme.typography.labelSmall,
                        color = KernelMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(0.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close tab",
                        tint = KernelMuted,
                        modifier = Modifier.padding(2.dp),
                    )
                }
            }
        }

        // Selected indicator
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(KernelAccent, CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "ACTIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = KernelBlack,
                )
            }
        }
    }
}
