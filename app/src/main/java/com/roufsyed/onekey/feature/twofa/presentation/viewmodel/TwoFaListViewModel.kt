package com.roufsyed.onekey.feature.twofa.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.OtpType
import com.roufsyed.onekey.core.domain.repository.AppPreferencesRepository
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import com.roufsyed.onekey.core.domain.usecase.DeleteCredentialUseCase
import com.roufsyed.onekey.core.domain.usecase.SaveCredentialUseCase
import com.roufsyed.onekey.core.security.SecureClipboardManager
import com.roufsyed.onekey.core.security.VaultLockedException
import com.roufsyed.onekey.feature.twofa.domain.OtpGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Marker for any 2FA list row, regardless of OTP type. The screen renders
 * [Rotating] entries with a countdown ring and [Hotp] entries with a
 * "Generate next code" button (added in C5). Both share the long-press ->
 * delete affordance.
 */
sealed class TwoFaListEntry {
    abstract val credential: Credential

    val isLinkedCredential: Boolean
        get() = credential.password.isNotEmpty()
            || credential.notes.isNotEmpty()
            || credential.url.isNotEmpty()
            || credential.customFields.isNotEmpty()
}

/** Time-based row (TOTP, Steam). Code recomputes every second from the wall clock. */
data class RotatingOtpEntry(
    override val credential: Credential,
    val code: String,
    val remainingSeconds: Int,
    val progress: Float,
) : TwoFaListEntry()

/**
 * Counter-based row (HOTP). [code] is null until the user taps "Generate next code"
 * for the first time after the screen mounts (C5 - codes are not held across
 * sessions because they're effectively burned once shown). [generating] gates the
 * row's button so a fast double-tap can't burn two counters.
 */
data class HotpListEntry(
    override val credential: Credential,
    val code: String? = null,
    val generating: Boolean = false,
) : TwoFaListEntry()

/**
 * Backwards-compat alias for the old `TotpEntry` name. The screen module still
 * imports `TotpEntry` from this file; rather than touch every reference, we keep
 * the alias as a stable public surface and let new code use [RotatingOtpEntry].
 */
typealias TotpEntry = RotatingOtpEntry

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TwoFaListViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val otpGenerator: OtpGenerator,
    private val deleteCredential: DeleteCredentialUseCase,
    private val saveCredential: SaveCredentialUseCase,
    private val secureClipboard: SecureClipboardManager,
    appPrefs: AppPreferencesRepository,
) : ViewModel() {

    val hideTopBarOnScroll: StateFlow<Boolean> = appPrefs.isHideTopBarOnScroll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Per-second recompute loop for rotating OTP types (TOTP, STEAM). Fed by the
     * DAO-level filter `observeRotatingOtp`, which excludes HOTP at the SQL layer
     * - HOTP is on a different cadence and gets its own flow below.
     */
    private val rotating: Flow<List<RotatingOtpEntry>> = credentialRepository.observeRotatingOtp()
        .transformLatest { credentials ->
            while (true) {
                emit(credentials.mapNotNull { cred ->
                    val params = cred.otpParams ?: return@mapNotNull null
                    runCatching { otpGenerator.generate(params) }.getOrNull()?.let { result ->
                        // Time-based variants always carry remainingSeconds / progress;
                        // the !! is the structural invariant.
                        RotatingOtpEntry(cred, result.code, result.remainingSeconds!!, result.progress!!)
                    }
                })
                delay(TICK_MILLIS)
            }
        }
        .flowOn(Dispatchers.Default)

    /**
     * Per-credential transient UI state for HOTP rows: the most recently generated
     * code (visible until the user navigates away or taps generate again) and an
     * in-flight flag that disables the button while the atomic increment is in
     * Room's transaction queue. Keyed by credential id.
     *
     * Deliberately not persisted across process death - HOTP codes are single-use,
     * so showing a stale code from a prior session would be misleading at best.
     */
    private val hotpUiState = MutableStateFlow<Map<String, HotpRowState>>(emptyMap())

    /**
     * Mutexes that serialise concurrent "generate next code" taps for the same
     * credential. The DAO transaction already serialises on the SQLite level, but
     * a UI-level mutex lets us cleanly toggle the row's `generating` flag without
     * racing two coroutines that observe each other's intermediate states.
     *
     * ConcurrentHashMap because viewModelScope launches can read/write across
     * dispatchers; computeIfAbsent avoids constructing a Mutex per failed lookup.
     */
    private val hotpGenerateLocks = ConcurrentHashMap<String, Mutex>()

    private val hotp: Flow<List<HotpListEntry>> = combine(
        credentialRepository.observeHotpEntries(),
        hotpUiState,
    ) { credentials, uiState ->
        credentials.map { cred ->
            val state = uiState[cred.id]
            HotpListEntry(
                credential = cred,
                code = state?.lastCode,
                generating = state?.generating ?: false,
            )
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Combined feed: rotating entries first (sorted by title at the SQL layer),
     * then HOTP entries (also title-sorted). Splitting them visually rather than
     * fully interleaving makes the cadence difference legible to the user - all
     * "ticking" codes are together, all "tap to advance" codes are together.
     */
    val entries: StateFlow<List<TwoFaListEntry>?> = combine(rotating, hotp) { r, h -> r + h }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Routes through SecureClipboardManager so the 30s clear survives navigation. */
    fun copyCode(code: String) {
        secureClipboard.copySecure("2FA Code", code)
    }

    fun removeTotp(entry: TwoFaListEntry) {
        viewModelScope.launch {
            if (entry.isLinkedCredential) {
                saveCredential(entry.credential.copy(otpParams = null))
            } else {
                deleteCredential(entry.credential.id)
            }
            // Drop any cached HOTP UI state for the removed row so a re-add of the
            // same credential id (rare, but possible via import dedup) doesn't show
            // the previous session's code.
            hotpUiState.update { it - entry.credential.id }
        }
    }

    /**
     * Atomically advance the HOTP counter for [entry] and surface the resulting
     * code in the row's transient UI state. Pre-conditions checked here so a
     * misplaced caller can't desync the counter:
     *
     * - The entry must actually be a HOTP type (gate against future call sites).
     * - A generation already in flight returns immediately - repeated taps don't
     *   queue up; the user-facing button is disabled while [HotpListEntry.generating]
     *   is true, but defence-in-depth here catches anything that bypasses the UI.
     *
     * Vault locking mid-generate maps to [VaultLockedException] from the save
     * path; we swallow it because NavGraph routes to LockScreen on the next
     * recomposition. The counter has not been advanced (the transaction failed
     * before the UPDATE committed), so no desync.
     */
    fun generateNextHotpCode(entry: HotpListEntry) {
        val params = entry.credential.otpParams ?: return
        if (params.type != OtpType.HOTP) return

        val lock = hotpGenerateLocks.computeIfAbsent(entry.credential.id) { Mutex() }
        viewModelScope.launch {
            // tryLock so a fast double-tap drops the second invocation entirely
            // rather than queuing it. The user already has the first code on screen
            // by the time the second tap lands; advancing twice would burn a code.
            if (!lock.tryLock()) return@launch
            try {
                hotpUiState.update { state ->
                    state + (entry.credential.id to (state[entry.credential.id]
                        ?.copy(generating = true)
                        ?: HotpRowState(generating = true)))
                }
                val result = credentialRepository.incrementHotpCounter(entry.credential.id)
                when (result) {
                    is AppResult.Success -> {
                        val counter = result.data
                        if (counter == null) {
                            // Row vanished or wasn't HOTP - drop the spinner and bail.
                            hotpUiState.update { it.markIdle(entry.credential.id) }
                            return@launch
                        }
                        val code = runCatching {
                            otpGenerator.generate(params.copy(counter = counter))
                        }.getOrNull()?.code
                        hotpUiState.update { state ->
                            state + (entry.credential.id to HotpRowState(
                                lastCode = code,
                                generating = false,
                            ))
                        }
                        // Auto-copy on generation matches the row-tap behaviour for
                        // rotating entries - keeps the interaction model consistent
                        // ("tap = code is in your clipboard").
                        if (code != null) copyCode(code)
                    }
                    is AppResult.Error -> {
                        if (result.exception !is VaultLockedException) {
                            // Surface nothing user-visible for non-lock errors; HOTP
                            // failures are rare and the counter has not advanced (DAO
                            // transaction never committed). Logging hook would land
                            // here in a future commit.
                        }
                        hotpUiState.update { it.markIdle(entry.credential.id) }
                    }
                }
            } finally {
                lock.unlock()
            }
        }
    }

    private fun Map<String, HotpRowState>.markIdle(id: String): Map<String, HotpRowState> {
        val current = this[id] ?: return this
        return this + (id to current.copy(generating = false))
    }

    private data class HotpRowState(
        val lastCode: String? = null,
        val generating: Boolean = false,
    )

    private companion object {
        const val TICK_MILLIS = 1_000L
    }
}
