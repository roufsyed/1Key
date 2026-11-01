package com.roufsyed.onekey.feature.passkey.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roufsyed.onekey.core.presentation.lockaware.PasswordUnlockSection
import com.roufsyed.onekey.core.presentation.lockaware.PinUnlockSection
import com.roufsyed.onekey.core.presentation.util.rememberCanUseBiometric
import com.roufsyed.onekey.feature.auth.presentation.viewmodel.AuthUiState
import com.roufsyed.onekey.feature.passkey.presentation.PasskeyCeremonyViewModel.CreateForm

/**
 * The passkey ceremony screens. Every text field and dialog comes from the
 * lock-aware package through [PinUnlockSection] and [PasswordUnlockSection];
 * nothing here uses a raw Material text field or dialog.
 */

@Composable
internal fun PasskeyLoadingSurface() {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator() }
}

/**
 * Fresh user verification for a site that asked for it while the vault was
 * already unlocked. Biometric first when enabled and available, then PIN,
 * then master password. The checks are the side-effect-free verifiers, so a
 * success here never re-emits an unlock to the rest of the app.
 */
@Composable
internal fun UserVerificationSurface(
    target: String,
    error: String?,
    busy: Boolean,
    biometricEnabled: Boolean,
    isPinSetup: Boolean,
    onBiometric: () -> Unit,
    onPin: (String) -> Unit,
    onPassword: (CharArray) -> Unit,
    onCancel: () -> Unit,
    cancelLabel: String,
) {
    val canUseBiometric = rememberCanUseBiometric()
    val biometricAvailable = biometricEnabled && canUseBiometric
    var usePassword by rememberSaveable { mutableStateOf(!isPinSetup) }
    var autoTriggered by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(biometricAvailable) {
        if (biometricAvailable && !autoTriggered) {
            autoTriggered = true
            onBiometric()
        }
    }

    val sectionState: AuthUiState = when {
        busy -> AuthUiState.Loading
        error != null -> AuthUiState.Error(error)
        else -> AuthUiState.Idle
    }

    CeremonyScaffold(
        headline = "Confirm it's you.",
        target = target,
        icon = Icons.Default.Lock,
    ) {
        if (usePassword || !isPinSetup) {
            PasswordUnlockSection(
                state = sectionState,
                lockoutUntilMs = null,
                onPasswordSubmit = onPassword,
                submitButtonLabel = "Confirm",
            )
            if (isPinSetup) {
                TextButton(onClick = { usePassword = false }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Use PIN instead") }
            }
        } else {
            PinUnlockSection(
                state = sectionState,
                lockoutUntilMs = null,
                onPinSubmit = onPin,
                onFallbackToPassword = { usePassword = true },
                fallbackLabel = "Use master password",
            )
        }
        if (biometricAvailable) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBiometric, enabled = !busy) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Use biometric", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(cancelLabel) }
    }
}

@Composable
internal fun CreatePasskeySurface(
    form: CreateForm,
    onChooseTarget: (String?) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    CeremonyScaffold(
        headline = "Save a passkey.",
        target = form.rpName.takeIf { it != form.rpId }?.let { "$it (${form.rpId})" } ?: form.rpId,
        icon = Icons.Default.Key,
    ) {
        DetailLine(label = "Account", value = listOf(form.userDisplayName, form.userName).filter { it.isNotBlank() }.distinct().joinToString(" · "))
        DetailLine(
            label = if (form.requesterIsApp) "Requested by app" else "Requested by",
            value = form.requesterLabel,
        )
        if (form.requesterIsApp) {
            Text(
                "1Key verified this app's signature. The passkey will work in this app and in trusted browsers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        form.replacesUserName?.let {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "You already have a passkey for this account here. Saving replaces it, which is what the site asked for.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Save to", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TargetRow(
            title = "New entry",
            subtitle = form.rpName.ifBlank { form.rpId },
            selected = form.selectedTargetId == null,
            onClick = { onChooseTarget(null) },
        )
        form.targets.forEach { target ->
            TargetRow(
                title = target.title,
                subtitle = target.username,
                selected = form.selectedTargetId == target.id,
                onClick = { onChooseTarget(target.id) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("Save passkey") }
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel") }
    }
}

@Composable
internal fun ExplainSurface(topic: ExplainTopic, onClose: () -> Unit) {
    val (title, body) = when (topic) {
        ExplainTopic.UNKNOWN_BROWSER ->
            "This browser isn't recognised" to
                "1Key only answers passkey requests from browsers it can identify, because a browser vouches for which website is asking. " +
                "If you installed this browser on purpose, add it under Settings, Passkeys, Trusted browsers. " +
                "1Key never connects to the internet, so it cannot look this up for you."
        ExplainTopic.MULTIPLE_SIGNERS ->
            "This app can't be verified" to
                "The app is signed by several keys at once, so 1Key cannot pin a single identity to a passkey. Sign in through your browser instead."
        ExplainTopic.UNSUPPORTED_ALGORITHM ->
            "This site wants a key type 1Key doesn't make" to
                "1Key creates ES256 passkeys, the standard type every major site and passkey manager uses. " +
                "This site's request left ES256 out, so there is nothing 1Key can offer it. Your vault is fine. " +
                "Sign in another way, or ask the site to accept ES256."
    }
    CeremonyScaffold(headline = title, target = null, icon = Icons.Default.Lock) {
        Text(body, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
    }
}

@Composable
internal fun PasskeyErrorSurface(message: String, onClose: () -> Unit) {
    CeremonyScaffold(headline = "That didn't work.", target = null, icon = Icons.Default.Lock) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
    }
}

// ── building blocks ───────────────────────────────────────────────────────────

@Composable
private fun CeremonyScaffold(
    headline: String,
    target: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Text(headline, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        if (target != null) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text(target, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    if (value.isBlank()) return
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TargetRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
