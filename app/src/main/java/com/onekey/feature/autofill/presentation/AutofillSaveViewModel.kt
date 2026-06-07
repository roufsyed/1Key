package com.onekey.feature.autofill.presentation

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.feature.autofill.domain.AutofillCaptureBuffer
import com.onekey.feature.autofill.domain.PendingCapture
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for [AutofillSaveActivity]. Drives the save-or-update decision:
 *
 *  - On first hydration it [consume]s the [AutofillCaptureBuffer] by token,
 *    pulling out the captured username/password pair. The result is held in
 *    [capture] (a [SaveState]) - `null` means the slot was empty (likely a
 *    stale Intent from a prior process) and the activity should finish.
 *
 *  - After unlock, it searches the vault for an existing credential that
 *    matches the same host (or package) AND username, exposed via
 *    [matchedExisting]. The UI offers "Update" when present and "Save as new"
 *    otherwise.
 *
 *  - [save] persists the choice and emits a terminal [SaveOutcome]. The
 *    activity finishes on any terminal state.
 *
 * Process-death note: the consume happens once at construction. If the
 * activity is recreated, [SavedStateHandle] preserves [HYDRATED_TOKEN] so we
 * do not double-consume. The captured payload itself is not persisted into
 * SavedState - once consumed, it lives only in this ViewModel instance, and
 * a real process death drops it (the user re-submits the form). That is by
 * design: plaintext credential bytes must not survive process death.
 */
@HiltViewModel
class AutofillSaveViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val application: Application,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    sealed class SaveState {
        data object Idle : SaveState()
        data class Hydrated(val capture: PendingCapture) : SaveState()
        data object MissingCapture : SaveState()
    }

    sealed class SaveOutcome {
        data object Idle : SaveOutcome()
        data object Saving : SaveOutcome()
        data object Saved : SaveOutcome()
        data class Failed(val message: String) : SaveOutcome()
    }

    private val _capture = MutableStateFlow<SaveState>(SaveState.Idle)
    val capture: StateFlow<SaveState> = _capture.asStateFlow()

    private val _outcome = MutableStateFlow<SaveOutcome>(SaveOutcome.Idle)
    val outcome: StateFlow<SaveOutcome> = _outcome.asStateFlow()

    private val _matchedExisting = MutableStateFlow<Credential?>(null)
    val matchedExisting: StateFlow<Credential?> = _matchedExisting.asStateFlow()

    /**
     * Resolved display label for the source app/site. Updated lazily on
     * hydration; we resolve the app label off the main thread to avoid PM
     * stalls but the lookup itself is small enough to be safe on Default.
     */
    private val _displayLabel = MutableStateFlow<String?>(null)
    val displayLabel: StateFlow<String?> = _displayLabel.asStateFlow()

    /**
     * Consume the capture buffer by [token], at most once across recreations.
     * Idempotent - the SavedState flag guards against re-consume on rotation.
     */
    fun hydrate(token: String) {
        if (_capture.value !is SaveState.Idle) return
        val alreadyHydrated = savedState.get<Boolean>(HYDRATED_TOKEN) == true
        if (alreadyHydrated) {
            // Process recreation after consume - payload is gone; treat as
            // missing so the activity finishes cleanly.
            _capture.value = SaveState.MissingCapture
            return
        }
        val payload = AutofillCaptureBuffer.consume(token)
        if (payload == null) {
            _capture.value = SaveState.MissingCapture
            return
        }
        savedState[HYDRATED_TOKEN] = true
        _capture.value = SaveState.Hydrated(payload)
        resolveDisplayLabel(payload)
    }

    /**
     * Look up an existing credential whose host (web domain or package) and
     * username both match. Returns the first hit; the picker UI calls this
     * after the vault is observed unlocked.
     */
    fun searchExisting() {
        val hydrated = _capture.value as? SaveState.Hydrated ?: return
        val payload = hydrated.capture
        viewModelScope.launch {
            val all: List<Credential> = when (val r = credentialRepository.getAllCredentials()) {
                is AppResult.Success -> r.data
                is AppResult.Error -> emptyList()
            }
            val needleUser = payload.username?.trim().orEmpty()
            val needleHost = payload.webDomain?.lowercase()
            val needlePkg = payload.packageName.lowercase()
            val match = all.firstOrNull { c ->
                val sameUser = needleUser.isNotEmpty() && c.username.equals(needleUser, ignoreCase = true)
                if (!sameUser) return@firstOrNull false
                if (needleHost != null) {
                    val credHost = runCatching {
                        val raw = c.url.trim()
                        if (raw.isEmpty()) null
                        else android.net.Uri.parse(if (raw.contains("://")) raw else "https://$raw").host?.lowercase()
                    }.getOrNull()
                    credHost == needleHost
                } else {
                    // Native-app capture: match on packageName encoded in url.
                    c.url.equals("androidapp://$needlePkg", ignoreCase = true)
                }
            }
            _matchedExisting.value = match
        }
    }

    /**
     * Save the captured credential. If [updateInPlace] is true and there is a
     * matched existing credential, replace its password (and username) while
     * preserving id, tags, custom fields, otpParams, favourite flag, and
     * createdAt. Otherwise insert a new credential.
     */
    fun save(updateInPlace: Boolean) {
        val hydrated = _capture.value as? SaveState.Hydrated ?: return
        val payload = hydrated.capture
        if (_outcome.value is SaveOutcome.Saving) return
        _outcome.value = SaveOutcome.Saving

        val now = System.currentTimeMillis()
        val username = payload.username.orEmpty()
        val password = payload.password.orEmpty()
        val title = _displayLabel.value
            ?: payload.webDomain
            ?: payload.packageName
        val url = payload.webDomain?.let { "https://$it" }
            ?: "androidapp://${payload.packageName}"

        viewModelScope.launch {
            val existing = if (updateInPlace) _matchedExisting.value else null
            val toSave: Credential = if (existing != null) {
                existing.copy(
                    username = username.ifEmpty { existing.username },
                    password = password,
                    updatedAt = now,
                )
            } else {
                Credential(
                    id = "",
                    title = title,
                    username = username,
                    password = password,
                    url = url,
                    notes = "",
                    otpParams = null,
                    tags = emptyList(),
                    customFields = emptyList(),
                    createdAt = now,
                    updatedAt = now,
                )
            }
            when (val result = credentialRepository.saveCredential(toSave)) {
                is AppResult.Success -> _outcome.value = SaveOutcome.Saved
                is AppResult.Error -> _outcome.value =
                    SaveOutcome.Failed(result.message ?: "Could not save")
            }
        }
    }

    fun dismiss() {
        // Best-effort: zero out the in-memory copy so a memory snapshot taken
        // immediately after dismissal doesn't see the captured plaintext.
        AutofillCaptureBuffer.clear()
        _outcome.value = SaveOutcome.Idle
    }

    private fun resolveDisplayLabel(payload: PendingCapture) {
        viewModelScope.launch {
            val label = payload.webDomain ?: runCatching {
                val pm = application.packageManager
                val ai = pm.getApplicationInfo(payload.packageName, 0)
                pm.getApplicationLabel(ai).toString().ifBlank { payload.packageName }
            }.getOrNull() ?: payload.packageName
            _displayLabel.value = label
        }
    }

    private companion object {
        const val HYDRATED_TOKEN = "autofill_save_hydrated"
    }
}
