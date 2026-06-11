package com.onekey.feature.settings.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.presentation.util.oneKeyTopBarColors
import com.onekey.core.security.KdfParams
import com.onekey.core.security.KdfPreset
import com.onekey.feature.auth.presentation.dialog.MasterPasswordReauthDialog
import com.onekey.feature.auth.presentation.dialog.MasterPasswordReauthState
import com.onekey.feature.settings.presentation.dialog.KdfCustomDialog
import com.onekey.feature.settings.presentation.viewmodel.SettingsEvent
import com.onekey.feature.settings.presentation.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

/**
 * One of two reauth-trigger states the screen tracks while the picker is
 * open. A successful selection (or Apply tap in the custom dialog) lifts a
 * [PendingKdfChoice] which then drives the [MasterPasswordReauthDialog]. The
 * dialog hands the typed master password to the VM, which runs the migration.
 *
 *  - [Fixed]  - one of the four fixed presets. params is derived from the
 *               preset itself; carried for completeness.
 *  - [Custom] - user-defined (m, t). The KdfParams here is what the picker
 *               will actually feed KdfMigrator on Apply.
 */
private sealed interface PendingKdfChoice {
    val preset: KdfPreset
    val params: KdfParams

    data class Fixed(override val preset: KdfPreset) : PendingKdfChoice {
        override val params: KdfParams = preset.toKdfParams()
    }

    data class Custom(override val params: KdfParams) : PendingKdfChoice {
        override val preset: KdfPreset = KdfPreset.CUSTOM
    }
}

/**
 * Full-screen Settings sub-route for choosing the Argon2id encryption strength.
 *
 * Four fixed presets ([KdfPreset.STANDARD], [KdfPreset.STANDARD_PLUS],
 * [KdfPreset.HARDENED], [KdfPreset.MAXIMUM]) are listed as cards. Disabled
 * presets (the device doesn't have enough RAM) render dimmed and non-clickable
 * with a "Requires N GB RAM" subtitle. Tapping an enabled card opens the
 * reauth dialog which, on success, runs KdfMigrator.migrateTo and dismisses
 * back to this screen with a snackbar.
 *
 * The "Custom" link at the bottom opens the [KdfCustomDialog],
 * which has its own benchmark / Estimate / Apply flow. Apply on the custom
 * dialog also routes through the same reauth dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsKdfStrengthScreen(
    onBack: () -> Unit,
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val activePreset by settingsVm.activeKdfPreset.collectAsStateWithLifecycle()
    val activeCustomParams by settingsVm.activeKdfCustomParams.collectAsStateWithLifecycle()
    val capacity by settingsVm.deviceCapacity.collectAsStateWithLifecycle()
    val benchmarks by settingsVm.kdfBenchmarks.collectAsStateWithLifecycle()
    val isMigrating by settingsVm.isKdfMigrating.collectAsStateWithLifecycle()
    val isBenchmarking by settingsVm.isKdfBenchmarking.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var pendingChoice by remember { mutableStateOf<PendingKdfChoice?>(null) }
    var reauthState by remember {
        mutableStateOf<MasterPasswordReauthState>(MasterPasswordReauthState.Idle)
    }
    var showCustomDialog by remember { mutableStateOf(false) }
    // Snackbar / inline message buffer for the rare "preset disabled" tap.
    var disabledExplain by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(disabledExplain) {
        val msg = disabledExplain ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        disabledExplain = null
    }

    LaunchedEffect(Unit) {
        settingsVm.event.collect { event ->
            when (event) {
                is SettingsEvent.KdfPresetApplied -> {
                    pendingChoice = null
                    reauthState = MasterPasswordReauthState.Idle
                    showCustomDialog = false
                    snackbarHostState.showSnackbar(
                        "Encryption strength updated to ${event.preset.displayName}"
                    )
                }
                is SettingsEvent.KdfPresetConfirmFailed -> {
                    reauthState = MasterPasswordReauthState.Error(event.attemptsRemaining)
                }
                is SettingsEvent.Error -> {
                    // Generic migration failure surfaces here too. Drop back
                    // to Idle so the user can retry without re-typing.
                    reauthState = MasterPasswordReauthState.Idle
                    snackbarHostState.showSnackbar(event.message)
                }
                SettingsEvent.VaultLocked -> {
                    // The Lock screen takes over via VaultKeyHolder; clear
                    // dialog state defensively in case the user returns.
                    pendingChoice = null
                    reauthState = MasterPasswordReauthState.Idle
                    showCustomDialog = false
                }
                else -> Unit  // Unrelated events flow through unhandled.
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                colors = oneKeyTopBarColors(),
                title = { Text("Encryption strength") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (isMigrating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            KdfStrengthContent(
                padding = PaddingValues(16.dp),
                activePreset = activePreset,
                activeCustomParams = activeCustomParams,
                capacity = capacity,
                benchmarks = benchmarks,
                isBenchmarking = isBenchmarking,
                isMigrating = isMigrating,
                onSelectFixed = { preset ->
                    if (preset == activePreset) {
                        // No-op tap: don't open the reauth dialog if the user
                        // chose the preset that's already in effect.
                        return@KdfStrengthContent
                    }
                    if (!isPresetEnabled(preset, capacity.enabledPresets)) {
                        val requiredGb = preset.minRamMb / 1024
                        val deviceGb = capacity.totalRamMb / 1024
                        disabledExplain =
                            "${preset.displayName} requires ${requiredGb} GB RAM. " +
                                "This device has ${deviceGb} GB."
                        return@KdfStrengthContent
                    }
                    pendingChoice = PendingKdfChoice.Fixed(preset)
                    reauthState = MasterPasswordReauthState.Idle
                },
                onOpenCustomDialog = { showCustomDialog = true },
                onRefreshBenchmark = settingsVm::refreshBenchmark,
            )
        }
    }

    // ── Custom params dialog ───────────────────────────────────────────────
    if (showCustomDialog) {
        // Seed the sliders from the currently-active custom params when the
        // user is editing an already-applied custom config; otherwise use
        // sensible defaults that pass the per-device cap.
        val currentCustom = activeCustomParams
        val seedM = currentCustom?.first ?: 96.coerceAtMost(capacity.maxArgon2MemoryMb)
        val seedT = currentCustom?.second ?: 5
        KdfCustomDialog(
            maxMemoryMb = capacity.maxArgon2MemoryMb,
            initialM = seedM,
            initialT = seedT,
            currentActiveMt = com.onekey.core.security.currentActiveMt(activePreset, activeCustomParams),
            isBenchmarking = isBenchmarking,
            estimate = { params ->
                // Compose's coroutineScope inside the dialog awaits this lambda
                // - we delegate straight to the VM so the same Mutex inside
                // KdfBenchmark serialises with the refresh-from-picker path.
                settingsVm.benchmarkCustom(params)
            },
            onApply = { params ->
                pendingChoice = PendingKdfChoice.Custom(params)
                reauthState = MasterPasswordReauthState.Idle
                // Keep the custom dialog OPEN behind the reauth dialog so the
                // user lands back at it after a wrong-password retry. The
                // reauth success path (KdfPresetApplied event handler above)
                // explicitly dismisses both.
            },
            onDismiss = { showCustomDialog = false },
        )
    }

    // ── Reauth dialog ──────────────────────────────────────────────────────
    val choice = pendingChoice
    if (choice != null) {
        val displayParams = choice.params
        MasterPasswordReauthDialog(
            state = reauthState,
            onVerify = { password ->
                reauthState = MasterPasswordReauthState.Verifying
                scope.launch {
                    when (choice) {
                        is PendingKdfChoice.Fixed -> settingsVm.selectPreset(choice.preset, password)
                        is PendingKdfChoice.Custom -> settingsVm.applyCustom(choice.params, password)
                    }
                }
            },
            onDismiss = {
                pendingChoice = null
                reauthState = MasterPasswordReauthState.Idle
            },
            title = "Confirm master password",
            bodyTitleLine = "Changing encryption strength re-derives your vault " +
                "verifier under the new Argon2id parameters. Your password and " +
                "vault contents do not change.",
            bodyDetailLine = "Switching to: ${choice.preset.displayName} " +
                "(Argon2id m=${displayParams.mCostKiB / 1024} MiB, " +
                "t=${displayParams.tCost}, p=1).",
            confirmButtonLabel = "Apply",
        )
    }
}

/** Encapsulates the scrollable list of preset cards. Pulled out so the
 *  surrounding screen stays readable and so a unit test can call this with a
 *  fake state without spinning up the dialog flow. */
@Composable
private fun KdfStrengthContent(
    padding: PaddingValues,
    activePreset: KdfPreset,
    activeCustomParams: Pair<Int, Int>?,
    capacity: com.onekey.core.security.CapacitySnapshot,
    benchmarks: Map<KdfParams, Long>,
    isBenchmarking: Boolean,
    isMigrating: Boolean,
    onSelectFixed: (KdfPreset) -> Unit,
    onOpenCustomDialog: () -> Unit,
    onRefreshBenchmark: () -> Unit,
) {
    val fixedPresets = listOf(
        KdfPreset.STANDARD,
        KdfPreset.STANDARD_PLUS,
        KdfPreset.HARDENED,
        KdfPreset.MAXIMUM,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // Explainer paragraph + device-summary chip.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Pick how hard your master password is to brute-force. " +
                        "Stronger presets cost more time and memory on each unlock " +
                        "but make GPU-farm attacks proportionally more expensive.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "This device: ${capacity.totalRamMb / 1024} GB RAM. " +
                        "Recommended preset: ${capacity.recommendedPreset.displayName}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(fixedPresets, key = { it.name }) { preset ->
            val enabled = isPresetEnabled(preset, capacity.enabledPresets)
            val isActive = preset == activePreset
            val isRecommended = preset == capacity.recommendedPreset
            val benchMs = benchmarks[preset.toKdfParams()]
            PresetCard(
                preset = preset,
                isActive = isActive,
                isRecommended = isRecommended,
                isEnabled = enabled && !isMigrating,
                deviceTotalRamMb = capacity.totalRamMb,
                benchmarkMs = benchMs,
                onClick = { onSelectFixed(preset) },
            )
        }

        item {
            // Custom row.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (isMigrating) 0.5f else 1f)
                    .clickable(enabled = !isMigrating) { onOpenCustomDialog() },
                colors = CardDefaults.cardColors(
                    containerColor = if (activePreset == KdfPreset.CUSTOM)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Custom",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (activePreset == KdfPreset.CUSTOM) {
                                AssistChip(
                                    onClick = {},
                                    enabled = false,
                                    label = { Text("Active") },
                                    colors = AssistChipDefaults.assistChipColors(
                                        disabledLabelColor = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                            }
                        }
                        Text(
                            "Pick your own Argon2id memory and iterations.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (activePreset == KdfPreset.CUSTOM && activeCustomParams != null) {
                            val (m, t) = activeCustomParams
                            Text(
                                "Currently: m=$m MiB, t=$t, p=1",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onRefreshBenchmark,
                enabled = !isBenchmarking && !isMigrating,
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (isBenchmarking) "Measuring..."
                    else "Refresh benchmark"
                )
            }
        }
    }
}

/** Card rendering a single preset row. The `isActive` highlight uses
 *  primaryContainer so the active config is unmissable on the picker. */
@Composable
private fun PresetCard(
    preset: KdfPreset,
    isActive: Boolean,
    isRecommended: Boolean,
    isEnabled: Boolean,
    deviceTotalRamMb: Long,
    benchmarkMs: Long?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isEnabled) 1f else 0.5f)
            .clickable(enabled = isEnabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(
                selected = isActive,
                onClick = if (isEnabled) onClick else null,
                enabled = isEnabled,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        preset.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    when {
                        isActive -> Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Active",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        isRecommended && isEnabled -> AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("Recommended") },
                            colors = AssistChipDefaults.assistChipColors(
                                disabledLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                        !isEnabled -> Icon(
                            Icons.Default.Lock,
                            contentDescription = "Disabled",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Argon2id m=${preset.mCostKiB / 1024} MiB, t=${preset.tCost}, p=${preset.parallelism}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!isEnabled) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            "Requires ${preset.minRamMb / 1024} GB RAM. " +
                                "This device has ${deviceTotalRamMb / 1024} GB.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            when (benchmarkMs) {
                                null -> "Estimating unlock time..."
                                else -> "Estimated unlock: ~${benchmarkMs} ms"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun isPresetEnabled(preset: KdfPreset, enabledSet: Set<KdfPreset>): Boolean {
    if (preset == KdfPreset.CUSTOM) return true
    return preset in enabledSet
}
