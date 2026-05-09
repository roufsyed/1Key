package com.onekey.feature.settings.presentation.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
            highlightAlpha.snapTo(1f)
            kotlinx.coroutines.delay(800)
            highlightAlpha.animateTo(0f, tween(durationMillis = 1200))
            onHighlightConsumed()
        }
    }

    Box(
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .background(highlightColor.copy(alpha = highlightAlpha.value)),
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

@Composable
internal fun ExpandableInfoCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(title) },
                leadingContent = { Icon(icon, null) },
                trailingContent = {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                    )
                },
                modifier = Modifier.clickable { expanded = !expanded },
            )
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
            }
        }
    }
}
