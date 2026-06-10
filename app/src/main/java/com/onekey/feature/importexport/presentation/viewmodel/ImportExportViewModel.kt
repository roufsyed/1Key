package com.onekey.feature.importexport.presentation.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.usecase.ExportFormat
import com.onekey.core.domain.usecase.ExportVaultUseCase
import com.onekey.core.domain.usecase.ImportVaultUseCase
import com.onekey.core.security.AutoLockManager
import com.onekey.feature.importexport.domain.BackupPasswordValidator
import com.onekey.feature.importexport.domain.ConflictResolution
import com.onekey.feature.importexport.domain.ImportFieldOptions
import com.onekey.feature.importexport.domain.ImportPlan
import com.onekey.feature.importexport.domain.ImportResult
import com.onekey.feature.importexport.domain.ParsedImport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

sealed class ImportExportEvent {
    data object PlainExportAllowed : ImportExportEvent()
    data class PlainExportDenied(val message: String) : ImportExportEvent()
    data object ExportPasswordVerified : ImportExportEvent()
    data class ExportPasswordFailed(val attemptsRemaining: Int) : ImportExportEvent()
    data object ExportVaultLocked : ImportExportEvent()
    data class ExportPasswordTooWeak(val message: String) : ImportExportEvent()
}

@Immutable
sealed class ImportExportUiState {
    data object Idle : ImportExportUiState()
    data object Loading : ImportExportUiState()
    data class Success(val message: String) : ImportExportUiState()
    data class ImportSuccess(val result: ImportResult) : ImportExportUiState()
    // Parsed data ready for user review - shown before committing anything to the vault.
    data class ImportPreview(
        val parsed: ParsedImport,
        val previewItems: List<Credential>,
        val customFieldKeys: List<String>,
        val sensitiveCustomFieldKeys: Set<String>,
    ) : ImportExportUiState()
    // Plan computed; some items conflict with existing - user picks merge or add-as-separate.
    data class ImportReview(val plan: ImportPlan) : ImportExportUiState()
    // Encrypted file detected; error is non-null when decryption failed (retry allowed).
    data class AwaitingImportPassword(val error: String? = null) : ImportExportUiState()
    data class Error(val message: String) : ImportExportUiState()
}

@HiltViewModel
class ImportExportViewModel @Inject constructor(
    private val exportVault: ExportVaultUseCase,
    private val importVault: ImportVaultUseCase,
    private val autoLockManager: AutoLockManager,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ImportExportUiState>(ImportExportUiState.Idle)
    val uiState: StateFlow<ImportExportUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ImportExportEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ImportExportEvent> = _events.asSharedFlow()

    // Held between "file picked" and "password submitted / cancelled".
    private var pendingImportFile: File? = null

    // Held between "parse complete -> preview shown" and "user confirms / cancels".
    private var pendingParsedImport: ParsedImport? = null

    // Held between "plan computed with conflicts -> review shown" and "user picks resolution".
    private var pendingPlan: ImportPlan? = null

    // Held between "password dialog confirmed" and "picker callback fires".
    private var pendingExportPassword: CharArray? = null

    private var exportVerifyAttempts = 0

    // ── Picker suppression ────────────────────────────────────────────────────

    fun notifyPickerLaunched() { autoLockManager.suppressForPicker() }
    fun notifyPickerDone() { autoLockManager.clearPickerSuppression() }

    // ── Export ────────────────────────────────────────────────────────────────

    fun export(uri: Uri, format: ExportFormat, context: Context) {
        viewModelScope.launch {
            _uiState.value = ImportExportUiState.Loading
            val tmpFile = File(context.cacheDir, "export.${format.name.lowercase()}")
            try {
                withContext(Dispatchers.IO) { tmpFile.createNewFile() }
                // Off-main: gathers + decrypts every credential, then serialises with gson.
                when (val result = withContext(Dispatchers.Default) {
                    exportVault(format, tmpFile.absolutePath)
                }) {
                    is AppResult.Success -> {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)
                                ?.use { out -> tmpFile.inputStream().use { it.copyTo(out) } }
                        }
                        _uiState.value = ImportExportUiState.Success(
                            "Your vault has been exported. This file is NOT encrypted - " +
                            "store it somewhere safe and treat it as a sensitive document."
                        )
                    }
                    is AppResult.Error ->
                        _uiState.value = ImportExportUiState.Error(result.message ?: "Export failed")
                }
            } finally {
                withContext(Dispatchers.IO) { tmpFile.delete() }
            }
        }
    }

    fun setPendingExportPassword(password: CharArray) {
        pendingExportPassword?.fill(' ')
        pendingExportPassword = password
    }

    fun clearPendingExportPassword() {
        pendingExportPassword?.fill(' ')
        pendingExportPassword = null
    }

    fun exportEncrypted(uri: Uri, format: ExportFormat, context: Context) {
        val pw = pendingExportPassword ?: return
        pendingExportPassword = null
        viewModelScope.launch {
            _uiState.value = ImportExportUiState.Loading
            val tmpFile = File(context.cacheDir, "export_enc.1key")
            try {
                withContext(Dispatchers.IO) { tmpFile.createNewFile() }
                // Off-main: serialise + AES-GCM encrypt the whole vault with the user's
                // backup password. Heavier than plain export.
                when (val result = withContext(Dispatchers.Default) {
                    exportVault.encrypted(format, pw, tmpFile.absolutePath)
                }) {
                    is AppResult.Success -> {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)
                                ?.use { out -> tmpFile.inputStream().use { it.copyTo(out) } }
                        }
                        _uiState.value = ImportExportUiState.Success(
                            "Your encrypted backup has been saved successfully. " +
                            "Keep it somewhere safe - you'll need your master password to restore from it."
                        )
                    }
                    is AppResult.Error ->
                        _uiState.value = ImportExportUiState.Error(result.message ?: "Export failed")
                }
            } finally {
                withContext(Dispatchers.IO) { tmpFile.delete() }
                pw.fill(' ')
            }
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    fun import(uri: Uri, context: Context) {
        viewModelScope.launch {
            _uiState.value = ImportExportUiState.Loading

            val sizeBytes = context.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            if (sizeBytes != null && sizeBytes > 2L * 1024 * 1024 * 1024) {
                val sizeMb = sizeBytes / (1024 * 1024)
                _uiState.value = ImportExportUiState.Error(
                    "This file is too large to import (${sizeMb} MB). Maximum supported size is 2 GB."
                )
                return@launch
            }

            val tmpFile = File(context.cacheDir, "import.tmp")
            var keepFile = false
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.use { inp -> tmpFile.outputStream().use { inp.copyTo(it) } }
                }
                val encrypted = withContext(Dispatchers.IO) {
                    importVault.isEncrypted(tmpFile.absolutePath)
                }
                if (encrypted) {
                    pendingImportFile = tmpFile
                    keepFile = true
                    _uiState.value = ImportExportUiState.AwaitingImportPassword()
                } else {
                    // Off-main: read entire file + JSON/CSV parse. With multi-MB exports
                    // this can take hundreds of ms.
                    when (val result = withContext(Dispatchers.Default) {
                        importVault.parseOnly(tmpFile.absolutePath)
                    }) {
                        is AppResult.Success -> {
                            val parsed = result.data
                            pendingParsedImport = parsed
                            _uiState.value = ImportExportUiState.ImportPreview(
                                parsed = parsed,
                                previewItems = buildPreviewItems(parsed.credentials),
                                customFieldKeys = buildCustomFieldKeys(parsed.credentials),
                                sensitiveCustomFieldKeys = buildSensitiveCustomFieldKeys(parsed.credentials),
                            )
                        }
                        is AppResult.Error ->
                            _uiState.value = ImportExportUiState.Error(result.message ?: "Import failed")
                    }
                }
            } finally {
                if (!keepFile) withContext(Dispatchers.IO) { tmpFile.delete() }
            }
        }
    }

    /** Called from the password dialog. Retries are allowed - pendingImportFile stays alive on failure. */
    fun importWithPassword(password: CharArray) {
        val file = pendingImportFile ?: return
        viewModelScope.launch {
            _uiState.value = ImportExportUiState.Loading
            try {
                // Off-main: PBKDF2 + AES-GCM decrypt of the backup file before parse.
                when (val result = withContext(Dispatchers.Default) {
                    importVault.parseOnlyEncrypted(file.absolutePath, password)
                }) {
                    is AppResult.Success -> {
                        val parsed = result.data
                        withContext(Dispatchers.IO) { file.delete() }
                        pendingImportFile = null
                        pendingParsedImport = parsed
                        _uiState.value = ImportExportUiState.ImportPreview(
                            parsed = parsed,
                            previewItems = buildPreviewItems(parsed.credentials),
                            customFieldKeys = buildCustomFieldKeys(parsed.credentials),
                            sensitiveCustomFieldKeys = buildSensitiveCustomFieldKeys(parsed.credentials),
                        )
                    }
                    is AppResult.Error -> {
                        // Keep pendingImportFile for retry; surface error inside dialog.
                        _uiState.value = ImportExportUiState.AwaitingImportPassword(
                            result.message ?: "Wrong password or corrupted backup"
                        )
                    }
                }
            } finally {
                password.fill(' ')
            }
        }
    }

    /**
     * Called when the user confirms the import preview with their chosen field options.
     * Computes a plan and either applies it directly (no conflicts) or transitions to
     * [ImportExportUiState.ImportReview] so the user can pick merge vs add-as-separate.
     */
    fun confirmImport(fieldOptions: ImportFieldOptions) {
        val parsed = pendingParsedImport ?: return
        viewModelScope.launch {
            _uiState.value = ImportExportUiState.Loading
            val planResult = withContext(Dispatchers.Default) {
                importVault.planImport(parsed, fieldOptions)
            }
            when (planResult) {
                is AppResult.Success -> {
                    val plan = planResult.data
                    if (plan.needsConflictResolution) {
                        pendingPlan = plan
                        _uiState.value = ImportExportUiState.ImportReview(plan)
                    } else {
                        // No conflicts - apply silently and finish.
                        applyPlan(plan, ConflictResolution.MERGE)
                    }
                }
                is AppResult.Error ->
                    _uiState.value = ImportExportUiState.Error(planResult.message ?: "Import failed")
            }
        }
    }

    /** Called from the Review screen with the user's decision for conflicts. */
    fun confirmConflictResolution(resolution: ConflictResolution) {
        val plan = pendingPlan ?: return
        viewModelScope.launch {
            _uiState.value = ImportExportUiState.Loading
            applyPlan(plan, resolution)
        }
    }

    private suspend fun applyPlan(plan: ImportPlan, resolution: ConflictResolution) {
        when (val result = withContext(Dispatchers.Default) {
            importVault.applyPlan(plan, resolution)
        }) {
            is AppResult.Success -> {
                pendingParsedImport = null
                pendingPlan = null
                _uiState.value = ImportExportUiState.ImportSuccess(result.data)
            }
            is AppResult.Error ->
                _uiState.value = ImportExportUiState.Error(result.message ?: "Import failed")
        }
    }

    fun cancelPendingImport() {
        viewModelScope.launch {
            val file = pendingImportFile
            pendingImportFile = null
            pendingParsedImport = null
            pendingPlan = null
            withContext(Dispatchers.IO) { file?.delete() }
            _uiState.value = ImportExportUiState.Idle
        }
    }

    /**
     * Re-emits the [ImportExportUiState.ImportPreview] state from the still-alive
     * [pendingParsedImport]. Used by the dialog's "Back" action on the Error phase so
     * the user can change their field selection and retry without re-picking the file.
     */
    fun returnToImportPreview() {
        val parsed = pendingParsedImport ?: return
        _uiState.value = ImportExportUiState.ImportPreview(
            parsed = parsed,
            previewItems = buildPreviewItems(parsed.credentials),
            customFieldKeys = buildCustomFieldKeys(parsed.credentials),
            sensitiveCustomFieldKeys = buildSensitiveCustomFieldKeys(parsed.credentials),
        )
    }

    // ── Export password verification (attempts-limited) ───────────────────────

    fun verifyPasswordForExport(password: CharArray) {
        viewModelScope.launch {
            // Validate strength before the expensive Argon2id unlock call. If the
            // password is too weak, surface the error immediately and zero the array.
            val strengthResult = BackupPasswordValidator.validate(password)
            if (strengthResult !is BackupPasswordValidator.Result.Valid) {
                password.fill(' ')
                _events.emit(ImportExportEvent.ExportPasswordTooWeak(
                    BackupPasswordValidator.userMessage(strengthResult)
                ))
                return@launch
            }

            try {
                // Argon2id derivation runs inside unlockWithPassword - keep it off Main
                // so the export-verify dialog doesn't freeze the UI on submit.
                val outcome = withContext(Dispatchers.Default) {
                    authRepository.unlockWithPassword(password)
                }
                when (outcome) {
                    is AppResult.Success -> {
                        exportVerifyAttempts = 0
                        _events.emit(ImportExportEvent.ExportPasswordVerified)
                    }
                    is AppResult.Error -> {
                        exportVerifyAttempts++
                        val remaining = 3 - exportVerifyAttempts
                        if (remaining <= 0) {
                            exportVerifyAttempts = 0
                            _events.emit(ImportExportEvent.ExportVaultLocked)
                        } else {
                            _events.emit(ImportExportEvent.ExportPasswordFailed(remaining))
                        }
                    }
                }
            } finally {
                password.fill(' ')
            }
        }
    }

    fun lockVault() {
        viewModelScope.launch { authRepository.lock() }
    }

    fun acknowledgeResult() {
        // Defensive - confirmImport already nulls on success, but if any path lands
        // here with stale parsed data, drop it so we don't leak across imports.
        pendingParsedImport = null
        _uiState.value = ImportExportUiState.Idle
    }

    // ── Disable-encryption guard ──────────────────────────────────────────────

    fun verifyPasswordForPlainExport(password: CharArray) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    authRepository.unlockWithPassword(password)
                }
                when (result) {
                    is AppResult.Success -> _events.emit(ImportExportEvent.PlainExportAllowed)
                    is AppResult.Error -> _events.emit(
                        ImportExportEvent.PlainExportDenied(result.message ?: "Wrong password")
                    )
                }
            } finally {
                password.fill(' ')
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns up to 8 credentials - one representative per unique first tag. */
    private fun buildPreviewItems(credentials: List<Credential>): List<Credential> {
        if (credentials.isEmpty()) return emptyList()
        val seenBuckets = LinkedHashSet<String>()
        val result = mutableListOf<Credential>()
        for (cred in credentials) {
            val bucket = cred.tags.firstOrNull() ?: ""
            if (seenBuckets.add(bucket)) {
                result.add(cred)
                if (result.size >= 8) break
            }
        }
        return result
    }

    /** All unique custom-field keys across all credentials, in first-seen order. */
    private fun buildCustomFieldKeys(credentials: List<Credential>): List<String> {
        val seen = LinkedHashSet<String>()
        for (cred in credentials) for (field in cred.customFields) seen.add(field.key)
        return seen.toList()
    }

    /** Keys that are marked sensitive in at least one credential. */
    private fun buildSensitiveCustomFieldKeys(credentials: List<Credential>): Set<String> =
        credentials.flatMapTo(HashSet()) { cred ->
            cred.customFields.filter { it.isSensitive }.map { it.key }
        }

    override fun onCleared() {
        super.onCleared()
        pendingExportPassword?.fill(' ')
        pendingExportPassword = null
        pendingParsedImport = null
        pendingImportFile?.delete()
        pendingImportFile = null
    }
}
