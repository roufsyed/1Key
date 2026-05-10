package com.onekey.feature.settings.presentation.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp

@Composable
internal fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
}

// Wraps a settings row so that when [isHighlighted] becomes true the nearest scrollable
// ancestor scrolls the row into view and a brief colour pulse draws the user's eye to it.
// When [isHighlighted] is false this composable is a zero-overhead passthrough.
//
// The pulse is painted *on top* of the row content via [drawWithContent] rather than as
// a [Modifier.background]. The reason: M3 [ListItem] (the typical child of this composable)
// defaults its `containerColor` to `colorScheme.surface`, an opaque colour. A background
// modifier on the outer Box ends up drawn behind that opaque container — invisible to the
// user. Drawing the pulse on top guarantees it is actually seen.
//
// Peak alpha is held at ~0.45 (not 1.0) so the row text stays readable through the flash,
// and the colour fades out smoothly instead of snapping to a solid block.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HighlightableRow(
    isHighlighted: Boolean,
    onHighlightConsumed: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val highlightAlpha = remember { Animatable(0f) }
    val highlightColor = MaterialTheme.colorScheme.primaryContainer

    LaunchedEffect(isHighlighted) {
        if (isHighlighted) {
            bringIntoViewRequester.bringIntoView()
            highlightAlpha.snapTo(0.45f)
            kotlinx.coroutines.delay(800)
            highlightAlpha.animateTo(0f, tween(durationMillis = 1200))
            onHighlightConsumed()
        }
    }

    Box(
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .drawWithContent {
                drawContent()
                if (highlightAlpha.value > 0f) {
                    drawRect(color = highlightColor, alpha = highlightAlpha.value)
                }
            },
    ) {
        content()
    }
}

@Composable
internal fun PrivacyLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
