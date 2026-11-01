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
        PasskeyDebugLog.d { "plan: passkeyOptions=${options.size} callingAppInfo=${request.callingAppInfo != null}" }
        if (options.isEmpty()) return Plan.Nothing
        val callingAppInfo = request.callingAppInfo ?: return Plan.Nothing
        val caller = callerVerifier.resolve(callingAppInfo)
        PasskeyDebugLog.d { "plan: caller=${caller.javaClass.simpleName}" }
        if (caller is CallerKind.Refused) return Plan.Refused(caller.reason)

        val entries = ArrayList<Pair<BeginGetPublicKeyCredentialOption, PasskeySummary>>()
        for (option in options) {
            val parsed = runCatching { WebAuthnJson.parseRequestOptions(option.requestJson) }
                .onFailure { PasskeyDebugLog.d { "plan: request JSON rejected: ${it.javaClass.simpleName}: ${it.message}" } }
                .getOrNull() ?: continue
            val rpId = parsed.rpId ?: originHost(caller)
            PasskeyDebugLog.d {
                "plan: rpIdInRequest=${parsed.rpId != null} rpIdResolved=${rpId != null} " +
                    "rpIdMatchesOriginHost=${parsed.rpId == null || parsed.rpId == originHost(caller)} allowList=${parsed.allowCredentials.size}"
            }
            PasskeyDebugLog.d {
                // Shape only, never the identifiers: how the relying party encoded its allow list.
                val raw = runCatching {
                    com.google.gson.JsonParser.parseString(option.requestJson).asJsonObject.getAsJsonArray("allowCredentials")
                        .map { it.asJsonObject }
                }.getOrNull().orEmpty()
                val ids = raw.mapNotNull { it.get("id")?.takeIf { v -> v.isJsonPrimitive }?.asString }
                "plan: allowCredentials raw=${raw.size} ids=${ids.size} rawLengths=${ids.map { it.length }} " +
                    "nonUrlSafe=${ids.count { v -> v.any { c -> c == '+' || c == '/' } }} padded=${ids.count { it.endsWith("=") }} " +
                    "types=${raw.map { it.get("type")?.takeIf { v -> v.isJsonPrimitive }?.asString }} " +
                    "transports=${raw.map { it.get("transports")?.toString() }} decodedBytes=${parsed.allowCredentials.map { it.id.size }}"
            }
            rpId ?: continue
            val listed = passkeyRepository.listActiveForRpId(rpId)
            val summaries = (listed as? AppResult.Success)?.data
            PasskeyDebugLog.d { "plan: lookup=${listed.javaClass.simpleName} forRpId=${summaries?.size}" }
            summaries ?: continue
            summaries.forEach {
                val usable = CallerPolicy.mayUse(it.origin, caller)
                val allowed = parsed.allows(it.webauthnCredentialId)
                PasskeyDebugLog.d { "plan: candidate origin=${it.origin.javaClass.simpleName} storedIdBytes=${it.webauthnCredentialId.size} mayUse=$usable inAllowList=$allowed" }
                if (usable && allowed) entries += option to it
            }
        }
        PasskeyDebugLog.d { "plan: entries=${entries.size}" }
        return Plan.Candidates(caller, entries)
    }

    /** `rpId` is optional in a get request; for a browser it defaults to the origin's host. */
    fun originHost(caller: CallerKind): String? =
        (caller as? CallerKind.Browser)?.origin?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }
}
