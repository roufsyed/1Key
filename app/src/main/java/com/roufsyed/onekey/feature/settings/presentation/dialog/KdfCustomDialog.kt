package com.roufsyed.onekey.feature.settings.presentation.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roufsyed.onekey.core.presentation.lockaware.LockAwareDialog
import com.roufsyed.onekey.core.security.KdfParams
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Hard ceiling on iteration count exposed by the Custom dialog.
 * Argon2id past `t=16` is already in territory where unlocks routinely take
 * 5-15s on commodity hardware; the device-side benchmark catches that and
 * shows a warning, but the slider itself stops at 16 to discourage absurdity.
 */
private const val CUSTOM_T_MAX = 16

/** Minimum t accepted. Argon2id spec requires `t >= 1`; we use 2 as the floor
 *  so even an aggressive "lower for speed" choice still meets the OWASP
 *  iteration minimum and the verifier remains within the project's safety budget. */
private const val CUSTOM_T_MIN = 2

/**
 * Minimum memory cost in MiB. 32 MiB is the OWASP 2023 floor for
 * memory-constrained interactive auth scenarios. Below that, Argon2id
 * collapses into a function easily parallelised on GPUs and the picker would
 * be issuing a worse-than-PBKDF2 config to the user.
 */
private const val CUSTOM_M_MIN = 32

/**
 * Soft warning threshold for "Unlock may feel slow." Tracks the design's
 * 5s/15s amber-and-red thresholds for the estimate panel.
 */
private const val ESTIMATE_AMBER_MS = 5_000L
private const val ESTIMATE_RED_MS = 15_000L

/**
 * Custom KDF parameter dialog. Two sliders (memory, iterations), one fixed
 * parallelism display, one Estimate button (runs the benchmark), one Apply
 * button (triggers the reauth flow).
 *
 *  - [maxMemoryMb] is the per-device cap from [com.roufsyed.onekey.core.security.DeviceCapacityDetector.maxCustomMemoryMb].
 *  - [initialM] / [initialT] seed the sliders. The picker passes the current
 *    custom values if Custom is already active, otherwise sensible defaults.
 *  - [currentActiveMt] is the live `(memoryMiB, tCost)` actually bound to the
 *    vault verifier right now (resolved via `currentActiveMt(...)` in
 *    `KdfPreset.kt`). When the sliders sit on these exact values the Apply
 *    button is disabled, preventing a no-op migration that would still pay
 *    the Argon2id re-derive cost. Pass `null` to skip the check entirely
 *    (e.g. cold start before the StateFlow has emitted).
 *  - [estimate] returns the measured wall-clock ms (or null on failure). The
 *    dialog wires this to the Estimate button.
 *  - [onApply] receives the user-chosen [KdfParams]. The caller is expected
 *    to open a reauth dialog and then call the migrator; the Custom dialog
 *    dismisses itself when [onDismiss] is invoked (typically from the
 *    enclosing screen on a successful KdfPresetApplied event).
 *
 * State preservation: slider values, the last-measured ms, and the
 * "estimated for these exact slider values" snapshot all live in
 * `rememberSaveable` so a rotation or process death doesn't lose partial
 * work. The Apply button is gated on an estimate having been taken for the
 * *current* slider position (changing a slider invalidates the estimate),
 * which prevents users from blindly committing an unmeasured config.
 */
@Composable
fun KdfCustomDialog(
    maxMemoryMb: Int,
    initialM: Int,
    initialT: Int,
    currentActiveMt: Pair<Int, Int>?,
    isBenchmarking: Boolean,
    estimate: suspend (KdfParams) -> Long?,
    onApply: (KdfParams) -> Unit,
    onDismiss: () -> Unit,
) {
    // Clamp the seed values to the allowed range so a stale custom config
    // (e.g. restored from a more-powerful device's vault) doesn't produce a
    // slider that exceeds its range and crashes Material's Slider.
    val clampedInitialM = initialM.coerceIn(CUSTOM_M_MIN, maxMemoryMb)
    val clampedInitialT = initialT.coerceIn(CUSTOM_T_MIN, CUSTOM_T_MAX)

    var memoryMb by rememberSaveable { mutableStateOf(clampedInitialM) }
    var iterations by rememberSaveable { mutableStateOf(clampedInitialT) }
    // Snapshot of (m, t) the most recent estimate was for. Apply is enabled
    // only when this matches the live slider values. nullable - null means
    // "no estimate has been taken yet for the current slider state".
    var estimatedFor by rememberSaveable { mutableStateOf<Pair<Int, Int>?>(null) }
    var estimateMs by rememberSaveable { mutableStateOf<Long?>(null) }
    var estimateError by rememberSaveable { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    // Whenever sliders move, drop the estimate so the user must re-measure
    // before Apply re-enables. This is what guarantees the Apply button only
    // commits values the user has actually seen a timing for.
    LaunchedEffect(memoryMb, iterations) {
        if (estimatedFor != memoryMb to iterations) {
            estimatedFor = null
        }
    }

    val currentParams = KdfParams(
        mCostKiB = memoryMb * 1024,
        tCost = iterations,
        parallelism = 1,
    )

    // True when the slider position equals the user's already-active
    // configuration. We gate Apply on this so the user cannot trigger a
    // no-op migration (which would still re-derive Argon2id, briefly lock
    // the UI, and drain battery) by re-saving the same values.
    val isUnchangedFromActive = currentActiveMt?.let { (activeM, activeT) ->
        activeM == memoryMb && activeT == iterations
    } == true

    LockAwareDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Tune, contentDescription = null) },
        title = { Text("Custom encryption strength") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // High-prominence advanced-user warning. Red text on a tinted
                // errorContainer surface (Material 3 convention for cautionary
                // content) so users see a different visual register than the
                // surrounding body copy and a tap-through-by-habit is harder.
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
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
                                "Advanced KDF (Key Derivation Function) Configuration",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(
                            "Warning",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "These settings control how encryption keys are derived " +
                                "from your master password. Higher values generally " +
                                "increase resistance to offline attacks but may " +
                                "increase unlock times and battery usage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "The default configuration has been carefully selected " +
                                "and is recommended for most users. Modify these " +
                                "settings only if you understand the trade-offs and " +
                                "have a specific reason to do so.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // ── Memory slider ──────────────────────────────────────
                Column {
                    Text(
                        "Memory: $memoryMb MiB",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = memoryMb.toFloat(),
                        onValueChange = { v -> memoryMb = v.roundToInt() },
                        valueRange = CUSTOM_M_MIN.toFloat()..maxMemoryMb.toFloat(),
                        // step count = (max - min) - 1 so the slider snaps at
                        // every integer MiB without floating-point drift.
                        steps = (maxMemoryMb - CUSTOM_M_MIN - 1).coerceAtLeast(0),
                        enabled = !isBenchmarking,
                    )
                    Text(
                        "Range: $CUSTOM_M_MIN-$maxMemoryMb MiB on this device. " +
                            "Higher memory increases attacker cost most.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── Iterations slider ──────────────────────────────────
                Column {
                    Text(
                        "Iterations: $iterations",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = iterations.toFloat(),
                        onValueChange = { v -> iterations = v.roundToInt() },
                        valueRange = CUSTOM_T_MIN.toFloat()..CUSTOM_T_MAX.toFloat(),
                        // 15 internal stops covers t=2..16. Argon2id rounds
                        // are cheap to add but rarely change the headline cost.
                        steps = (CUSTOM_T_MAX - CUSTOM_T_MIN - 1).coerceAtLeast(0),
                        enabled = !isBenchmarking,
                    )
                    Text(
                        "Range: $CUSTOM_T_MIN-$CUSTOM_T_MAX. " +
                            "Iterations are a linear cost multiplier on both you and the attacker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── Parallelism (locked at 1) ──────────────────────────
                Column {
                    Text(
                        "Parallelism: 1 (fixed)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Argon2id parallelism greater than 1 helps attackers more " +
                            "than defenders on commodity hardware; 1Key follows OWASP " +
                            "guidance and locks this value.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── Estimate panel ─────────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        when {
                            isBenchmarking -> {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    "Measuring on this device...",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            estimateError != null -> {
                                Text(
                                    estimateError ?: "Could not measure on this device.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            estimateMs == null -> {
                                Text(
                                    "Estimate: tap below to measure on this device.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            else -> {
                                val ms = estimateMs!!
                                val seconds = ms / 1000.0
                                Text(
                                    "Estimated unlock time: ${ms} ms (~${"%.1f".format(seconds)}s)",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                when {
                                    ms > ESTIMATE_RED_MS -> Text(
                                        "Unlock will be very slow. Consider lowering memory or iterations.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    ms > ESTIMATE_AMBER_MS -> Text(
                                        "Unlock may feel slow.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                                if (estimatedFor != memoryMb to iterations) {
                                    Text(
                                        "Values changed - re-measure before applying.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                if (isUnchangedFromActive) {
                    Text(
                        "These values match your current configuration. " +
                                "Move a slider to apply a different setting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "Argon2id m=$memoryMb MiB, t=$iterations, p=1",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        estimateError = null
                        scope.launch {
                            val ms = runCatching { estimate(currentParams) }.getOrNull()
                            if (ms == null) {
                                estimateError =
                                    "Could not measure on this device. Try lower memory."
                                estimateMs = null
                                estimatedFor = null
                            } else {
                                estimateMs = ms
                                estimatedFor = memoryMb to iterations
                            }
                        }
                    },
                    enabled = !isBenchmarking,
                ) { Text("Estimate") }
                Button(
                    onClick = { onApply(currentParams) },
                    enabled = !isBenchmarking &&
                        !isUnchangedFromActive &&
                        estimatedFor == memoryMb to iterations &&
                        estimateMs != null,
                ) { Text("Apply") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBenchmarking) {
                Text("Cancel")
            }
        },
    )
}
