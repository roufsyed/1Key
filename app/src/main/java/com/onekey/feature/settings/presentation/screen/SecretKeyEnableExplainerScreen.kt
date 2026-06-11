package com.onekey.feature.settings.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.onekey.core.presentation.util.oneKeyTopBarColors

/**
 * Pre-enable explainer for the Secret Key feature. Shown when the user taps
 * "Enable Secret Key" from [SettingsSecretKeyScreen] state A (feature off).
 * The screen explains the threat model, the trade-offs, and what the user
 * has to do to finish enable (save the kit before they leave). On Continue
 * the parent navigates to the master-password reauth dialog and then runs
 * [com.onekey.feature.secretkey.domain.SecretKeyEnableUseCase].
 *
 * Why a full-screen explainer (and not a dialog):
 *  - The body covers four distinct points (what SK is, why it matters,
 *    what we ask of the user, what happens if they lose the kit). A
 *    LockAwareDialog can host this much copy but the reading experience is
 *    cramped and the "Continue" button is too easy to tap-through.
 *  - The full-screen surface also lets the user navigate Back if they
 *    decide they want to read more elsewhere in Settings before enabling.
 *
 * Stateless: this composable surfaces user intent ([onContinue] /
 * [onCancel]); no ViewModel reads. The parent screen owns the enable
 * lifecycle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretKeyEnableExplainerScreen(
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                colors = oneKeyTopBarColors(),
                title = { Text("Enable Secret Key") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Hero card. Sets the tone before the user reads any details.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "Two-factor key derivation",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        "Secret Key is a 128-bit random value 1Key generates on this " +
                            "device and mixes into your master password before deriving " +
                            "the vault key. Without both your master password AND your " +
                            "Secret Key, nobody can derive the verifier - even if they " +
                            "steal an encrypted backup file.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Why this matters block.
            Text(
                "Why this matters",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExplainerRow(
                icon = Icons.Default.Security,
                title = "Defeats password-only attacks",
                body = "Argon2id is strong, but it still derives a key from one secret. " +
                    "If an attacker steals an encrypted backup file and you have a " +
                    "weak or reused master password, they have a fighting chance to " +
                    "brute-force it. With Secret Key on, they would also need the " +
                    "16 bytes from your Emergency Kit.",
            )
            ExplainerRow(
                icon = Icons.Default.Backup,
                title = "Travels with your backups",
                body = "Every V5 backup file embeds a flag saying \"this needs the " +
                    "Secret Key.\" The file does NOT carry the Secret Key itself - " +
                    "we never write the SK to disk except wrapped under Android's " +
                    "Keystore. When you restore on a new device, you provide the SK " +
                    "by scanning your Emergency Kit's QR code.",
            )
            ExplainerRow(
                icon = Icons.Default.PrivacyTip,
                title = "Local only",
                body = "The Secret Key never leaves your device unless you choose " +
                    "to print or save your Emergency Kit. 1Key has no server-side " +
                    "component, no cloud account, and no telemetry.",
            )

            Spacer(Modifier.height(8.dp))

            // What we ask of the user. Spell out the kit save BEFORE the
            // user commits, not as a fait accompli on the next screen.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "What we ask of you",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Text(
                        "On the next screen 1Key generates your Secret Key and " +
                            "renders a printable Emergency Kit. You must save the " +
                            "kit before you continue - print it, save the PDF to a " +
                            "device you trust, or both. We will not let you finish " +
                            "until the \"I have saved my kit\" checkbox is ticked.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Pick a save location offline from this device. A printed " +
                            "page in a safe place is the canonical recommendation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Loss / recovery warning. Pinned in error styling because a
            // user who skips reading this can lose data later.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "What happens if you lose your kit",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "If this device is wiped or replaced and you do not have " +
                            "your Emergency Kit, your V5 backups become unrestorable - " +
                            "the Secret Key is required to decrypt them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "We strongly recommend printing the kit and storing it the " +
                            "way you would store a passport or birth certificate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Footer action row. Continue is the primary; Cancel returns
            // to SettingsSecretKeyScreen state A unchanged.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
                Button(onClick = onContinue) {
                    Text("Continue")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * One row of the explainer's "Why this matters" list. Pulled out so the
 * three rows share spacing/colour tokens and a future copy edit only has
 * one shape to update.
 */
@Composable
private fun ExplainerRow(
    icon: ImageVector,
    title: String,
    body: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
