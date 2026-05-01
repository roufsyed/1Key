package com.onekey.core.presentation.lockaware

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * CompositionLocal carrying the "user did something" callback that resets the
 * [com.onekey.core.security.AutoLockManager] inactivity timer.
 *
 * Why this exists: `Activity.onUserInteraction()` only fires for events
 * dispatched to the activity's window. Compose `Dialog` / `ModalBottomSheet` /
 * `Popup` / `DropdownMenu` each render in their *own* native Window. Touches
 * (and IME-driven `TextField` updates inside them) never reach the activity
 * window's input dispatcher, so the inactivity timer drains while the user is
 * actively typing or tapping. Locks fire under the user's fingers.
 *
 * The fix is per-surface compensation: every wrapper composable in this
 * package reads this local and threads `ping()` into the surface's input path.
 * `MainActivity.setContent` is the single provider — no other site should
 * override.
 *
 * `staticCompositionLocalOf` rather than `compositionLocalOf` because the
 * provided lambda is reference-stable for the entire process lifetime; we
 * never want a per-recomposition copy chain when this rarely-changing value
 * doesn't need it. This keeps every consumer's recomposition cost zero.
 *
 * Default no-op so consumers in tests / previews don't NPE; the absence of a
 * provider is silently safe rather than loud, because failing here would
 * crash production previews while adding zero value.
 */
val LocalUserActivityPing = staticCompositionLocalOf<() -> Unit> { {} }
