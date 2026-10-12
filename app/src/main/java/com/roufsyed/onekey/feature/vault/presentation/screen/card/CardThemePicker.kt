package com.roufsyed.onekey.feature.vault.presentation.screen.card

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roufsyed.onekey.core.domain.model.CardTheme
import com.roufsyed.onekey.core.presentation.card.palette

/**
 * Fifteen swatches in a wrapping row, all visible at once (no horizontal scroll
 * inside the vertically scrolling editor). Each swatch is a 40 dp circle inside a
 * 48 dp touch target; the selected one carries a primary-coloured ring plus a check
 * glyph, so selection is shape as well as colour. The header names the current
 * theme so colour is never the only cue.
 */
@Composable
fun CardThemePicker(
    selected: CardTheme,
    onSelect: (CardTheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Card appearance - ${selected.displayName}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTheme.entries.forEach { theme ->
                Swatch(theme = theme, selected = theme == selected, onClick = { onSelect(theme) })
            }
        }
    }
}

@Composable
private fun Swatch(theme: CardTheme, selected: Boolean, onClick: () -> Unit) {
    val palette = remember(theme) { theme.palette() }
    val ring = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = theme.displayName }
            .then(if (selected) Modifier.border(2.dp, ring, CircleShape) else Modifier)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(palette.background)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = palette.onCard,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
