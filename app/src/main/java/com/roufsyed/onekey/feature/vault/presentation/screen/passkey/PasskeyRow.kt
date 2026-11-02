package com.roufsyed.onekey.feature.vault.presentation.screen.passkey

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roufsyed.onekey.core.domain.model.PasskeyOrigin
import com.roufsyed.onekey.core.domain.model.PasskeySummary
import com.roufsyed.onekey.core.presentation.lockaware.LockAwareDialog
import com.roufsyed.onekey.core.presentation.monogram.SiteMonogram
import com.roufsyed.onekey.core.presentation.util.toFormattedDate
import com.roufsyed.onekey.core.presentation.util.toFormattedDateTime

/**
 * One passkey: the site, the account, and context (when it was made and where
 * it works). Nothing is revealed or copied, because a passkey has no value a
 * user could use by hand.
 *
 * Shared by the Passkeys tab (compact: one context line, no actions; the row's
 * container opens the vault entry) and the credential detail section
 * ([detailed]: created, last used and where it works on their own lines, with
 * a delete button that the caller confirms through [DeletePasskeyDialog]).
 * The button appears only when [onDelete] is supplied.
 */
@Composable
internal fun PasskeyRow(
    passkey: PasskeySummary,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    detailed: Boolean = false,
) {
    val context = LocalContext.current
    val site = passkey.rpName.ifBlank { passkey.rpId }
    val showDomain = passkey.rpName.isNotBlank() && !passkey.rpName.equals(passkey.rpId, ignoreCase = true)
    val account = listOf(passkey.userDisplayName, passkey.userName).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    val created = remember(passkey.createdAt, detailed) { if (detailed) passkey.createdAt.toFormattedDateTime() else passkey.createdAt.toFormattedDate() }
    val lastUsed = remember(passkey.lastUsedAt) { passkey.lastUsedAt?.toFormattedDateTime() }
    val (scopeIcon, scopeText) = when (val origin = passkey.origin) {
        is PasskeyOrigin.Browser -> Icons.Outlined.Language to "Browsers"
        is PasskeyOrigin.App -> {
            val label = remember(origin.packageName) {
                runCatching {
                    val pm = context.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(origin.packageName, 0)).toString()
                }.getOrNull() ?: origin.packageName
            }
            Icons.Outlined.Smartphone to "Browsers and $label"
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SiteMonogram(label = site)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(site, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showDomain) {
                    Text(passkey.rpId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete passkey for ${passkey.rpId}", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (account.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            MetaLine(icon = Icons.Outlined.Person, text = account, emphasised = true)
        }
        if (detailed) {
            Spacer(Modifier.height(10.dp))
            MetaLine(icon = Icons.Outlined.CalendarToday, text = "Created $created")
            Spacer(Modifier.height(6.dp))
            MetaLine(icon = Icons.Outlined.Schedule, text = lastUsed?.let { "Last used $it" } ?: "Never used")
            Spacer(Modifier.height(6.dp))
            MetaLine(icon = scopeIcon, text = "Works in: $scopeText")
        } else {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaLine(icon = Icons.Outlined.CalendarToday, text = created)
                Spacer(Modifier.width(14.dp))
                MetaLine(icon = scopeIcon, text = scopeText, modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}

@Composable
private fun MetaLine(icon: ImageVector, text: String, modifier: Modifier = Modifier, emphasised: Boolean = false) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = if (emphasised) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            color = if (emphasised) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Confirms deleting [target]. The copy says what the site still holds and what the user must do next. */
@Composable
internal fun DeletePasskeyDialog(
    target: PasskeySummary?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    LockAwareDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
        title = { Text("Delete this passkey?") },
        text = {
            Text(
                "Only the passkey is removed; the vault entry stays. " +
                    "You will need to sign in to ${target?.rpId ?: "this site"} another way and create a new passkey. " +
                    "The site keeps its copy of the public key until you remove it there too.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
