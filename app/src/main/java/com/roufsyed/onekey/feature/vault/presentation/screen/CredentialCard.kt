package com.roufsyed.onekey.feature.vault.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import com.roufsyed.onekey.core.presentation.theme.FavoriteIcon
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roufsyed.onekey.core.data.snapshot.SnapshotCredential
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.presentation.monogram.SiteColours
import com.roufsyed.onekey.core.presentation.monogram.SiteMonogram

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
    leading: CardLeading = CardLeading.TypeIcon,
    hasPasskey: Boolean = false,
) = CredentialCardImpl(
    title = credential.title,
    username = credential.username,
    url = credential.url,
    tags = credential.tags,
    isFavorite = credential.isFavorite,
    onClick = onClick,
    isSelected = isSelected,
    onLongClick = onLongClick,
    leading = leading,
    hasPasskey = hasPasskey,
    hasOtp = credential.otpParams != null,
)

/**
 * What the row shows on the left. [TypeIcon] is the Vault home's tag-derived
 * icon; [SiteMonogram] is the coloured letter tile the 2FA and Passkeys tabs
 * use, for lists where rows are mostly logins and telling sites apart matters.
 */
internal enum class CardLeading { TypeIcon, SiteMonogram }

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
    leading: CardLeading = CardLeading.TypeIcon,
    hasPasskey: Boolean = false,
) = CredentialCardImpl(
    title = credential.title,
    username = credential.username,
    url = credential.url,
    tags = credential.tags,
    isFavorite = credential.isFavorite,
    onClick = onClick,
    isSelected = isSelected,
    onLongClick = onLongClick,
    leading = leading,
    hasPasskey = hasPasskey,
    hasOtp = credential.hasOtp,
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
    leading: CardLeading,
    hasPasskey: Boolean,
    hasOtp: Boolean,
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
                when {
                    leading == CardLeading.TypeIcon -> Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle
                        else tagIcon(tags.firstOrNull() ?: ""),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    // Monogram rows keep their 40 dp footprint in selection mode so the
                    // list does not reflow when the first row is long-pressed.
                    isSelected -> Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    else -> SiteMonogram(label = title, colourKey = SiteColours.keyFor(url, title))
                }
                if (isFavorite && !isSelected) {
                    FavoriteIcon(
                        isFavorite = true,
                        contentDescription = "Favorited",
                        size = 18.dp,
                        modifier = Modifier
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
        // Sign-in methods are badged at the trailing edge rather than typed: a
        // 2FA shield and a passkey key, small and muted, in that order. Both are
        // hidden in selection mode to keep the row quiet while the check mark is
        // showing.
        trailingContent = if ((hasOtp || hasPasskey) && !isSelected) {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hasOtp) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = "Has 2FA",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (hasPasskey) {
                        Icon(
                            imageVector = Icons.Outlined.Key,
                            contentDescription = "Has a passkey",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        } else {
            null
        },
        colors = colors,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}
