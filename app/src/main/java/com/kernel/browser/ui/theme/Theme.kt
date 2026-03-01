package com.kernel.browser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import android.app.Activity
import androidx.core.view.WindowCompat

private val KernelDarkColorScheme = darkColorScheme(
    primary = KernelAccent,
    onPrimary = KernelBlack,
    secondary = KernelAccentDim,
    onSecondary = KernelBlack,
    tertiary = KernelSuccess,
    background = KernelBlack,
    onBackground = KernelWhite,
    surface = KernelSurface,
    onSurface = KernelWhite,
    surfaceVariant = KernelSurfaceVariant,
    onSurfaceVariant = KernelTextSecondary,
    outline = KernelBorder,
    error = KernelError,
    onError = KernelWhite,
)

private val KernelLightColorScheme = lightColorScheme(
    primary = KernelAccentDim,
    onPrimary = KernelBlack,
    secondary = KernelAccent,
    onSecondary = KernelBlack,
    tertiary = KernelSuccess,
    background = KernelLightBackground,
    onBackground = KernelLightText,
    surface = KernelLightSurface,
    onSurface = KernelLightText,
    surfaceVariant = KernelLightSurfaceVariant,
    onSurfaceVariant = KernelLightTextSecondary,
    outline = KernelLightBorder,
    error = KernelError,
    onError = KernelWhite,
)

@Composable
fun KernelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) KernelDarkColorScheme else KernelLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = KernelTypography,
        content = content,
    )
}
