package com.roufsyed.onekey.feature.passkey.service

import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.PasskeySummary
import com.roufsyed.onekey.core.domain.passkey.CallerKind
import com.roufsyed.onekey.core.domain.passkey.CallerPolicy
import com.roufsyed.onekey.core.domain.repository.PasskeyRepository
import com.roufsyed.onekey.core.domain.webauthn.WebAuthnJson
import com.roufsyed.onekey.feature.passkey.verify.CallerVerifier
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides which passkeys a `BeginGetCredential` request may see. Shared by the
 * service (vault already unlocked) and by `PasskeyGetActivity` (after it
 * unlocked the vault on the user's behalf), so both produce the same list.
 */
@Singleton
class PasskeyGetPlanner @Inject constructor(
    val callerVerifier: CallerVerifier,
    val passkeyRepository: PasskeyRepository,
) {
    sealed class Plan {
        /** No passkey option in the request, or nothing to say. */
        data object Nothing : Plan()
        data class Refused(val reason: CallerKind.Reason) : Plan()
        data class Candidates(val caller: CallerKind, val entries: List<Pair<BeginGetPublicKeyCredentialOption, PasskeySummary>>) : Plan()
    }

    /** Requires an unlocked vault; the service checks that before calling. */
    suspend fun plan(request: BeginGetCredentialRequest): Plan {
        val options = request.beginGetCredentialOptions.filterIsInstance<BeginGetPublicKeyCredentialOption>()
        if (options.isEmpty()) return Plan.Nothing
        val callingAppInfo = request.callingAppInfo ?: return Plan.Nothing
        val caller = callerVerifier.resolve(callingAppInfo)
        if (caller is CallerKind.Refused) return Plan.Refused(caller.reason)

        val entries = ArrayList<Pair<BeginGetPublicKeyCredentialOption, PasskeySummary>>()
        for (option in options) {
            val parsed = runCatching { WebAuthnJson.parseRequestOptions(option.requestJson) }.getOrNull() ?: continue
            val rpId = parsed.rpId ?: originHost(caller) ?: continue
            val summaries = (passkeyRepository.listActiveForRpId(rpId) as? AppResult.Success)?.data ?: continue
            summaries
                .filter { CallerPolicy.mayUse(it.origin, caller) && parsed.allows(it.webauthnCredentialId) }
                .forEach { entries += option to it }
        }
        return Plan.Candidates(caller, entries)
    }

    /** `rpId` is optional in a get request; for a browser it defaults to the origin's host. */
    fun originHost(caller: CallerKind): String? =
        (caller as? CallerKind.Browser)?.origin?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }
}
