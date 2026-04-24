package com.onekey.feature.importexport.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.usecase.ExportFormat
import com.onekey.core.domain.usecase.ExportVaultUseCase
import com.onekey.core.domain.usecase.ImportVaultUseCase
import com.onekey.core.security.AutoLockManager
import com.onekey.feature.importexport.domain.ImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@Immutable
sealed class ImportExportUiState {
    data object Idle : ImportExportUiState()
    data object Loading : ImportExportUiState()
    data class Success(val message: String) : ImportExportUiState()
    data class ImportSuccess(val result: ImportResult) : ImportExportUiState()
    data class Error(val message: String) : ImportExportUiState()
}

@HiltViewModel
class ImportExportViewModel @Inject constructor(
    private val exportVault: ExportVaultUseCase,
    private val importVault: ImportVaultUseCase,
    private val autoLockManager: AutoLockManager,
) : ViewModel() {

    fun notifyPickerLaunched() { autoLockManager.suppressForPicker() }
    fun notifyPickerDone() { autoLockManager.clearPickerSuppression() }

    private val _uiState = MutableStateFlow<ImportExportUiState>(ImportExportUiState.Idle)
    val uiState: StateFlow<ImportExportUiState> = _uiState.asStateFlow()

    fun export(uri: Uri, format: ExportFormat, context: Context) {
        viewModelScope.launch {
            _uiState.value = ImportExportUiState.Loading
            val tmpFile = File(context.cacheDir, "export.${format.name.lowercase()}")
            try {
                withContext(Dispatchers.IO) { tmpFile.createNewFile() }
                when (val result = exportVault(format, tmpFile.absolutePath)) {
                    is AppResult.Success -> {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)
                                ?.use { out -> tmpFile.inputStream().use { it.copyTo(out) } }
                        }
                        _uiState.value = ImportExportUiState.Success("Export complete")
                    }
                    is AppResult.Error ->
                        _uiState.value = ImportExportUiState.Error(result.message ?: "Export failed")
                }
            } finally {
                withContext(Dispatchers.IO) { tmpFile.delete() }
            }
        }
    }

    fun import(uri: Uri, format: ExportFormat, context: Context) {
        viewModelScope.launch {
            _uiState.value = ImportExportUiState.Loading
            val tmpFile = File(context.cacheDir, "import.${format.name.lowercase()}")
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.use { inp -> tmpFile.outputStream().use { inp.copyTo(it) } }
                }
                when (val result = importVault(format, tmpFile.absolutePath)) {
                    is AppResult.Success ->
                        _uiState.value = ImportExportUiState.ImportSuccess(result.data)
                    is AppResult.Error ->
                        _uiState.value = ImportExportUiState.Error(result.message ?: "Import failed")
                }
            } finally {
                withContext(Dispatchers.IO) { tmpFile.delete() }
            }
        }
    }
}
