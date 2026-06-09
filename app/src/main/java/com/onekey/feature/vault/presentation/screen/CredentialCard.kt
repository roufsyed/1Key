package com.onekey.feature.vault.presentation.screen

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onekey.core.data.snapshot.SnapshotCredential
import com.onekey.core.domain.model.Credential

/**
 * Flat list row for a credential - matches the [TagRow] category-list look used on
 * the Vault home so credential lists read as one family with the categories above
 * them. Plain leading icon (derived from the credential's first tag), title in
 * `titleMedium` weight `Medium`, username as the M3 `supportingContent` slot.
 *
 * No tags, no last-modified time, no chevron, no favourite indicator - the row is
 * intentionally minimal; everything else lives on the credential detail screen.
 */
@Composable
internal fun CredentialCard(
    credential: Credential,
    onClick: () -> Unit,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {},
) = CredentialCardImpl(
    title = credential.title,
    username = credential.username,
    url = credential.url,
    tags = credential.tags,
    isFavorite = credential.isFavorite,
    onClick = onClick,
    isSelected = isSelected,
    onLongClick = onLongClick,
)

/**
 * Overload for the lean [SnapshotCredential] projection used by the snapshot-backed
 * search surface. The card needs only title/username/tags (tags for the leading
 * icon derivation) - the snapshot intentionally omits password, notes, OTP secret,
 * and custom fields, so a list-row composable cannot accidentally render any of those.
 */
@Composable
internal fun CredentialCard(
    credential: SnapshotCredential,
    onClick: () -> Unit,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {},
) = CredentialCardImpl(
    title = credential.title,
    username = credential.username,
    url = credential.url,
    tags = credential.tags,
    isFavorite = credential.isFavorite,
    onClick = onClick,
    isSelected = isSelected,
    onLongClick = onLongClick,
)

@Composable
private fun CredentialCardImpl(
    title: String,
    username: String,
    url: String,
    tags: List<String>,
    isFavorite: Boolean,
    onClick: () -> Unit,
    isSelected: Boolean,
    onLongClick: () -> Unit,
) {
    // Subtitle precedence: username, then URL, then nothing. Falling back to URL
    // when username is empty keeps the row's two-line shape consistent and gives
    // the user a meaningful secondary identifier for password-only / SSH / server
    // entries that have no username but do have a URL.
    val subtitle = when {
        username.isNotEmpty() -> username
        url.isNotEmpty() -> url
        else -> null
    }

    // Container colour:
    //  - Selected (multi-select mode): primaryContainer for clear selection state.
    //  - Default: transparent so the row picks up the Scaffold's background
    //    (`colorScheme.background`) instead of the M3 ListItem default of
    //    `colorScheme.surface`. The two differ by a few % in our palette and
    //    produce a visible card-on-background banding effect we don't want
    //    on a flat list.
    val colors = if (isSelected) {
        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
    }

    ListItem(
        leadingContent = {
            // Icon derived from the credential's first tag - matches the TagRow
            // home-screen language so list views read as one family. Pre-Phase-1
            // credentials carry type = LOGIN so a type-based icon would render
            // every row as a lock; tag-derived is correct.
            //
            // When the credential is favourited, a small tertiary-tinted heart
            // sits at the bottom-right of the icon as an "is favourited" badge.
            // Suppressed in selection mode so the CheckCircle reads cleanly.
            Box {
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle
                    else tagIcon(tags.firstOrNull() ?: ""),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                if (isFavorite && !isSelected) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Favorited",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.BottomEnd)
                            .offset(x = 4.dp, y = 4.dp),
                    )
                }
            }
        },
        headlineContent = {
            Text(
                title,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = subtitle?.let {
            {
                Text(
                    it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        colors = colors,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}
