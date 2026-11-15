// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.wuerl.easyconnect.ui.theme.AppTheme

enum class LedMode { Idle, Normal, BlinkBlue, Success, Error }

/**
 * A stylised wall controller: a rounded panel, the mode button and an LED ring. While waiting
 * for pairing mode the ring blinks blue — what the user should see on the real controller —
 * and a ripple around the button hints "press and hold".
 */
@Composable
fun ControllerIllustration(mode: LedMode, description: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val extended = AppTheme.extended
    val ledColor by animateColorAsState(
        when (mode) {
            LedMode.Idle -> colors.outline
            LedMode.Normal -> Color.White
            LedMode.BlinkBlue -> extended.pairingBlue
            LedMode.Success -> extended.success
            LedMode.Error -> colors.error
        },
        label = "led",
    )
    val transition = rememberInfiniteTransition(label = "controller")
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1_000
                1f at 0
                1f at 420
                0.12f at 520
                0.12f at 900
                1f at 1_000
            },
        ),
        label = "blink",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400), RepeatMode.Reverse),
        label = "breathe",
    )
    val ripple by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing)),
        label = "ripple",
    )
    val panel = colors.surfaceContainerHighest
    val panelEdge = colors.outlineVariant
    val button = colors.surfaceContainerLowest
    val buttonEdge = colors.outline.copy(alpha = 0.5f)

    Canvas(modifier.size(width = 156.dp, height = 132.dp).semantics { contentDescription = description }) {
        val center = Offset(size.width / 2, size.height / 2)
        val panelSize = 108.dp.toPx()
        val corner = 28.dp.toPx()
        val topLeft = Offset(center.x - panelSize / 2, center.y - panelSize / 2)
        drawRoundRect(panel, topLeft, Size(panelSize, panelSize), CornerRadius(corner))
        drawRoundRect(panelEdge, topLeft, Size(panelSize, panelSize), CornerRadius(corner), style = Stroke(1.dp.toPx()))

        val ringRadius = 30.dp.toPx()
        val buttonRadius = 21.dp.toPx()
        val alpha = when (mode) {
            LedMode.BlinkBlue -> blink
            LedMode.Normal -> breathe
            else -> 1f
        }
        // soft glow behind the ring
        if (mode != LedMode.Idle) {
            drawCircle(
                Brush.radialGradient(
                    listOf(ledColor.copy(alpha = 0.35f * alpha), Color.Transparent),
                    center = center,
                    radius = ringRadius * 1.9f,
                ),
                radius = ringRadius * 1.9f,
                center = center,
            )
        }
        if (mode == LedMode.Normal || mode == LedMode.Idle) {
            // a hairline around the (white or dark) ring keeps it visible on any panel colour
            drawCircle(colors.outline.copy(alpha = 0.6f), radius = ringRadius, center = center, style = Stroke(6.dp.toPx()))
        }
        drawCircle(ledColor.copy(alpha = alpha), radius = ringRadius, center = center, style = Stroke(4.dp.toPx()))
        // the "press and hold" ripple
        if (mode == LedMode.BlinkBlue) {
            drawCircle(
                ledColor.copy(alpha = 0.5f * (1f - ripple)),
                radius = buttonRadius * (0.6f + 0.4f * ripple),
                center = center,
                style = Stroke(2.dp.toPx()),
            )
        }
        drawCircle(button, radius = buttonRadius, center = center)
        drawCircle(buttonEdge, radius = buttonRadius, center = center, style = Stroke(1.dp.toPx()))
        // the mode symbol on the button: three short bars
        val bar = 10.dp.toPx()
        val gap = 5.dp.toPx()
        for (i in -1..1) {
            val y = center.y + i * gap
            drawLine(
                colors.onSurfaceVariant.copy(alpha = 0.7f),
                Offset(center.x - bar / 2, y),
                Offset(center.x + bar / 2, y),
                strokeWidth = 2.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}
