package com.onekey.feature.vault.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onekey.core.data.snapshot.SnapshotCredential
import com.onekey.core.domain.model.Credential
import com.onekey.core.presentation.util.toRelativeTime

/**
 * Flat list row for a credential — sibling of the home tag rows. No card chrome, no
 * elevation; the divider strip below each row (provided by callers) gives the list its
 * separation. Leading icon reflects the credential's first tag so the row is scannable.
 */
@Composable
internal fun CredentialCard(
    credential: Credential,
    onClick: () -> Unit,
    onTagClick: (String) -> Unit,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {},
) = CredentialCardImpl(
    title = credential.title,
    username = credential.username,
    tags = credential.tags,
    isFavorite = credential.isFavorite,
    updatedAt = credential.updatedAt,
    onClick = onClick,
    onTagClick = onTagClick,
    isSelected = isSelected,
    onLongClick = onLongClick,
)

/**
 * Overload for the lean [SnapshotCredential] projection used by the snapshot-backed
 * search surface. The card needs only title/username/tags/isFavorite/updatedAt —
 * the snapshot intentionally omits password, notes, OTP secret, and custom fields,
 * so a list-row composable cannot accidentally render any of those.
 */
@Composable
internal fun CredentialCard(
    credential: SnapshotCredential,
    onClick: () -> Unit,
    onTagClick: (String) -> Unit,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {},
) = CredentialCardImpl(
    title = credential.title,
    username = credential.username,
    tags = credential.tags,
    isFavorite = credential.isFavorite,
    updatedAt = credential.updatedAt,
    onClick = onClick,
    onTagClick = onTagClick,
    isSelected = isSelected,
    onLongClick = onLongClick,
)

@Composable
private fun CredentialCardImpl(
    title: String,
    username: String,
    tags: List<String>,
    isFavorite: Boolean,
    updatedAt: Long,
    onClick: () -> Unit,
    onTagClick: (String) -> Unit,
    isSelected: Boolean,
    onLongClick: () -> Unit,
) {
    // Computed once per unique updatedAt value; avoids DateTimeFormatter work on every recomposition.
    val relativeTime = remember(updatedAt) { updatedAt.toRelativeTime() }

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
        // Icon comes from the credential's first tag rather than its type — legacy
        // rows (pre-Phase 1) all carry type = LOGIN, so a type-based icon would render
        // every row as a lock no matter which category list the user is in. Tags
        // match the home rows' icon language anyway, so list views read as one family.
        Icon(
            imageVector = if (isSelected)
                Icons.Default.CheckCircle
            else
                tagIcon(tags.firstOrNull() ?: ""),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(if (isSelected) 24.dp else 22.dp),
        )
        Spacer(Modifier.width(if (isSelected) 12.dp else 16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isFavorite) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            if (username.isNotEmpty()) {
                Text(
                    username,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val hasTime = updatedAt > 0L
            val hasTags = tags.isNotEmpty()
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
                    tags.take(2).forEach { tag ->
                        TagPill(text = tag, onClick = { onTagClick(tag) })
                    }
                    if (tags.size > 2) {
                        Text(
                            "+${tags.size - 2}",
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
