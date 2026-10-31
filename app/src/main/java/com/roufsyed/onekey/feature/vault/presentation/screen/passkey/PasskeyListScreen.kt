package com.roufsyed.onekey.feature.vault.presentation.screen.passkey

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roufsyed.onekey.core.presentation.util.oneKeyTopBarColors
import com.roufsyed.onekey.feature.vault.presentation.viewmodel.PasskeyListViewModel
import com.roufsyed.onekey.feature.vault.presentation.viewmodel.PasskeyListViewModel.ListState

/**
 * Bottom-navigation tab listing every passkey in the vault, the way the 2FA
 * tab lists every OTP account. Tapping a row opens the vault entry that owns
 * the passkey; the trailing bin deletes the passkey after confirmation. The
 * tab is shown only while "Show Passkeys tab" is on in Settings > Passkeys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasskeyListScreen(
    onCredentialClick: (String) -> Unit,
    viewModel: PasskeyListViewModel = hiltViewModel(),
) {
    val state by viewModel.listState.collectAsStateWithLifecycle()
    val hideTopBarOnScroll by viewModel.hideTopBarOnScroll.collectAsStateWithLifecycle()
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    // Same collapsing top bar as the other tabs; see TwoFaListScreen for why the
    // offset is pre-seeded and clamped to the 56 dp bar height.
    val barHeightLimitPx = with(LocalDensity.current) { -56.dp.toPx() }
    val topAppBarState = rememberTopAppBarState(initialHeightOffsetLimit = barHeightLimitPx)
    if (topAppBarState.heightOffsetLimit != barHeightLimitPx) {
        topAppBarState.heightOffsetLimit = barHeightLimitPx
    }
    if (topAppBarState.heightOffset < barHeightLimitPx) {
        topAppBarState.heightOffset = barHeightLimitPx
    }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        state = topAppBarState,
        canScroll = { hideTopBarOnScroll },
    )
    LaunchedEffect(hideTopBarOnScroll) {
        if (!hideTopBarOnScroll) {
            topAppBarState.heightOffset = 0f
            topAppBarState.contentOffset = 0f
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                colors = oneKeyTopBarColors(),
                title = { Text("Passkeys") },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        when (val s = state) {
            is ListState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is ListState.Loaded -> if (s.rows.isEmpty()) {
                EmptyPasskeys(modifier = Modifier.fillMaxSize().padding(padding))
            } else {
                LazyColumn(
                    modifier = Modifier.padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(s.rows, key = { it.passkey.id }) { row ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCredentialClick(row.passkey.credentialId) },
                        ) {
                            PasskeyRow(
                                passkey = row.passkey,
                                credentialTitle = row.credentialTitle,
                                onDelete = { pendingDelete = row.passkey.id },
                                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { id ->
        val rows = (state as? ListState.Loaded)?.rows.orEmpty()
        DeletePasskeyDialog(
            target = rows.firstOrNull { it.passkey.id == id }?.passkey,
            onConfirm = {
                pendingDelete = null
                viewModel.deletePasskey(id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun EmptyPasskeys(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("No passkeys yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Passkeys are created by websites and apps, not here. Add one from a site's security settings, " +
                    "and when Android asks where to save it, choose 1Key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
