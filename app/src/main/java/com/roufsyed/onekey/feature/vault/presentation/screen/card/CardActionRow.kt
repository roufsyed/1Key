package com.roufsyed.onekey.feature.vault.presentation.screen.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.roufsyed.onekey.core.domain.card.CardFace
import com.roufsyed.onekey.core.domain.card.CardSlot

@Immutable
private data class CardAction(
    val description: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * The 48 dp row under the card: Flip, then Show/Hide (only while the visible face
 * has a masked slot), then one Copy button per bound value slot on that face
 * (regardless of its sensitive flag). Always icon-only and horizontally centred, so
 * the row looks the same on both faces; every button carries its full action as
 * its content description.
 */
@Composable
fun CardActionRow(
    model: CardDisplayModel,
    face: CardFace,
    revealed: Boolean,
    onFlip: () -> Unit,
    onToggleReveal: () -> Unit,
    onCopy: (CardSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val masked = model.maskedOn(face)
    val copyable = model.copyableOn(face)
    val actions = remember(model, face, revealed) {
        buildList {
            add(CardAction("Flip card", Icons.Default.Flip, onFlip))
            if (masked.isNotEmpty()) {
                val subject = if (masked.size == 1) shortName(masked.single().slot) else "details"
                val verb = if (revealed) "Hide" else "Show"
                add(
                    CardAction(
                        description = "$verb card $subject",
                        icon = if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        onClick = onToggleReveal,
                    )
                )
            }
            copyable.forEach { display ->
                val slot = display.slot
                add(CardAction("Copy card ${shortName(slot)}", Icons.Default.ContentCopy) { onCopy(slot) })
            }
        }
    }

    Row(
        modifier = modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(ACTION_SPACING_DP.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            IconButton(onClick = action.onClick) {
                Icon(action.icon, contentDescription = action.description)
            }
        }
    }
}

private const val ACTION_SPACING_DP = 8

private fun shortName(slot: CardSlot): String = when (slot) {
    CardSlot.CARD_NUMBER -> "number"
    CardSlot.CVV -> "CVV"
    CardSlot.EXPIRY -> "expiry"
    CardSlot.CARDHOLDER -> "name"
    CardSlot.SCHEME -> "scheme"
    CardSlot.BANK_NAME -> "bank"
}
