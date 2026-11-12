package com.nuvio.tv.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The purple-to-black diagonal gradient the sign-in screen uses behind the Nuvio logo, so the launcher
 * setup looks like the rest of first-run instead of a plain page.
 */
fun Modifier.brandGradientBackground(): Modifier = drawWithCache {
    val angleRadians = 122.0 * PI / 180.0
    val directionX = sin(angleRadians).toFloat()
    val directionY = (-cos(angleRadians)).toFloat()
    val halfLength = (abs(size.width * directionX) + abs(size.height * directionY)) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val start = Offset(center.x - directionX * halfLength, center.y - directionY * halfLength)
    val end = Offset(center.x + directionX * halfLength, center.y + directionY * halfLength)
    val brush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to Color(0xFF21113B),
            0.14f to Color(0xFF21113B),
            0.26f to Color(0xFF1A0E2F),
            0.36f to Color(0xFF130A23),
            0.48f to Color(0xFF0A060F),
            0.60f to Color(0xFF050408),
            0.70f to Color.Black,
            1f to Color.Black
        ),
        start = start,
        end = end
    )
    onDrawBehind { drawRect(brush = brush) }
}
