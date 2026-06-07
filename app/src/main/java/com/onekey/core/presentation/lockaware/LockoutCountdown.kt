package com.onekey.core.presentation.lockaware

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Composable hook that ticks a "seconds remaining until the lockout window
 * expires" counter for the lock + autofill UIs.
 *
 *  - [lockoutUntilMs] is an absolute epoch-ms timestamp (the future moment the
 *    cooldown ends). Null means "no lockout in effect."
 *  - The returned `Int` is the *current* remaining seconds, recomputed every
 *    200 ms while a window is active. When the window expires the counter
 *    settles on `0` and the effect suspends until [lockoutUntilMs] changes.
 *
 * Replaces the prior `val now = remember { System.currentTimeMillis() }`
 * pattern in the autofill panes, which captured `now` at composition time and
 * never re-evaluated - meaning the field stayed disabled long after the
 * window had expired (and conversely could appear enabled in the first frame
 * before the StateFlow had propagated). Identical to the inline blocks in
 * `LockScreen.PinUnlockSection` and `LockScreen.PasswordUnlockSection`,
 * which now delegate here.
 */
@Composable
fun rememberLockoutSecondsRemaining(lockoutUntilMs: Long?): Int {
    var lockoutSecondsRemaining by remember { mutableIntStateOf(0) }
    LaunchedEffect(lockoutUntilMs) {
        if (lockoutUntilMs == null) {
            lockoutSecondsRemaining = 0
            return@LaunchedEffect
        }
        while (true) {
            val remaining = ((lockoutUntilMs - System.currentTimeMillis()) / 1000)
                .toInt().coerceAtLeast(0)
            lockoutSecondsRemaining = remaining
            if (remaining <= 0) break
            delay(200L)
        }
    }
    return lockoutSecondsRemaining
}
