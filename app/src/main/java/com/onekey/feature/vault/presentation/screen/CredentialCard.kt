package com.onekey.feature.vault.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialType
import com.onekey.core.presentation.util.toRelativeTime

/**
 * Flat list row for a credential — sibling of the home tag rows. No card chrome, no
 * elevation; the divider strip below each row (provided by callers) gives the list its
 * separation. Leading icon reflects credential type so the row is scannable at a glance.
 */
@Composable
internal fun CredentialCard(
    credential: Credential,
    onClick: () -> Unit,
    onTagClick: (String) -> Unit,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {},
) {
    // Computed once per unique updatedAt value; avoids DateTimeFormatter work on every recomposition.
    val relativeTime = remember(credential.updatedAt) { credential.updatedAt.toRelativeTime() }

    val containerColor = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer
    else
        Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isSelected)
                Icons.Default.CheckCircle
            else
                credentialTypeIcon(credential.type),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(if (isSelected) 24.dp else 22.dp),
        )
        Spacer(Modifier.width(if (isSelected) 12.dp else 16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    credential.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (credential.isFavorite) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            if (credential.username.isNotEmpty()) {
                Text(
                    credential.username,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val hasTime = credential.updatedAt > 0L
            val hasTags = credential.tags.isNotEmpty()
            if (hasTime || hasTags) {
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (hasTime) {
                        Text(
                            relativeTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    credential.tags.take(2).forEach { tag ->
                        TagPill(text = tag, onClick = { onTagClick(tag) })
                    }
                    if (credential.tags.size > 2) {
                        Text(
                            "+${credential.tags.size - 2}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (!isSelected) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TagPill(text: String, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        modifier = Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private fun credentialTypeIcon(type: CredentialType) = when (type) {
    CredentialType.LOGIN -> Icons.Default.Lock
    CredentialType.SECURE_NOTE -> Icons.Default.Description
    CredentialType.CREDIT_CARD -> Icons.Default.CreditCard
    CredentialType.PASSWORD -> Icons.Default.Key
    CredentialType.BANK_ACCOUNT -> Icons.Default.AccountBalance
    CredentialType.DATABASE -> Icons.Default.Storage
    CredentialType.EMAIL -> Icons.Default.Email
    CredentialType.SERVER -> Icons.Default.Computer
    CredentialType.OTHER -> Icons.Default.Label
}
