package com.roufsyed.onekey.feature.passkey.presentation

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.roufsyed.onekey.core.domain.model.ThemeMode
import com.roufsyed.onekey.core.domain.model.isDark
import com.roufsyed.onekey.core.domain.repository.AppPreferencesRepository
import com.roufsyed.onekey.core.presentation.animation.UnlockOverlay
import com.roufsyed.onekey.core.presentation.lockaware.LocalUserActivityPing
import com.roufsyed.onekey.core.presentation.theme.OneKeyTheme
import com.roufsyed.onekey.core.presentation.util.BiometricPromptController
import com.roufsyed.onekey.core.presentation.viewmodel.AppViewModel
import com.roufsyed.onekey.core.security.AutoLockManager
import com.roufsyed.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import com.roufsyed.onekey.feature.autofill.presentation.AutofillLockedSurface
import com.roufsyed.onekey.feature.passkey.presentation.PasskeyCeremonyViewModel.Step
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Shared shell of the two passkey activities. Each one is launched by the
 * system through a provider PendingIntent, does the whole job inside one
 * activity (unlock if needed, verify, sign or create) and finishes with a
 * result on its intent, the way the autofill unlock activity does.
 *
 * Window hygiene mirrors `AutofillUnlockActivity`: FLAG_SECURE following the
 * screenshots preference, `filterTouchesWhenObscured`, `onUserInteraction`
 * feeding the auto-lock manager, `LocalUserActivityPing` at the root, and an
 * activity-held [BiometricPromptController]. `singleTop` rather than
 * `singleInstance`, so a passkey request and an autofill request never
 * cancel each other.
 */
@AndroidEntryPoint
abstract class PasskeyCeremonyActivity : FragmentActivity() {

    @Inject lateinit var appPrefs: AppPreferencesRepository
    @Inject lateinit var autoLockManager: AutoLockManager

    protected val authViewModel: AuthViewModel by viewModels()
    protected val appViewModel: AppViewModel by viewModels()
    protected val ceremony: PasskeyCeremonyViewModel by viewModels()
    protected var biometricController: BiometricPromptController? = null

    /** Label of the cancel affordance on the unlock and verification surfaces. */
    protected abstract val cancelLabel: String

    /** Reads the launching intent and starts the ceremony. Return false to finish cancelled. */
    protected abstract fun begin(): Boolean

    /** Handles a terminal [Step]; implementations set a result and finish. */
    protected abstract fun deliver(step: Step)

    /** Puts a cancellation result on the intent, then finishes. */
    protected abstract fun finishCancelled()

    /** Closes an explanatory screen. Cancellation by default; the create side maps some topics to a precise error. */
    protected open fun finishExplained(topic: ExplainTopic) = finishCancelled()

    override fun onUserInteraction() {
        super.onUserInteraction()
        autoLockManager.onUserActivity()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A second request routed into a live instance would carry a different
        // provider request than the ViewModel already parsed. Finish so the
        // system starts a fresh instance with the new extras.
        biometricController?.cancel()
        finishCancelled()
    }

    override fun onDestroy() {
        biometricController?.cancel()
        biometricController = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            finishCancelled()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch {
            appPrefs.isScreenshotsEnabled().collect { enabled ->
                if (enabled) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        window.decorView.filterTouchesWhenObscured = true
        biometricController = BiometricPromptController(this, autoLockManager)

        if (!begin()) {
            finishCancelled()
            return
        }

        setContent {
            val themeMode by appPrefs.getThemeMode().collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            OneKeyTheme(darkTheme = themeMode.isDark()) {
                val ping = remember(autoLockManager) { { autoLockManager.onUserActivity() } }
                CompositionLocalProvider(LocalUserActivityPing provides ping) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        CeremonyHost()
                        UnlockOverlay(appViewModel = appViewModel)
                    }
                }
            }
        }
    }

    @Composable
    private fun CeremonyHost() {
        val step by ceremony.step.collectAsStateWithLifecycle()
        val isUnlocked by authViewModel.isUnlocked.collectAsStateWithLifecycle()
        val isPinSetup by authViewModel.isPinSetup.collectAsStateWithLifecycle()
        val isBiometricEnabled by authViewModel.isBiometricEnabled.collectAsStateWithLifecycle()

        // Vault locked under the ceremony (background timeout, lockout elsewhere): back to the gate.
        LaunchedEffect(isUnlocked) { if (!isUnlocked) ceremony.onLocked() }

        LaunchedEffect(step) {
            when (step) {
                is Step.SignDone, is Step.CreateDone, is Step.ListAfterUnlock -> deliver(step)
                else -> Unit
            }
        }

        when (val s = step) {
            is Step.Loading -> PasskeyLoadingSurface()
            is Step.Unlock -> AutofillLockedSurface(
                target = s.target,
                headlineText = s.headline,
                submitButtonLabel = "Unlock",
                authViewModel = authViewModel,
                appViewModel = appViewModel,
                biometricController = biometricController!!,
                onUnlocked = { ceremony.onUnlocked() },
                onAbort = { finishCancelled() },
                cancelLabel = cancelLabel,
            )
            is Step.Verify -> UserVerificationSurface(
                target = s.target,
                error = s.error,
                busy = s.busy,
                biometricEnabled = isBiometricEnabled,
                isPinSetup = isPinSetup,
                onBiometric = {
                    biometricController?.show(
                        title = "Confirm it's you",
                        negativeButtonText = if (isPinSetup) "Use PIN" else "Use master password",
                        onSuccess = { ceremony.onBiometricVerified() },
                        onError = { },
                    )
                },
                onPin = { ceremony.verifyPin(it) },
                onPassword = { ceremony.verifyPassword(it) },
                onCancel = { finishCancelled() },
                cancelLabel = cancelLabel,
            )
            is Step.Create -> CreatePasskeySurface(
                form = s.form,
                onChooseTarget = { ceremony.chooseTarget(it) },
                onConfirm = { ceremony.confirmCreate() },
                onCancel = { finishCancelled() },
            )
            is Step.Explain -> ExplainSurface(topic = s.topic, onClose = { finishExplained(s.topic) })
            is Step.Failed -> if (s.kind == PasskeyCeremonyViewModel.FailureKind.CANCELLED) {
                LaunchedEffect(s) { finishCancelled() }
            } else {
                PasskeyErrorSurface(message = s.message, onClose = { deliver(s) })
            }
            is Step.SignDone, is Step.CreateDone, is Step.ListAfterUnlock -> PasskeyLoadingSurface()
        }
    }
}
