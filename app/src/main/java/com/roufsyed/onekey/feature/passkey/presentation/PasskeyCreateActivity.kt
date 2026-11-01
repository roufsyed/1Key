package com.roufsyed.onekey.feature.passkey.presentation

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.NotSupportedError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.provider.PendingIntentHandler
import com.roufsyed.onekey.feature.passkey.presentation.PasskeyCeremonyViewModel.FailureKind
import com.roufsyed.onekey.feature.passkey.presentation.PasskeyCeremonyViewModel.Step
import dagger.hilt.android.AndroidEntryPoint

/**
 * Registration side of the provider: launched when the user picks "1Key vault"
 * in the system's save sheet. Unlocks if needed, lets the user attach the
 * passkey to an existing entry for the site or a new one, verifies the user
 * when the site asked, generates the key pair, stores the passkey and returns
 * the attestation response.
 */
@AndroidEntryPoint
class PasskeyCreateActivity : PasskeyCeremonyActivity() {

    override val cancelLabel: String = "Cancel"

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun begin(): Boolean {
        intent.getStringExtra(EXTRA_EXPLAIN)?.let { topic ->
            // Explanatory entry picked in the save sheet: no request to serve, only something to say.
            ceremony.showExplanation(ExplainTopic.parse(topic))
            return true
        }
        val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) ?: return false
        ceremony.startCreate(request)
        return true
    }

    /**
     * A site that cannot take ES256 gets a NotSupportedError, which it can show
     * as such; a refused caller gets the same cancellation the sign-in side returns.
     */
    override fun finishExplained(topic: ExplainTopic) {
        when (topic) {
            ExplainTopic.UNSUPPORTED_ALGORITHM ->
                deliver(Step.Failed("This site requires a key type 1Key does not support.", FailureKind.NOT_SUPPORTED))
            ExplainTopic.UNKNOWN_BROWSER, ExplainTopic.MULTIPLE_SIGNERS -> finishCancelled()
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun deliver(step: Step) {
        when (step) {
            is Step.CreateDone -> {
                val result = Intent()
                PendingIntentHandler.setCreateCredentialResponse(result, CreatePublicKeyCredentialResponse(step.responseJson))
                setResult(RESULT_OK, result)
                finish()
            }
            is Step.Failed -> {
                val result = Intent()
                val exception = when (step.kind) {
                    FailureKind.INVALID_STATE -> CreatePublicKeyCredentialDomException(InvalidStateError(), step.message)
                    FailureKind.NOT_SUPPORTED -> CreatePublicKeyCredentialDomException(NotSupportedError(), step.message)
                    FailureKind.NOT_ALLOWED -> CreatePublicKeyCredentialDomException(NotAllowedError(), step.message)
                    FailureKind.CANCELLED -> CreateCredentialCancellationException(step.message)
                    FailureKind.GENERIC -> CreateCredentialUnknownException(step.message)
                }
                PendingIntentHandler.setCreateCredentialException(result, exception)
                setResult(RESULT_OK, result)
                finish()
            }
            else -> Unit
        }
    }

    override fun finishCancelled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val result = Intent()
            PendingIntentHandler.setCreateCredentialException(result, CreateCredentialCancellationException())
            setResult(RESULT_CANCELED, result)
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    companion object {
        /** Name of an [ExplainTopic]; present only on the explanatory create entry's intent. */
        const val EXTRA_EXPLAIN = "com.roufsyed.onekey.passkey.EXPLAIN"
    }
}
