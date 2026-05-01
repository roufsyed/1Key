package com.onekey.core.presentation.lockaware

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Observes pointer and key events without consuming them, pinging
 * [LocalUserActivityPing] so the inactivity timer is reset by interactions
 * happening inside dialogs / sheets / popups whose touches never reach
 * `Activity.onUserInteraction()`.
 *
 * Pointer behaviour:
 *  - First pointer-down of every gesture pings.
 *  - Subsequent events within the same gesture (drag moves on a slider, sustained
 *    long-press) also ping. This prevents a long sustained gesture (a user
 *    scrubbing a password-generator slider for >30s without lifting their finger)
 *    from outliving the inactivity window.
 *  - The 5-second throttle inside [com.onekey.core.security.AutoLockManager.onUserActivity]
 *    caps the actual reset rate; per-event pinging here is cheap.
 *
 * Key behaviour:
 *  - `onPreviewKeyEvent` runs before children. Returning `false` means the event
 *    propagates normally; we observe but never intercept. Hardware keyboard /
 *    Chromebook input flows through this path; soft-keyboard (IME) input
 *    bypasses it entirely and is captured separately by
 *    [com.onekey.core.presentation.lockaware.LockAwareOutlinedTextField] /
 *    [com.onekey.core.presentation.lockaware.LockAwareTextField].
 *
 * Implementation note: we capture the latest `ping` lambda via
 * [rememberUpdatedState] so [pointerInput] can be keyed on `Unit` (stable) —
 * this avoids tearing down and rebuilding the gesture loop every recomposition,
 * which would in turn lose any in-progress gesture mid-stream.
 */
fun Modifier.lockAware(): Modifier = composed {
    val pingState = rememberUpdatedState(LocalUserActivityPing.current)
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                // First touch of the gesture — always ping.
                awaitFirstDown(requireUnconsumed = false)
                pingState.value()
                // Continue pinging on subsequent events of the same gesture.
                // Loop exits when no pointer is still pressed (gesture ended).
                while (true) {
                    val event = awaitPointerEvent()
                    pingState.value()
                    if (event.changes.none { it.pressed }) break
                }
            }
        }
        .onPreviewKeyEvent {
            pingState.value()
            // false → event continues to descendants; we're observing, not intercepting.
            false
        }
}

/**
 * Convenience for sites that want to ping without a modifier — e.g. a
 * `TextField`'s `onValueChange`. Reads the local and returns a ping callable.
 *
 * Useful in two places:
 *  1. The lockaware text-field wrappers, which call ping on every value change
 *     so IME input resets the timer.
 *  2. Custom interactions (sliders, gesture handlers) that want explicit pings.
 */
@Composable
fun rememberUserActivityPing(): () -> Unit {
    val pingState = rememberUpdatedState(LocalUserActivityPing.current)
    return { pingState.value() }
}
