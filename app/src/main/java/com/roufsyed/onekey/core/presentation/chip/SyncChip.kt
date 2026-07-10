package com.roufsyed.onekey.core.presentation.chip

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.roufsyed.onekey.feature.sync.domain.SyncState

/**
 * Top-of-screen status chip for the Sync-on-Master-Password-Unlock feature.
 *
 * Three visible states (animated transitions between them):
 *  - [SyncState.Syncing] : neutral surface tint + spinner + "Syncing your vault..." label.
 *                          NO X button. The user cannot cancel an in-flight sync (the
 *                          atomic write either lands or fails on its own).
 *  - [SyncState.Synced]  : green tint + check icon + "Synced" label + X. Auto-dismisses
 *                          after [SYNCED_DISMISS_MS]. Tapping X dismisses earlier.
 *  - [SyncState.Failed]  : amber tint + warning icon + "Backup didn't save" label + X.
 *                          Auto-dismisses after [FAILED_DISMISS_MS]. Body is tappable
 *                          to navigate to Settings -> Sync where the precise reason
 *                          (the [SyncState.Failed.reason] enum) is rendered.
 *
 * Hidden when state is [SyncState.Idle]. The composable is mounted at the top of the
 * NavGraph Box so it overlays per-screen TopAppBars cleanly; status-bar padding is
 * applied so the chip body sits in the safe inset region.
 *
 * State, dismiss action, and the failure-tap navigation callback are all hoisted -
 * this composable holds NO durable state of its own.
 */
@Composable
fun SyncChip(
    state: SyncState,
    onDismiss: () -> Unit,
    onFailureTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Auto-dismiss timers for terminal states. coroutine `delay` (not Handler.postDelayed)
    // so cancellation happens automatically if the state transitions away mid-wait.
    LaunchedEffect(state) {
        when (state) {
            is SyncState.Synced -> {
                kotlinx.coroutines.delay(SYNCED_DISMISS_MS)
                onDismiss()
            }
            is SyncState.Failed -> {
                kotlinx.coroutines.delay(FAILED_DISMISS_MS)
                onDismiss()
            }
            else -> Unit
        }
    }

    AnimatedVisibility(
        modifier = modifier.fillMaxWidth(),
        visible = state !is SyncState.Idle,
        enter = slideInVertically(
            animationSpec = tween(SLIDE_DURATION_MS),
            initialOffsetY = { -it },
        ) + fadeIn(tween(SLIDE_DURATION_MS)),
        exit = slideOutVertically(
            animationSpec = tween(SLIDE_DURATION_MS),
            targetOffsetY = { -it },
        ) + fadeOut(tween(SLIDE_DURATION_MS)),
    ) {
        ChipBody(
            state = state,
            onDismiss = onDismiss,
            onFailureTap = onFailureTap,
        )
    }
}

@Composable
private fun ChipBody(
    state: SyncState,
    onDismiss: () -> Unit,
    onFailureTap: () -> Unit,
) {
    val targetTint = when (state) {
        is SyncState.Syncing -> MaterialTheme.colorScheme.surfaceContainerHigh
        is SyncState.Synced -> SyncSuccessTint(MaterialTheme.colorScheme.surface)
        is SyncState.Failed -> SyncFailureTint(MaterialTheme.colorScheme.surface)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val containerColor by animateColorAsState(
        targetValue = targetTint,
        animationSpec = tween(TINT_DURATION_MS),
        label = "syncChipContainerColor",
    )

    Surface(
        color = containerColor,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // Crossfade between Syncing / Synced / Failed contents so the label and icon
        // change together with the tint, not separately. AnimatedContent uses fadeIn
        // togetherWith fadeOut for a clean visual swap.
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                fadeIn(tween(CONTENT_CROSSFADE_MS)) togetherWith fadeOut(tween(CONTENT_CROSSFADE_MS))
            },
            label = "syncChipContent",
        ) { current ->
            when (current) {
                is SyncState.Syncing -> SyncingRow()
                is SyncState.Synced -> SyncedRow(onDismiss = onDismiss)
                is SyncState.Failed -> FailedRow(onDismiss = onDismiss, onTap = onFailureTap)
                else -> Box(Modifier.height(CHIP_HEIGHT)) // empty
            }
        }
    }
}

@Composable
private fun SyncingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(CHIP_HEIGHT)
            .padding(horizontal = 16.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Syncing your vault...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SyncedRow(onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(CHIP_HEIGHT)
            .padding(start = 16.dp, end = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = SuccessAccent,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Synced",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FailedRow(onDismiss: () -> Unit, onTap: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(CHIP_HEIGHT)
            .clickable(onClick = onTap)
            .padding(start = 16.dp, end = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = FailureAccent,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Backup didn't save",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Soft green tint for the Synced state. Mixes ~14% green into the base surface so it
 * reads as "operation succeeded" without screaming. Matches the spec's neutral approach.
 */
private fun SyncSuccessTint(surface: Color): Color =
    Color(
        red = (surface.red * 0.86f + 0.14f * 0.122f).coerceIn(0f, 1f),
        green = (surface.green * 0.86f + 0.14f * 0.30f).coerceIn(0f, 1f),
        blue = (surface.blue * 0.86f + 0.14f * 0.18f).coerceIn(0f, 1f),
        alpha = surface.alpha,
    )

/**
 * Soft amber tint for the Failed state. Same blending strategy, biased toward amber.
 */
private fun SyncFailureTint(surface: Color): Color =
    Color(
        red = (surface.red * 0.86f + 0.14f * 0.62f).coerceIn(0f, 1f),
        green = (surface.green * 0.86f + 0.14f * 0.41f).coerceIn(0f, 1f),
        blue = (surface.blue * 0.86f + 0.14f * 0.13f).coerceIn(0f, 1f),
        alpha = surface.alpha,
    )

private val SuccessAccent = Color(0xFF2E7D32)
private val FailureAccent = Color(0xFFC77700)

private val CHIP_HEIGHT = 44.dp
private const val SLIDE_DURATION_MS = 200
private const val CONTENT_CROSSFADE_MS = 250
private const val TINT_DURATION_MS = 400
private const val SYNCED_DISMISS_MS = 1500L
private const val FAILED_DISMISS_MS = 5000L
