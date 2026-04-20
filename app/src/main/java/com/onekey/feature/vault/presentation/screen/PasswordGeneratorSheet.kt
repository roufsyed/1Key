@file:OptIn(ExperimentalMaterial3Api::class)

package com.onekey.feature.vault.presentation.screen

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.model.PasswordConfig
import com.onekey.core.domain.model.PasswordStrength
import com.onekey.core.domain.model.PasswordType
import com.onekey.feature.vault.presentation.viewmodel.PasswordGeneratorViewModel

@Composable
fun PasswordGeneratorSheet(
    onUsePassword: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: PasswordGeneratorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(Unit) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Password Generator", style = MaterialTheme.typography.titleLarge)

            // Type selector
            TypeSelector(
                selected = state.config.type,
                onSelect = { viewModel.updateConfig(state.config.copy(type = it)) },
            )

            // Password preview
            PasswordPreview(
                password = state.password,
                onRegenerate = viewModel::regenerate,
                onCopy = viewModel::copyToClipboard,
            )

            // Strength meter
            StrengthMeter(strength = state.strength)

            HorizontalDivider()

            // Type-specific controls
            when (state.config.type) {
                PasswordType.RANDOM -> RandomControls(
                    config = state.config,
                    onConfigChange = viewModel::updateConfig,
                )
                PasswordType.MEMORABLE -> MemorableControls(
                    config = state.config,
                    onConfigChange = viewModel::updateConfig,
                )
                PasswordType.PIN -> PinControls(
                    config = state.config,
                    onConfigChange = viewModel::updateConfig,
                )
            }

            HorizontalDivider()

            Button(
                onClick = {
                    onUsePassword(viewModel.currentPassword())
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.password.isNotEmpty(),
            ) {
                Text("Use This Password")
            }
        }
    }
}

@Composable
private fun TypeSelector(selected: PasswordType, onSelect: (PasswordType) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        PasswordType.entries.forEachIndexed { index, type ->
            SegmentedButton(
                selected = selected == type,
                onClick = { onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index, PasswordType.entries.size),
                label = { Text(type.label) },
            )
        }
    }
}

@Composable
private fun PasswordPreview(password: String, onRegenerate: () -> Unit, onCopy: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = password.ifEmpty { "Generating…" },
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                    letterSpacing = 1.sp,
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onCopy) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy password")
                }
                IconButton(onClick = onRegenerate) {
                    Icon(Icons.Default.Refresh, contentDescription = "Regenerate")
                }
            }
        }
    }
}

@Composable
private fun StrengthMeter(strength: PasswordStrength) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Strength", style = MaterialTheme.typography.labelMedium)
            Text(
                strength.label,
                style = MaterialTheme.typography.labelMedium,
                color = strengthColor(strength),
            )
        }
        LinearProgressIndicator(
            progress = { strength.fraction },
            modifier = Modifier.fillMaxWidth(),
            color = strengthColor(strength),
        )
    }
}

@Composable
private fun strengthColor(strength: PasswordStrength) = when (strength) {
    PasswordStrength.WEAK -> MaterialTheme.colorScheme.error
    PasswordStrength.FAIR -> MaterialTheme.colorScheme.tertiary
    PasswordStrength.STRONG -> MaterialTheme.colorScheme.primary
    PasswordStrength.VERY_STRONG -> MaterialTheme.colorScheme.primary
}

@Composable
private fun RandomControls(config: PasswordConfig, onConfigChange: (PasswordConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LengthSlider(
            label = "Length: ${config.length}",
            value = config.length,
            range = 4f..128f,
            onValue = { onConfigChange(config.copy(length = it)) },
        )
        ToggleRow("Uppercase (A–Z)", config.uppercase) {
            val atLeastOne = !config.uppercase || config.lowercase || config.digits || config.symbols
            if (atLeastOne) onConfigChange(config.copy(uppercase = !config.uppercase))
        }
        ToggleRow("Lowercase (a–z)", config.lowercase) {
            val atLeastOne = config.uppercase || !config.lowercase || config.digits || config.symbols
            if (atLeastOne) onConfigChange(config.copy(lowercase = !config.lowercase))
        }
        ToggleRow("Numbers (0–9)", config.digits) {
            val atLeastOne = config.uppercase || config.lowercase || !config.digits || config.symbols
            if (atLeastOne) onConfigChange(config.copy(digits = !config.digits))
        }
        ToggleRow("Symbols (!@#…)", config.symbols) {
            val atLeastOne = config.uppercase || config.lowercase || config.digits || !config.symbols
            if (atLeastOne) onConfigChange(config.copy(symbols = !config.symbols))
        }
        ToggleRow("Avoid ambiguous (0,O,l,I)", config.avoidAmbiguous) {
            onConfigChange(config.copy(avoidAmbiguous = !config.avoidAmbiguous))
        }
    }
}

@Composable
private fun MemorableControls(config: PasswordConfig, onConfigChange: (PasswordConfig) -> Unit) {
    LengthSlider(
        label = "Words: ${config.wordCount}",
        value = config.wordCount,
        range = 2f..10f,
        onValue = { onConfigChange(config.copy(wordCount = it)) },
    )
}

@Composable
private fun PinControls(config: PasswordConfig, onConfigChange: (PasswordConfig) -> Unit) {
    LengthSlider(
        label = "Digits: ${config.pinLength}",
        value = config.pinLength,
        range = 4f..12f,
        onValue = { onConfigChange(config.copy(pinLength = it)) },
    )
}

@Composable
private fun LengthSlider(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Int) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onValue(it.toInt()) },
            valueRange = range,
            steps = (range.endInclusive - range.start - 1).toInt(),
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

private val PasswordType.label: String
    get() = when (this) {
        PasswordType.RANDOM -> "Random"
        PasswordType.MEMORABLE -> "Memorable"
        PasswordType.PIN -> "PIN"
    }
