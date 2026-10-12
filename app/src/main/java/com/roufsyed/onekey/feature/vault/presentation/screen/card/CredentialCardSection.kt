package com.roufsyed.onekey.feature.vault.presentation.screen.card

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.roufsyed.onekey.core.domain.card.CardFace
import com.roufsyed.onekey.core.domain.card.CardSlot
import com.roufsyed.onekey.core.domain.model.CardTheme
import kotlinx.coroutines.delay

/** Revealed values re-mask after this long, matching the clipboard's 30 s auto-clear window. */
const val CARD_REVEAL_TIMEOUT_MS = 30_000L

/**
 * View-mode card: the flip card, its action row and the caller's banner slot.
 *
 * State rules (agreed in the plan's security review):
 *  - the logical face survives rotation and process death (`rememberSaveable`);
 *    it is a non-secret;
 *  - the reveal flag is plain `remember`, so it resets on rotation, lock, back
 *    navigation and process death - `rememberSaveable` would survive the lock and
 *    unlock round trip when "restore last screen" is on;
 *  - every flip re-masks in the same handler that changes the face, so revealed
 *    digits never rotate and the number and the CVV are never visible together;
 *  - a reveal expires after [CARD_REVEAL_TIMEOUT_MS] and on ON_STOP (home, app
 *    switcher, screen off), but not on a mere focus change in split screen.
 *
 * Copying is delegated to the ViewModel so the clipboard payload is resolved from
 * the live credential by the same binder the card draws with. The buttons copy a
 * form-ready value (digits only for numbers); a long press on a drawn value copies
 * that field exactly as stored.
 */
@Composable
fun CredentialCardSection(
    model: CardDisplayModel,
    theme: CardTheme,
    stateKey: String,
    onCopy: (slot: CardSlot, exact: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    belowActions: @Composable () -> Unit = {},
) {
    var cardFace by rememberSaveable(stateKey) { mutableStateOf(CardFace.FRONT) }
    var showCardDetails by remember(stateKey) { mutableStateOf(false) }

    LaunchedEffect(showCardDetails) {
        if (showCardDetails) {
            delay(CARD_REVEAL_TIMEOUT_MS)
            showCardDetails = false
        }
    }
    LifecycleStartEffect(stateKey) {
        onStopOrDispose { showCardDetails = false }
    }

    val haptic = LocalHapticFeedback.current
    val flipState = rememberCardFlipState(stateKey, cardFace)
    val revealedSlots = remember(model, cardFace, showCardDetails) {
        if (showCardDetails) model.maskedOn(cardFace).mapTo(HashSet()) { it.slot } else emptySet()
    }

    fun flip() {
        haptic.performHapticFeedback(
            if (cardFace == CardFace.FRONT) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
        )
        showCardDetails = false
        cardFace = cardFace.other()
    }

    // Long press on a drawn value copies that field exactly as stored; a tap on it
    // flips like a tap anywhere else on the card.
    val slotGestures = remember(haptic, onCopy) {
        CardSlotGestures(
            onTap = ::flip,
            onLongPress = { slot ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onCopy(slot, true)
            },
        )
    }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CredentialFlipCard(
                model = model,
                theme = theme,
                face = cardFace,
                revealedSlots = revealedSlots,
                showPlaceholders = false,
                onClick = ::flip,
                flipState = flipState,
                modifier = Modifier.fillMaxWidth(),
                slotGestures = slotGestures,
            )
        }
        CardActionRow(
            model = model,
            face = cardFace,
            revealed = showCardDetails,
            onFlip = ::flip,
            onToggleReveal = { showCardDetails = !showCardDetails },
            onCopy = { slot ->
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onCopy(slot, false)
            },
        )
    }
    belowActions()
}

/**
 * Shown instead of the card when a card-type credential has none of its slots
 * filled. Same flat row language as the other detail rows; "Add" opens the editor
 * without pre-adding fields (the 20-field cap may already be reached).
 */
@Composable
fun CardEmptyStateRow(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val hint = "Add bank name, name on card, card number, expiry and CVV to show this credential as a card"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Card", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(hint, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onAdd) { Text("Add") }
    }
}
