package com.kernel.browser.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

@Composable
fun NoiseBackground(
    modifier: Modifier = Modifier,
    baseColor: Color = Color(0xFF0A0A0A),
    noiseAlpha: Float = 0.06f,
    density: Float = 0.3f,
) {
    val seed = remember { Random.nextInt() }

    Canvas(modifier = modifier) {
        drawRect(baseColor)

        val rng = Random(seed)
        val stepX = (1f / density).toInt().coerceAtLeast(2)
        val stepY = (1f / density).toInt().coerceAtLeast(2)

        var x = 0
        while (x < size.width.toInt()) {
            var y = 0
            while (y < size.height.toInt()) {
                if (rng.nextFloat() < density) {
                    val brightness = rng.nextFloat()
                    drawCircle(
                        color = Color.White.copy(alpha = brightness * noiseAlpha),
                        radius = 0.5f,
                        center = Offset(x.toFloat(), y.toFloat()),
                    )
                }
                y += stepY
            }
            x += stepX
        }
    }
}
