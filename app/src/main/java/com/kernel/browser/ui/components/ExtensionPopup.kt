package com.kernel.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kernel.browser.ui.theme.KernelMuted
import com.kernel.browser.ui.theme.KernelSurface
import com.kernel.browser.ui.theme.KernelWhite
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

@Composable
fun ExtensionPopup(
    title: String,
    session: GeckoSession,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(KernelSurface)
            .padding(top = 12.dp),
    ) {
        // Handle bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 140.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(KernelMuted.copy(alpha = 0.4f)),
        )

        // Title
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = KernelWhite,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        // GeckoView for popup content
        AndroidView(
            factory = { context ->
                GeckoView(context).apply {
                    setSession(session)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp),
            onRelease = { view ->
                try {
                    view.releaseSession()
                } catch (_: Exception) {}
            },
        )
    }
}
