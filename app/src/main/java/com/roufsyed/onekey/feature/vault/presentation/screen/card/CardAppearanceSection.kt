package com.roufsyed.onekey.feature.vault.presentation.screen.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roufsyed.onekey.core.domain.card.CardFace
import com.roufsyed.onekey.core.domain.model.CardTheme
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.CustomField

/**
 * Editor section under the Title field for card types: a live preview of the card
 * built from the hoisted title and custom-field state, a Flip button (no tap-to-flip
 * in the editor), and the theme swatches.
 *
 * The preview mirrors each custom-field row's own reveal state: a sensitive slot
 * shows its real value only while that row's eye is open ([revealedFieldIndices]),
 * otherwise the masked rendering. Flipping the preview changes no row state.
 */
@Composable
fun CardAppearanceSection(
    type: CredentialType,
    title: String,
    customFields: List<CustomField>,
    revealedFieldIndices: Set<Int>,
    theme: CardTheme,
    onThemeSelected: (CardTheme) -> Unit,
    stateKey: String,
    modifier: Modifier = Modifier,
) {
    val model = remember(type, title, customFields) { CardDisplayModel.build(type, title, customFields) }
    var previewFace by rememberSaveable(stateKey) { mutableStateOf(CardFace.FRONT) }
    val flipState = rememberCardFlipState(stateKey, previewFace)
    val revealedSlots = remember(model, revealedFieldIndices) {
        model.slots.values.filter { it.fieldIndex in revealedFieldIndices }.mapTo(HashSet()) { it.slot }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CredentialFlipCard(
                model = model,
                theme = theme,
                face = previewFace,
                revealedSlots = revealedSlots,
                showPlaceholders = true,
                onClick = null,
                flipState = flipState,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = { previewFace = previewFace.other() },
                contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
            ) {
                Icon(Icons.Default.Flip, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(if (previewFace == CardFace.FRONT) "Show back" else "Show front")
            }
        }
        CardThemePicker(selected = theme, onSelect = onThemeSelected)
    }
}
