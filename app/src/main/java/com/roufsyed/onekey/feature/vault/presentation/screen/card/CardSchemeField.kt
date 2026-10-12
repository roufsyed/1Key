package com.roufsyed.onekey.feature.vault.presentation.screen.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roufsyed.onekey.core.domain.card.CardScheme
import com.roufsyed.onekey.core.presentation.lockaware.LockAwareExposedDropdownMenu
import com.roufsyed.onekey.core.presentation.lockaware.LockAwareOutlinedTextField

/**
 * Value editor for the "Card Scheme" custom field: a dropdown of the known schemes
 * plus a "Custom" option that reveals a free-text field. The stored value is the
 * scheme's display name or the custom text, so the field stays ordinary free text
 * in the data model and in backups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardSchemeField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val known = remember(value) { CardScheme.fromValue(value) }
    // "Custom" stays selected while its text is typed, including while it is still
    // empty; a value that names no known scheme is custom by definition.
    var customChosen by rememberSaveable { mutableStateOf(known == null && value.isNotBlank()) }
    val isCustom = customChosen || (known == null && value.isNotBlank())
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = when {
        isCustom -> "Custom"
        known != null -> known.displayName
        else -> ""
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            LockAwareOutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("Card scheme") },
                placeholder = { Text("Select a card scheme") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                    .fillMaxWidth(),
            )
            LockAwareExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                CardScheme.options.forEach { scheme ->
                    DropdownMenuItem(
                        text = { Text(scheme.displayName) },
                        onClick = {
                            customChosen = false
                            onValueChange(scheme.displayName)
                            expanded = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Custom") },
                    onClick = {
                        customChosen = true
                        // Start the custom text empty rather than pre-filled with a scheme name.
                        if (known != null) onValueChange("")
                        expanded = false
                    },
                )
            }
        }
        if (isCustom) {
            LockAwareOutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("Custom scheme") },
                placeholder = { Text("e.g. Maestro, JCB, UnionPay") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
