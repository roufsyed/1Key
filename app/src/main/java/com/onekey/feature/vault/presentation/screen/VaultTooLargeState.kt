package com.onekey.feature.vault.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Empty-state surface for the snapshot-Bypassed branch (vault exceeds the
 * snapshot store's per-row decryption cap). Both list screens render this in
 * place of their LazyColumn when `CredentialListState.Bypassed` is in effect.
 *
 * No retry button: bypass is monotonic with respect to credential count and
 * naturally recomposes back to `Loaded` once the user shrinks the vault
 * (deleting / purging entries) below the cap.
 */
@Composable
internal fun VaultTooLargeState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.LibraryBooks,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Vault too large for fast list view",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                // No "use the home-screen search" guidance: that surface is
                // also bypassed for the same vault size. The user's only
                // path right now is to open a credential by id from outside
                // this screen; the >10,000-entry browse experience is a
                // tracked follow-up.
                text = "This view is disabled for vaults beyond 10,000 entries.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
