package com.roufsyed.onekey.feature.vault.presentation.screen.passkey

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roufsyed.onekey.core.domain.model.PasskeyOrigin
import com.roufsyed.onekey.core.domain.model.PasskeySummary
import com.roufsyed.onekey.core.presentation.lockaware.LockAwareDialog
import com.roufsyed.onekey.core.presentation.util.toFormattedDateTime

/**
 * Read-only list of the passkeys attached to a credential, in view mode of the
 * detail screen. Nothing is revealed or copied: a passkey has no value a user
 * could use by hand. The only action is delete, behind a confirmation.
 */
@Composable
internal fun PasskeysSection(
    passkeys: List<PasskeySummary>,
    onDelete: (String) -> Unit,
) {
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            "Passkeys (${passkeys.size})",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        passkeys.forEach { passkey ->
            PasskeyRow(passkey = passkey, onDelete = { pendingDelete = passkey.id })
        }
    }

    pendingDelete?.let { id ->
        val target = passkeys.firstOrNull { it.id == id }
        LockAwareDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Default.Key, contentDescription = null) },
            title = { Text("Delete this passkey?") },
            text = {
                Text(
                    "You will need to sign in to ${target?.rpId ?: "this site"} another way and create a new passkey. " +
                        "The site keeps its copy of the public key until you remove it there too.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(id)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PasskeyRow(passkey: PasskeySummary, onDelete: () -> Unit) {
    val created = remember(passkey.createdAt) { passkey.createdAt.toFormattedDateTime() }
    val lastUsed = remember(passkey.lastUsedAt) { passkey.lastUsedAt?.toFormattedDateTime() }
    val scope = when (val o = passkey.origin) {
        is PasskeyOrigin.Browser -> "Browsers"
        is PasskeyOrigin.App -> "Browsers and ${o.packageName}"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(passkey.rpName.ifBlank { passkey.rpId }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (passkey.rpName.isNotBlank() && passkey.rpName != passkey.rpId) {
                Text(passkey.rpId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val account = listOf(passkey.userDisplayName, passkey.userName).filter { it.isNotBlank() }.distinct().joinToString(" · ")
            if (account.isNotBlank()) Text(account, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Created $created" + (lastUsed?.let { " · Last used $it" } ?: " · Never used") + " · Works in: $scope",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete passkey for ${passkey.rpId}", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        }
    }
}
