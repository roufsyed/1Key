package com.onekey.core.presentation.util

import android.content.Context
import androidx.biometric.BiometricPrompt
import com.onekey.core.security.AutoLockManager

/**
 * Owns the lifecycle of a [BiometricPrompt] across an autofill activity. The
 * activity creates one instance in `onCreate`, retains it as a member field
 * (so a configuration change does NOT drop the cancel handle), and cancels
 * the prompt in `onNewIntent` and `onDestroy`. The composable that builds
 * the unlock UI invokes [show] from its auto-trigger / "use biometric"
 * affordance and [cancel] from a `LaunchedEffect(lockReason)` when the lock
 * reason flips on mid-prompt.
 *
 * Two correctness invariants this class enforces that the prior pattern
 * (Compose-state-only) did not:
 *
 *  1. **Cancel survives configuration change.** Compose `remember`-held
 *     references are dropped on rotation; an activity-scoped field is not.
 *     Without this, a rotation mid-prompt could leave the system biometric
 *     dialog showing with no caller to cancel it.
 *
 *  2. **Inactivity-suppression is balanced.** A long-running BiometricPrompt
 *     consumes no `onUserInteraction` events (the system dialog steals
 *     touches), so [AutoLockManager]'s idle timer would expire mid-prompt
 *     and relock the vault — silently dropping the key the user is about to
 *     receive. We acquire on show, release on every terminal callback
 *     (success / error / cancel). [onAuthFailed] (wrong finger) does NOT
 *     release — the prompt stays open and we keep suppressing.
 */
class BiometricPromptController(
    private val context: Context,
    private val autoLockManager: AutoLockManager,
) {

    private var active: BiometricPrompt? = null
    private var suppressing = false

    /**
     * Shows the prompt, replacing any in-flight one. Callbacks fire on the
     * main thread (the underlying [showBiometricPrompt] uses the main
     * executor). `onSuccess` / `onError` / `onUserCancel` are terminal —
     * after they fire the prompt is gone and another [show] is required.
     * `onAuthFailed` is mid-prompt feedback only; the system continues to
     * accept retries until success, hard error, or user cancel.
     *
     * @param title defaults to "Biometric Unlock"; do NOT add subtitle that
     *   names the requesting host — leaks via OEM biometric logs.
     */
    fun show(
        title: String = "Biometric Unlock",
        negativeButtonText: String = "Use master password",
        onSuccess: () -> Unit,
        onAuthFailed: () -> Unit = {},
        onError: (String) -> Unit,
    ) {
        cancel()
        acquireSuppression()
        // Exception-safety: if [showBiometricPrompt] throws (e.g. the
        // [requireNotNull] FragmentActivity check fails, or any future
        // addition throws before `authenticate` lands), the suppression
        // already acquired above would otherwise leak forever — the
        // controller never gets a callback to release it. Re-balance the
        // bracket and rethrow.
        active = try {
            showBiometricPrompt(
                context = context,
                title = title,
                negativeButtonText = negativeButtonText,
                onSuccess = {
                    active = null
                    releaseSuppression()
                    onSuccess()
                },
                onError = { msg ->
                    active = null
                    releaseSuppression()
                    onError(msg)
                },
                onAuthFailed = onAuthFailed,
            )
        } catch (t: Throwable) {
            releaseSuppression()
            active = null
            throw t
        }
    }

    /**
     * Cancels the active prompt if any. Idempotent — calling multiple times
     * or when no prompt is showing is a no-op. Releases the inactivity
     * suppression atomically with the cancel.
     */
    fun cancel() {
        val current = active ?: run {
            // Defensive: if `active` is already null, ensure suppression is
            // released too. The two should always agree, but a future bug
            // in `show`'s callback ordering would otherwise strand the
            // suppression count permanently > 0.
            releaseSuppression()
            return
        }
        active = null
        current.cancelAuthentication()
        releaseSuppression()
    }

    private fun acquireSuppression() {
        if (!suppressing) {
            autoLockManager.acquireInactivitySuppression()
            suppressing = true
        }
    }

    private fun releaseSuppression() {
        if (suppressing) {
            autoLockManager.releaseInactivitySuppression()
            suppressing = false
        }
    }
}
