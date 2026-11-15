// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wuerl.easyconnect.ui.theme.AppTheme

enum class StepStatus { Todo, Active, Working, Done, Error }

/**
 * One step of the vertical setup timeline: a numbered badge, a connector line and a card. The
 * line is drawn behind the row instead of using intrinsic measurements, which would clip
 * wrapping content such as a FlowRow of buttons.
 */
@Composable
fun StepItem(
    number: Int,
    status: StepStatus,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val lineColor by animateColorAsState(
        if (status == StepStatus.Done) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else MaterialTheme.colorScheme.outlineVariant,
        label = "connector",
    )
    Row(
        modifier.drawBehind {
            if (!isLast) {
                val x = 18.dp.toPx()
                val top = (BADGE_TOP + BADGE_SIZE + 6.dp).toPx()
                val bottom = size.height - 2.dp.toPx()
                if (bottom > top) {
                    drawLine(lineColor, Offset(x, top), Offset(x, bottom), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        },
    ) {
        Column(Modifier.width(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(BADGE_TOP))
            StepBadge(number, status)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 16.dp)) { content() }
    }
}

private val BADGE_TOP = 18.dp
private val BADGE_SIZE = 32.dp

@Composable
fun StepBadge(number: Int, status: StepStatus) {
    val colors = MaterialTheme.colorScheme
    val (container, onContainer) = when (status) {
        StepStatus.Done -> colors.primary to colors.onPrimary
        StepStatus.Active, StepStatus.Working -> colors.primaryContainer to colors.onPrimaryContainer
        StepStatus.Error -> colors.errorContainer to colors.onErrorContainer
        StepStatus.Todo -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    val background by animateColorAsState(container, label = "badge")
    Surface(shape = CircleShape, color = background, contentColor = onContainer, modifier = Modifier.size(BADGE_SIZE)) {
        Box(contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = status,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith fadeOut() },
                label = "badge-content",
            ) { s ->
                when (s) {
                    StepStatus.Done -> Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    StepStatus.Error -> Icon(Icons.Rounded.PriorityHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                    StepStatus.Working -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = onContainer,
                        trackColor = Color.Transparent,
                    )
                    else -> Text("$number", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** The card of a step: icon, title and optional trailing actions, then the body. */
@Composable
fun StepCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                if (trailing != null) trailing() else Spacer(Modifier.width(12.dp))
            }
            Column(
                Modifier.padding(end = 12.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** A line of status text with a leading icon or spinner. */
@Composable
fun StatusLine(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    working: Boolean = false,
    secondary: String? = null,
) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                working -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                icon != null -> Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            secondary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
