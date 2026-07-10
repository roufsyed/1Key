package com.roufsyed.onekey.core.presentation.util

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Builds and shows a `BIOMETRIC_STRONG` [BiometricPrompt]. Hoisted from
 * `LockScreen` so both the main lock surface and the autofill lock activities
 * share one canonical implementation - drift between two near-duplicate
 * callers is exactly the kind of regression vector to avoid on an auth
 * surface.
 *
 * Contract:
 *  - [context] **must** be a [FragmentActivity]. Both [com.roufsyed.onekey.MainActivity]
 *    and the autofill activities qualify. The previous private helper returned
 *    null silently on a wrong host type - now this fails loud (`require`) so a
 *    future caller from a non-FragmentActivity surface can't get a no-op chip
 *    that looks like it should work.
 *  - The negative-button label is parameterised. Calling this from a PIN
 *    fallback context should pass `negativeButtonText = "Use PIN instead"`
 *    rather than the default "Use master password".
 *  - The returned [BiometricPrompt] is held by the caller so it can call
 *    `cancelAuthentication()` if the lock reason flips on mid-prompt, the
 *    activity finishes, or the user explicitly switches methods. Holding
 *    the reference at activity scope (member var) is required to survive
 *    configuration change; Compose-scope `remember` drops the reference on
 *    rotation.
 *
 * Error semantics:
 *  - `onSuccess`: biometric matched. The vault hasn't been unlocked yet -
 *    callers route through `AuthViewModel.unlockWithBiometric()` to perform
 *    the actual unwrap.
 *  - `onError`: any "hard" failure - sensor unavailable, OS-level
 *    `ERROR_LOCKOUT` / `ERROR_LOCKOUT_PERMANENT` (the platform's per-sensor
 *    rate-limit kicks in around 5 wrong reads, separate from our app-level
 *    counter), `ERROR_NO_BIOMETRICS`, `ERROR_HW_UNAVAILABLE`. The system's
 *    own [CharSequence] message is forwarded - they are already user-readable
 *    strings localised by the framework.
 *  - `onAuthFailed`: the user supplied biometric the OS recognised as not
 *    matching (wrong finger / wrong face). The prompt itself displays its own
 *    "try again" feedback; we forward the event so callers can drive the
 *    app-level counter (`AuthViewModel.recordBiometricFailure`).
 *  - User-cancel paths (`ERROR_USER_CANCELED`, `ERROR_NEGATIVE_BUTTON`,
 *    `ERROR_CANCELED` - the last one fires when the host calls
 *    `cancelAuthentication()` itself) are deliberately silent. Surfacing
 *    them as errors would flash an alarming red message when the user just
 *    tapped "Use master password" or when our own cancellation logic ran.
 *
 * Threat-model note: `FLAG_SECURE` on the host window does NOT propagate to
 * the BiometricPrompt - the prompt is rendered in a separate system-owned
 * window (the biometric subsystem). The prompt title is therefore generic
 * ("Biometric Unlock") and **must not** carry context like "for github.com"
 * - that would leak the requesting host into OEM biometric logs.
 */
fun showBiometricPrompt(
    context: Context,
    title: String = "Biometric Unlock",
    negativeButtonText: String = "Use master password",
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
    onAuthFailed: () -> Unit = {},
): BiometricPrompt {
    val activity = requireNotNull(context as? FragmentActivity) {
        "showBiometricPrompt requires a FragmentActivity host; got ${context.javaClass.name}"
    }
    val executor = ContextCompat.getMainExecutor(context)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                onSuccess()

            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                // Silent: user-initiated cancels and our own programmatic
                // cancellation. ERROR_CANCELED fires when the host calls
                // cancelAuthentication() - that path is our cleanup, not a
                // user-visible failure.
                if (code == BiometricPrompt.ERROR_USER_CANCELED ||
                    code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    code == BiometricPrompt.ERROR_CANCELED
                ) return
                onError(msg.toString())
            }

            override fun onAuthenticationFailed() {
                onAuthFailed()
            }
        }
    )
    BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setNegativeButtonText(negativeButtonText)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()
        .also { prompt.authenticate(it) }
    return prompt
}
