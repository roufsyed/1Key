package com.roufsyed.onekey.feature.passkey.presentation

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.roufsyed.onekey.core.domain.passkey.CallerKind
import com.roufsyed.onekey.feature.passkey.presentation.PasskeyCeremonyViewModel.FailureKind
import com.roufsyed.onekey.feature.passkey.presentation.PasskeyCeremonyViewModel.Step
import com.roufsyed.onekey.feature.passkey.service.PasskeyEntries
import com.roufsyed.onekey.feature.passkey.service.PasskeyGetPlanner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sign-in side of the provider. Three modes, chosen by [EXTRA_MODE]:
 *
 *  - [MODE_SIGN]: the user picked one of 1Key's entries in the system selector.
 *    Unlock if needed, verify the user if the site asked, sign, return the
 *    assertion through [PendingIntentHandler.setGetCredentialResponse].
 *  - [MODE_UNLOCK_AND_LIST]: the vault was locked when the selector asked, so
 *    the service offered an "Unlock 1Key" action. Unlock, rebuild the entry
 *    list with the same planner the service uses, and hand it back through
 *    [PendingIntentHandler.setBeginGetCredentialResponse].
 *  - [MODE_EXPLAIN]: the caller was refused by policy; show why.
 */
@AndroidEntryPoint
class PasskeyGetActivity : PasskeyCeremonyActivity() {

    @Inject lateinit var planner: PasskeyGetPlanner
    @Inject lateinit var entries: PasskeyEntries
    @Inject lateinit var unlockGrant: PasskeyUnlockGrant

    override val cancelLabel: String = "Cancel sign-in"

    private var beginRequest: BeginGetCredentialRequest? = null

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun begin(): Boolean {
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_SIGN -> {
                val request = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent) ?: return false
                val passkeyId = intent.getStringExtra(EXTRA_PASSKEY_ID) ?: return false
                ceremony.startSign(request, passkeyId, intent.getStringExtra(EXTRA_UNLOCK_GRANT))
            }
            MODE_UNLOCK_AND_LIST -> {
                beginRequest = PendingIntentHandler.retrieveBeginGetCredentialRequest(intent) ?: return false
                ceremony.startUnlockAndList(label = "your passkeys")
            }
            MODE_EXPLAIN -> {
                val reason = runCatching { CallerKind.Reason.valueOf(intent.getStringExtra(EXTRA_REFUSAL).orEmpty()) }
                    .getOrDefault(CallerKind.Reason.UNKNOWN_BROWSER)
                ceremony.showExplanation(reason)
            }
            else -> return false
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun deliver(step: Step) {
        when (step) {
            is Step.SignDone -> {
                val result = Intent()
                PendingIntentHandler.setGetCredentialResponse(result, GetCredentialResponse(PublicKeyCredential(step.responseJson)))
                setResult(RESULT_OK, result)
                finish()
            }
            is Step.ListAfterUnlock -> {
                val request = beginRequest ?: return finishCancelled()
                lifecycleScope.launch {
                    val builder = BeginGetCredentialResponse.Builder()
                    when (val plan = runCatching { planner.plan(request) }.getOrNull()) {
                        is PasskeyGetPlanner.Plan.Candidates -> {
                            // The user just unlocked for this request. Let that unlock be the
                            // verification for the entry they pick next (one-shot grant) and keep
                            // the vault open while the system selector is in front; the sign
                            // activity ends the hold, or it expires on its own.
                            val grant = unlockGrant.issue(plan.entries.map { it.second.id }.toSet())
                            autoLockManager.holdForCredentialSelector()
                            plan.entries.forEach { (option, passkey) ->
                                builder.addCredentialEntry(entries.credentialEntry(option, passkey, unlockGrant = grant))
                            }
                        }
                        is PasskeyGetPlanner.Plan.Refused -> builder.addAction(entries.explainAction(plan.reason))
                        else -> Unit
                    }
                    val result = Intent()
                    PendingIntentHandler.setBeginGetCredentialResponse(result, builder.build())
                    setResult(RESULT_OK, result)
                    finish()
                }
            }
            is Step.Failed -> {
                val result = Intent()
                val exception = when (step.kind) {
                    FailureKind.NOT_ALLOWED, FailureKind.INVALID_STATE, FailureKind.NOT_SUPPORTED ->
                        GetPublicKeyCredentialDomException(NotAllowedError(), step.message)
                    FailureKind.CANCELLED -> GetCredentialCancellationException(step.message)
                    FailureKind.GENERIC -> GetCredentialUnknownException(step.message)
                }
                PendingIntentHandler.setGetCredentialException(result, exception)
                setResult(RESULT_OK, result)
                finish()
            }
            else -> Unit
        }
    }

    override fun onDestroy() {
        // Any finishing instance other than unlock-and-list ends the selector hold:
        // the sign activity on success, failure or cancel, and the explain screen.
        if (isFinishing && intent.getStringExtra(EXTRA_MODE) != MODE_UNLOCK_AND_LIST) {
            autoLockManager.releaseCredentialSelectorHold()
        }
        super.onDestroy()
    }

    override fun finishCancelled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val result = Intent()
            PendingIntentHandler.setGetCredentialException(result, GetCredentialCancellationException())
            setResult(RESULT_CANCELED, result)
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    companion object {
        const val EXTRA_MODE = "com.roufsyed.onekey.passkey.MODE"
        const val EXTRA_PASSKEY_ID = "com.roufsyed.onekey.passkey.PASSKEY_ID"
        const val EXTRA_REFUSAL = "com.roufsyed.onekey.passkey.REFUSAL"
        const val EXTRA_UNLOCK_GRANT = "com.roufsyed.onekey.passkey.UNLOCK_GRANT"
        const val MODE_SIGN = "sign"
        const val MODE_UNLOCK_AND_LIST = "unlock_and_list"
        const val MODE_EXPLAIN = "explain"
    }
}
