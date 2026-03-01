package com.kernel.browser.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kernel.browser.ui.theme.KernelAccent
import com.kernel.browser.ui.theme.KernelBlack
import com.kernel.browser.ui.theme.KernelMuted
import com.kernel.browser.ui.theme.KernelSurfaceVariant
import com.kernel.browser.ui.theme.KernelWhite
import com.kernel.browser.utils.UrlUtils

@Composable
fun KernelUrlBar(
    url: String,
    isLoading: Boolean,
    progress: Int,
    onUrlSubmitted: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val barHeight by animateDpAsState(
        targetValue = if (isFocused) 52.dp else 44.dp,
        animationSpec = spring(stiffness = 800f),
        label = "barHeight",
    )

    val bgColor by animateColorAsState(
        targetValue = if (isFocused) KernelSurfaceVariant else KernelSurfaceVariant.copy(alpha = 0.8f),
        label = "bgColor",
    )

    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor),
        ) {
            if (isFocused) {
                // Focused state: editable text field
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = KernelMuted,
                        modifier = Modifier.size(18.dp),
                    )
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .focusRequester(focusRequester),
                        textStyle = MaterialTheme.typography.labelLarge.copy(
                            color = KernelWhite,
                        ),
                        cursorBrush = SolidColor(KernelAccent),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                onUrlSubmitted(inputText)
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            },
                        ),
                    )
                    if (inputText.isNotEmpty()) {
                        IconButton(
                            onClick = { inputText = "" },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = KernelMuted,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }
            } else {
                // Idle state: display URL
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .clickable {
                            inputText = url
                            isFocused = true
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (url.startsWith("https://")) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Secure",
                            tint = KernelAccent,
                            modifier = Modifier.size(14.dp).padding(end = 4.dp),
                        )
                    }
                    Text(
                        text = if (url == "about:blank") "Search or enter URL" else UrlUtils.displayUrl(url),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (url == "about:blank") KernelMuted else KernelWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Loading progress indicator
            if (isLoading && progress < 100) {
                val animatedProgress by animateFloatAsState(
                    targetValue = progress / 100f,
                    label = "progress",
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(animatedProgress)
                        .height(2.dp)
                        .background(KernelAccent),
                )
            }
        }
    }

    // Handle focus loss
    LaunchedEffect(isFocused) {
        if (!isFocused) {
            keyboardController?.hide()
        }
    }

    // Track focus state
    Box(
        modifier = Modifier.onFocusChanged { state ->
            if (!state.isFocused && isFocused) {
                isFocused = false
            }
        },
    )
}
