package com.roufsyed.onekey.feature.passkey.presentation

import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.provider.ProviderCreateCredentialRequest
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roufsyed.onekey.core.data.snapshot.SnapshotState
import com.roufsyed.onekey.core.di.SnapshotStateFlow
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.Passkey
import com.roufsyed.onekey.core.domain.passkey.CallerKind
import com.roufsyed.onekey.core.domain.passkey.CallerPolicy
import com.roufsyed.onekey.core.domain.repository.AuthRepository
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import com.roufsyed.onekey.core.domain.repository.PasskeyRepository
import com.roufsyed.onekey.core.domain.webauthn.ClientData
import com.roufsyed.onekey.core.domain.webauthn.CreationOptions
import com.roufsyed.onekey.core.domain.webauthn.RequestOptions
import com.roufsyed.onekey.core.domain.webauthn.RpId
import com.roufsyed.onekey.core.domain.webauthn.WebAuthnAuthenticator
import com.roufsyed.onekey.core.domain.webauthn.WebAuthnJson
import com.roufsyed.onekey.feature.autofill.domain.HostExtractor
import com.roufsyed.onekey.feature.passkey.verify.CallerVerifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID
import javax.inject.Inject

/**
 * State machine behind [PasskeyGetActivity] and [PasskeyCreateActivity]. Survives
 * rotation; the activities only render [step] and deliver terminal results.
 *
 * Order of gates for every ceremony: vault unlocked, then (create only) the
 * target chosen, then user verification when the relying party asked for it,
 * then the cryptography. An unlock performed inside this ceremony counts as
 * user verification; so does the unlock the user did moments earlier in the
 * unlock-and-list step of the same request ([PasskeyUnlockGrant]); so does
 * Android 15's single-tap biometric result; so does a fresh biometric, PIN or
 * master-password check on the verification surface. Three failed verification
 * attempts lock the vault, like everywhere else.
 */
@HiltViewModel
class PasskeyCeremonyViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val passkeyRepository: PasskeyRepository,
    private val credentialRepository: CredentialRepository,
    private val callerVerifier: CallerVerifier,
    private val unlockGrant: PasskeyUnlockGrant,
    @SnapshotStateFlow private val snapshot: StateFlow<@JvmSuppressWildcards SnapshotState>,
) : ViewModel() {

    enum class FailureKind { GENERIC, INVALID_STATE, NOT_SUPPORTED, NOT_ALLOWED, CANCELLED }

    data class TargetCredential(val id: String, val title: String, val username: String)

    data class CreateForm(
        val rpId: String,
        val rpName: String,
        val userName: String,
        val userDisplayName: String,
        val requesterLabel: String,
        val requesterIsApp: Boolean,
        val targets: List<TargetCredential>,
        val selectedTargetId: String?,
        val replacesUserName: String?,
    )

    sealed interface Step {
        data object Loading : Step
        data class Unlock(val target: String, val headline: String) : Step
        data class Verify(val target: String, val error: String?, val busy: Boolean) : Step
        data class Create(val form: CreateForm) : Step
        data class Explain(val reason: CallerKind.Reason) : Step
        data class Failed(val message: String, val kind: FailureKind) : Step
        data class SignDone(val responseJson: String) : Step
        data class CreateDone(val responseJson: String) : Step
        data object ListAfterUnlock : Step
    }

    private class SignJob(
        val passkeyId: String,
        val option: GetPublicKeyCredentialOption,
        val options: RequestOptions,
        val caller: CallerKind,
        /** Verified before this activity started: system single-tap prompt or the unlock grant. */
        val preVerified: Boolean,
    )

    private class CreateJob(
        val request: CreatePublicKeyCredentialRequest,
        val options: CreationOptions,
        val caller: CallerKind,
        val rpId: String,
        val preVerified: Boolean,
    ) {
        var targetCredentialId: String? = null
        var confirmed = false
        var replaces: Passkey? = null
        var existing: List<Passkey> = emptyList()
    }

    private val _step = MutableStateFlow<Step>(Step.Loading)
    val step: StateFlow<Step> = _step.asStateFlow()

    private var started = false
    private var sign: SignJob? = null
    private var create: CreateJob? = null
    private var listAfterUnlock = false
    private var freshUnlock = false
    private var verified = false
    private var verifyFailures = 0

    /** The site or app a surface should name. */
    private var target: String = ""

    // ── starts ───────────────────────────────────────────────────────────────

    fun startSign(request: ProviderGetCredentialRequest, passkeyId: String, unlockGrantToken: String? = null) {
        if (started) return
        started = true
        // Redeem first and once, whatever happens next: a grant must never outlive the launch it was made for.
        val grantHonoured = unlockGrant.redeem(unlockGrantToken, passkeyId)
        viewModelScope.launch {
            val option = request.credentialOptions.filterIsInstance<GetPublicKeyCredentialOption>().firstOrNull()
                ?: return@launch fail("The request carried no passkey option.")
            val options = runCatching { WebAuthnJson.parseRequestOptions(option.requestJson) }.getOrNull()
                ?: return@launch fail("The sign-in request could not be read.")
            val caller = callerVerifier.resolve(request.callingAppInfo)
            if (caller is CallerKind.Refused) { _step.value = Step.Explain(caller.reason); return@launch }
            target = options.rpId ?: requesterLabel(caller)
            sign = SignJob(passkeyId, option, options, caller, preVerified = grantHonoured || request.biometricPromptResult?.isSuccessful == true)
            proceed()
        }
    }

    fun startCreate(request: ProviderCreateCredentialRequest) {
        if (started) return
        started = true
        viewModelScope.launch {
            val callingRequest = request.callingRequest as? CreatePublicKeyCredentialRequest
                ?: return@launch fail("Only passkeys can be saved here.", FailureKind.NOT_SUPPORTED)
            val options = runCatching { WebAuthnJson.parseCreationOptions(callingRequest.requestJson) }.getOrNull()
                ?: return@launch fail("The registration request could not be read.")
            if (!options.supportsEs256) {
                return@launch fail("This site requires a key type 1Key does not support.", FailureKind.NOT_SUPPORTED)
            }
            val caller = callerVerifier.resolve(request.callingAppInfo)
            if (caller is CallerKind.Refused) { _step.value = Step.Explain(caller.reason); return@launch }
            val rpId = options.rpId ?: originHost(caller)
                ?: return@launch fail("The site did not say which domain the passkey is for.")
            if (caller is CallerKind.App && !RpId.isValid(rpId)) {
                return@launch fail("The app asked for a passkey with an invalid site name.", FailureKind.NOT_ALLOWED)
            }
            target = rpId
            create = CreateJob(callingRequest, options, caller, rpId, preVerified = request.biometricPromptResult?.isSuccessful == true)
            proceed()
        }
    }

    fun startUnlockAndList(label: String) {
        if (started) return
        started = true
        listAfterUnlock = true
        target = label
        viewModelScope.launch { proceed() }
    }

    fun showExplanation(reason: CallerKind.Reason) {
        started = true
        _step.value = Step.Explain(reason)
    }

    // ── events from the UI ───────────────────────────────────────────────────

    /** The unlock surface finished inside this ceremony: counts as user verification. */
    fun onUnlocked() {
        freshUnlock = true
        viewModelScope.launch { proceed() }
    }

    /** The vault locked under us (background timeout, lockout): go back to the unlock gate. */
    fun onLocked() {
        if (!started) return
        viewModelScope.launch { proceed() }
    }

    fun onBiometricVerified() {
        verified = true
        viewModelScope.launch { proceed() }
    }

    fun verifyPin(pin: String) = verifyWith { authRepository.verifyPin(pin.toCharArray()) }

    fun verifyPassword(password: CharArray) = verifyWith {
        try { authRepository.verifyMasterPassword(password) } finally { password.fill(' ') }
    }

    fun chooseTarget(credentialId: String?) {
        val job = create ?: return
        job.targetCredentialId = credentialId
        (_step.value as? Step.Create)?.let { _step.value = Step.Create(it.form.copy(selectedTargetId = credentialId)) }
    }

    fun confirmCreate() {
        val job = create ?: return
        job.confirmed = true
        viewModelScope.launch { proceed() }
    }

    // ── the gate sequence ────────────────────────────────────────────────────

    private suspend fun proceed() {
        val unlocked = authRepository.isUnlocked().first()
        if (!unlocked) {
            _step.value = Step.Unlock(target, if (create != null) "Unlock 1Key to save a passkey" else "Unlock 1Key to sign in")
            return
        }
        if (listAfterUnlock) { _step.value = Step.ListAfterUnlock; return }

        sign?.let { job ->
            if (job.options.userVerification.wantsVerification && !userVerified(job.preVerified)) {
                _step.value = Step.Verify(target, error = null, busy = false)
                return
            }
            doSign(job)
            return
        }
        create?.let { job ->
            if (!job.confirmed) {
                _step.value = Step.Create(buildForm(job))
                return
            }
            if (job.options.userVerification.wantsVerification && !userVerified(job.preVerified)) {
                _step.value = Step.Verify(target, error = null, busy = false)
                return
            }
            doCreate(job)
        }
    }

    private fun userVerified(preVerified: Boolean): Boolean = freshUnlock || verified || preVerified

    private fun verifyWith(check: suspend () -> AppResult<Unit>) {
        viewModelScope.launch {
            _step.value = Step.Verify(target, error = null, busy = true)
            when (val result = check()) {
                is AppResult.Success -> {
                    verifyFailures = 0
                    verified = true
                    proceed()
                }
                is AppResult.Error -> {
                    verifyFailures++
                    if (verifyFailures >= MAX_VERIFY_FAILURES) {
                        authRepository.lock()
                        fail("Too many failed attempts. The vault has been locked.", FailureKind.CANCELLED)
                    } else {
                        val left = MAX_VERIFY_FAILURES - verifyFailures
                        _step.value = Step.Verify(
                            target,
                            error = (result.message ?: "That didn't match.") + " $left ${if (left == 1) "attempt" else "attempts"} left.",
                            busy = false,
                        )
                    }
                }
            }
        }
    }

    // ── sign ─────────────────────────────────────────────────────────────────

    private suspend fun doSign(job: SignJob) {
        val passkey = (passkeyRepository.getById(job.passkeyId) as? AppResult.Success)?.data
            ?: return fail("That passkey is no longer in the vault.", FailureKind.NOT_ALLOWED)
        if (!CallerPolicy.mayUse(passkey.origin, job.caller)) {
            return fail("This app isn't allowed to use that passkey.", FailureKind.NOT_ALLOWED)
        }
        if (!job.options.allows(passkey.webauthnCredentialId)) {
            return fail("The site did not ask for this passkey.", FailureKind.NOT_ALLOWED)
        }
        val uv = userVerified(job.preVerified)
        val (clientDataJson, clientDataHash) = clientData(ClientData.TYPE_GET, job.option.clientDataHash, job.options.challenge, job.caller)
            ?: return fail("Could not establish the requesting origin.")
        val assertion = try {
            WebAuthnAuthenticator.assert(
                rpId = passkey.rpId,
                privateKeyPkcs8 = passkey.privateKeyPkcs8,
                clientDataHash = clientDataHash,
                userVerified = uv,
                backupState = passkey.isBackedUp,
            )
        } catch (_: Exception) {
            return fail("The passkey could not be used.")
        } finally {
            passkey.privateKeyPkcs8.fill(0)
        }
        passkeyRepository.touchLastUsed(passkey.id)
        _step.value = Step.SignDone(
            WebAuthnJson.assertionResponse(
                credentialId = passkey.webauthnCredentialId,
                clientDataJson = clientDataJson,
                authenticatorData = assertion.authenticatorData,
                signature = assertion.signature,
                userHandle = passkey.userHandle,
            ),
        )
    }

    // ── create ───────────────────────────────────────────────────────────────

    private suspend fun buildForm(job: CreateJob): CreateForm {
        if (job.existing.isEmpty()) {
            val summaries = (passkeyRepository.listActiveForRpId(job.rpId) as? AppResult.Success)?.data.orEmpty()
            job.existing = summaries.mapNotNull { (passkeyRepository.getById(it.id) as? AppResult.Success)?.data }
            job.replaces = job.existing.firstOrNull { it.userHandle.contentEquals(job.options.userId) }
            // Default target: the entry already holding this account's passkey, else a new entry.
            if (job.targetCredentialId == null) job.targetCredentialId = job.replaces?.credentialId
        }
        val wanted = job.rpId.removePrefix("www.")
        val loaded = (snapshot.value as? SnapshotState.Loaded)?.credentials.orEmpty()
        var targets = loaded
            .filter { HostExtractor.hostOf(it.url) == wanted }
            .sortedByDescending { it.updatedAt }
            .take(MAX_TARGETS)
            .map { TargetCredential(it.id, it.title, it.username) }
        val preselected = job.targetCredentialId
        if (preselected != null && targets.none { it.id == preselected }) {
            // The entry already holding this account's passkey may not match the
            // URL rule (renamed URL, bypassed snapshot); keep it selectable anyway.
            loaded.firstOrNull { it.id == preselected }?.let { targets = listOf(TargetCredential(it.id, it.title, it.username)) + targets }
        }
        return CreateForm(
            rpId = job.rpId,
            rpName = job.options.rpName.ifBlank { job.rpId },
            userName = job.options.userName,
            userDisplayName = job.options.userDisplayName,
            requesterLabel = requesterLabel(job.caller),
            requesterIsApp = job.caller is CallerKind.App,
            targets = targets,
            selectedTargetId = preselected?.takeIf { id -> targets.any { it.id == id } },
            replacesUserName = job.replaces?.userName,
        )
    }

    private suspend fun doCreate(job: CreateJob) {
        val excluded = job.options.excludeCredentials.any { descriptor -> job.existing.any { descriptor.matches(it.webauthnCredentialId) } }
        if (excluded) return fail("A passkey for this account already exists in 1Key.", FailureKind.INVALID_STATE)
        val binding = CallerPolicy.bindingFor(job.caller) ?: return fail("This app can't create passkeys.", FailureKind.NOT_ALLOWED)

        val credentialId = job.targetCredentialId ?: createCredential(job) ?: return fail("The vault entry could not be saved.")
        val uv = userVerified(job.preVerified)
        val registration = WebAuthnAuthenticator.register(rpId = job.rpId, userVerified = uv, backupState = false)
        val now = System.currentTimeMillis()
        val saved = try {
            passkeyRepository.save(
                Passkey(
                    id = "",
                    credentialId = credentialId,
                    rpId = job.rpId,
                    rpName = job.options.rpName.ifBlank { job.rpId },
                    userName = job.options.userName,
                    userDisplayName = job.options.userDisplayName,
                    userHandle = job.options.userId,
                    webauthnCredentialId = registration.credentialId,
                    privateKeyPkcs8 = registration.privateKeyPkcs8,
                    prfSecret = null,
                    origin = binding,
                    createdAt = now,
                    lastUsedAt = null,
                    backedUpAt = null,
                ),
            )
        } finally {
            registration.privateKeyPkcs8.fill(0)
        }
        if (saved !is AppResult.Success) return fail("The passkey could not be saved.")
        job.replaces?.let { passkeyRepository.deleteById(it.id) }

        val clientDataJson = clientData(ClientData.TYPE_CREATE, job.request.clientDataHash, job.options.challenge, job.caller)?.first
        _step.value = Step.CreateDone(
            WebAuthnJson.registrationResponse(
                credentialId = registration.credentialId,
                clientDataJson = clientDataJson,
                attestationObject = registration.attestationObject,
                authenticatorData = registration.authenticatorData,
                publicKeySpki = registration.publicKeySpki,
                credProps = true,
            ),
        )
    }

    private suspend fun createCredential(job: CreateJob): String? {
        val id = UUID.randomUUID().toString()
        val credential = Credential(
            id = id,
            title = job.options.rpName.ifBlank { job.rpId },
            username = job.options.userName,
            password = "",
            url = "https://${job.rpId}",
            notes = "",
            otpParams = null,
            tags = listOf(CredentialType.LOGIN.displayName),
            customFields = emptyList(),
            createdAt = 0L,
            updatedAt = 0L,
            type = CredentialType.LOGIN,
        )
        return if (credentialRepository.saveCredential(credential) is AppResult.Success) id else null
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns (clientDataJSON bytes or null, clientDataHash). A privileged caller
     * supplied the hash and keeps its own client data; a native app gets client
     * data built here with its `android:apk-key-hash:` origin.
     */
    private fun clientData(type: String, suppliedHash: ByteArray?, challenge: String, caller: CallerKind): Pair<ByteArray?, ByteArray>? {
        if (suppliedHash != null) return null to suppliedHash
        val (origin, packageName) = when (caller) {
            is CallerKind.Browser -> caller.origin to null
            is CallerKind.App -> ClientData.apkKeyHashOrigin(CallerPolicy.hexToBytes(caller.certSha256)) to caller.packageName
            is CallerKind.Refused -> return null
        }
        val json = ClientData.json(type, challenge, origin, packageName)
        return json.toByteArray(Charsets.UTF_8) to ClientData.hash(json)
    }

    private fun requesterLabel(caller: CallerKind): String = when (caller) {
        is CallerKind.Browser -> originHost(caller) ?: caller.origin
        is CallerKind.App -> caller.packageName
        is CallerKind.Refused -> ""
    }

    private fun originHost(caller: CallerKind): String? =
        (caller as? CallerKind.Browser)?.origin?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }

    private fun fail(message: String, kind: FailureKind = FailureKind.GENERIC) {
        _step.value = Step.Failed(message, kind)
    }

    private companion object {
        const val MAX_VERIFY_FAILURES = 3
        const val MAX_TARGETS = 6
    }
}
