package com.onekey.feature.settings.presentation.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.feature.sync.domain.SyncEngine
import com.onekey.feature.sync.domain.SyncFailureReason
import com.onekey.feature.sync.domain.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state + actions for the Settings -> Sync screen.
 *
 * The screen has two visible modes:
 *  - OFF mode: the "Sync" row is a single toggle. Tapping the toggle shows the
 *    consent dialog. Confirming the dialog launches the SAF folder picker; once
 *    the URI is granted, the toggle flips ON and the URI is persisted.
 *  - ON mode: four dependent rows are visible (last synced, location, completion
 *    notification toggle, Turn off). All are backed by [AppPreferencesRepository].
 *
 * The ViewModel never holds the master password; it never derives anything; it
 * never touches Keystore. It only reads/writes preferences and observes
 * [SyncEngine.state] so the screen can render the precise failure reason when
 * the user arrives from a failure-chip tap.
 */
@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPrefs: AppPreferencesRepository,
    syncEngine: SyncEngine,
) : ViewModel() {

    val isSyncEnabled: StateFlow<Boolean> = appPrefs.isSyncEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val syncLocationUri: StateFlow<String?> = appPrefs.getSyncLocationUri()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val isCompletionNotificationEnabled: StateFlow<Boolean> =
        appPrefs.isSyncCompletionNotificationEnabled()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val lastSuccessAt: StateFlow<Long> = appPrefs.getSyncLastSuccessAt()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    /**
     * Exposes the live sync state so the screen can render the failure reason
     * when the user arrives via the chip's failure tap. UI maps
     * `SyncState.Failed(reason)` to `reason.userVisibleText()`.
     */
    val syncState: StateFlow<SyncState> = syncEngine.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, SyncState.Idle)

    private val _showConsentDialog = MutableStateFlow(false)
    val showConsentDialog: StateFlow<Boolean> = _showConsentDialog.asStateFlow()

    /** Called when the user taps the "Sync" toggle while it is OFF. */
    fun onTurnOnRequested() {
        _showConsentDialog.value = true
    }

    fun onConsentDismissed() {
        _showConsentDialog.value = false
    }

    /**
     * Called by the screen after the user confirms the consent dialog and the SAF
     * folder picker callback fires with a non-null URI. Persists the URI grant via
     * `takePersistableUriPermission` so the engine can read it after a process
     * restart, stores the URI string in DataStore, and flips the toggle ON.
     */
    fun onLocationPicked(uri: Uri) {
        _showConsentDialog.value = false
        viewModelScope.launch {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Some providers refuse the persistable grant. Without it, the URI
                // becomes invalid on next process start. Treat as a soft failure -
                // do not flip the toggle on if we cannot persist.
                return@launch
            }
            appPrefs.setSyncLocationUri(uri.toString())
            appPrefs.setSyncEnabled(true)
        }
    }

    fun onChangeLocationPicked(uri: Uri) {
        viewModelScope.launch {
            // Release the prior URI's persistable grant before taking the new one.
            val oldUriString = appPrefs.getSyncGateDirect().locationUri
            if (oldUriString != null) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(oldUriString),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                return@launch
            }
            appPrefs.setSyncLocationUri(uri.toString())
        }
    }

    fun setCompletionNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setSyncCompletionNotificationEnabled(enabled) }
    }

    /**
     * Disable sync: clears the persistable URI grant, removes the URI from
     * DataStore, flips the toggle OFF. The existing `vault-backup.1key` file at
     * the chosen folder is left untouched - the user can keep it, move it, or
     * delete it themselves.
     */
    fun turnOffSync() {
        viewModelScope.launch {
            val gate = appPrefs.getSyncGateDirect()
            if (gate.locationUri != null) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(gate.locationUri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
            appPrefs.setSyncEnabled(false)
            appPrefs.setSyncLocationUri(null)
            appPrefs.setSyncCompletionNotificationEnabled(false)
        }
    }

    /**
     * Returns a human-readable full path for the current sync URI, or null when
     * none is set. Format examples:
     *   - Local storage: "Internal storage / Documents / 1Key Backups"
     *   - Removable SD card: "0000-0000 / Backups"
     *   - Cloud provider (best-effort): "Google Drive / 1Key Backups"
     *
     * SAF tree URIs hide the actual filesystem path behind a content:// scheme,
     * so the "path" we render is reconstructed from the tree document ID. For
     * `com.android.externalstorage.documents` the document ID is
     * `VOLUME_NAME:RELATIVE_PATH` (e.g. `primary:Documents/1Key Backups`); for
     * cloud providers the ID is opaque, so we fall back to a friendly authority
     * name + the document's display name.
     */
    fun displayLocation(): String? {
        val uriString = syncLocationUri.value ?: return null
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()

        return when (uri.authority) {
            AUTHORITY_EXTERNAL_STORAGE -> formatExternalStorage(treeDocId)
            else -> {
                val docName = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
                val providerLabel = friendlyProvider(uri.authority)
                when {
                    providerLabel != null && docName != null -> "$providerLabel / $docName"
                    docName != null -> docName
                    providerLabel != null -> providerLabel
                    else -> treeDocId
                }
            }
        }
    }

    private fun formatExternalStorage(treeDocId: String): String {
        val colon = treeDocId.indexOf(':')
        if (colon < 0) return treeDocId
        val volume = treeDocId.substring(0, colon)
        val relative = treeDocId.substring(colon + 1)
        val volumeLabel = when (volume) {
            "primary" -> "Internal storage"
            "home" -> "Home"
            else -> volume // SD-card volumes use IDs like "0000-0000"
        }
        val segments = relative.split('/').filter { it.isNotEmpty() }
        return (listOf(volumeLabel) + segments).joinToString(" / ")
    }

    private fun friendlyProvider(authority: String?): String? = when {
        authority == null -> null
        authority.contains("google.android.apps.docs") -> "Google Drive"
        authority.contains("dropbox") -> "Dropbox"
        authority.contains("onedrive") || authority.contains("skydrive") -> "OneDrive"
        authority.contains("nextcloud") -> "Nextcloud"
        authority.contains("box.android") -> "Box"
        authority.contains("mega.privacy") -> "MEGA"
        else -> null
    }

    private companion object {
        const val AUTHORITY_EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    }

    /** Map the engine's most recent failure to user-visible text, or null if none. */
    fun lastFailureText(): String? {
        val current = syncState.value
        if (current is SyncState.Failed) return current.reason.userVisibleText()
        return null
    }

    @Suppress("unused") // referenced from tests / future surfaces
    val lastFailureReason: SyncFailureReason?
        get() = (syncState.value as? SyncState.Failed)?.reason
}
