package com.roufsyed.onekey.feature.passkey.service

import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.passkey.CallerKind
import com.roufsyed.onekey.core.domain.webauthn.RpId
import com.roufsyed.onekey.core.domain.webauthn.WebAuthnJson
import com.roufsyed.onekey.feature.passkey.presentation.ExplainTopic
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 1Key as an Android Credential Manager passkey provider (API 34 and later;
 * the manifest disables the component below that through a bool resource).
 *
 * Same shape as [com.roufsyed.onekey.feature.autofill.service.OneKeyAutofillService]:
 * one SupervisorJob scope, one Job per request wired to the system's
 * CancellationSignal, never throw across the binder, never log request
 * contents. Every policy decision lives in [PasskeyGetPlanner] and
 * `CallerPolicy`; this class only routes.
 *
 * Nothing here needs a network. Origins are vetted against the bundled
 * allowlist and native apps against their own signing lineage.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class OneKeyCredentialProviderService : CredentialProviderService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default + CoroutineName("passkey-provider"))
    private lateinit var entry: PasskeyEntryPoint

    override fun onCreate() {
        super.onCreate()
        entry = EntryPointAccessors.fromApplication(applicationContext, PasskeyEntryPoint::class.java)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        val job = serviceScope.launch {
            val response = try {
                handleGet(request)
            } catch (_: Throwable) {
                BeginGetCredentialResponse.Builder().build()
            }
            runCatching { callback.onResult(response) }
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private suspend fun handleGet(request: BeginGetCredentialRequest): BeginGetCredentialResponse {
        val builder = BeginGetCredentialResponse.Builder()
        if (!entry.preferences().isProviderEnabled().first()) return builder.build()
        val hasPasskeyOption = request.beginGetCredentialOptions.any { it is androidx.credentials.provider.BeginGetPublicKeyCredentialOption }
        if (!hasPasskeyOption || request.callingAppInfo == null) return builder.build()

        if (!entry.authRepository().isUnlocked().first()) {
            // Locked: the activity behind this action unlocks, then rebuilds the
            // entries through the same planner and hands them back to the selector.
            return builder.addAuthenticationAction(entry.entries().unlockAction()).build()
        }
        return when (val plan = entry.planner().plan(request)) {
            is PasskeyGetPlanner.Plan.Nothing -> builder.build()
            is PasskeyGetPlanner.Plan.Refused -> builder.addAction(entry.entries().explainAction(plan.reason)).build()
            is PasskeyGetPlanner.Plan.Candidates -> {
                plan.entries.forEach { (option, passkey) -> builder.addCredentialEntry(entry.entries().credentialEntry(option, passkey)) }
                builder.build()
            }
        }
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        val job = serviceScope.launch {
            val response = try {
                handleCreate(request)
            } catch (_: Throwable) {
                BeginCreateCredentialResponse.Builder().build()
            }
            runCatching { callback.onResult(response) }
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private suspend fun handleCreate(request: BeginCreateCredentialRequest): BeginCreateCredentialResponse {
        val builder = BeginCreateCredentialResponse.Builder()
        if (!entry.preferences().isProviderEnabled().first()) return builder.build()
        val passkeyRequest = request as? BeginCreatePublicKeyCredentialRequest ?: return builder.build()
        val callingAppInfo = request.callingAppInfo ?: return builder.build()
        val options = runCatching { WebAuthnJson.parseCreationOptions(passkeyRequest.requestJson) }.getOrNull() ?: return builder.build()

        val planner = entry.planner()
        val caller = planner.callerVerifier.resolve(callingAppInfo)
        // The save sheet has no action slot, so "why isn't 1Key here" is itself a
        // create entry (plan section 8.4). A refused caller gets no site name.
        if (caller is CallerKind.Refused) {
            return builder.addCreateEntry(entry.entries().explainCreateEntry(ExplainTopic.of(caller.reason), siteLabel = null)).build()
        }
        if (!options.supportsEs256) {
            val site = options.rpId ?: planner.originHost(caller)
            return builder.addCreateEntry(entry.entries().explainCreateEntry(ExplainTopic.UNSUPPORTED_ALGORITHM, siteLabel = site)).build()
        }
        val rpId = options.rpId ?: planner.originHost(caller) ?: return builder.build()
        // A native app's RP ID is its own claim; refuse shapes no legitimate RP could have.
        if (caller is CallerKind.App && !RpId.isValid(rpId)) return builder.build()

        val existing = if (entry.authRepository().isUnlocked().first()) {
            (planner.passkeyRepository.listActiveForRpId(rpId) as? AppResult.Success)?.data?.size ?: 0
        } else 0
        return builder.addCreateEntry(entry.entries().createEntry(rpId, existing)).build()
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        // Nothing to clear: 1Key keeps no sign-in session state outside the vault.
        runCatching { callback.onResult(null) }
    }
}
