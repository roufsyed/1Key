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
    data class Error(val message: String) : ImportExportUiState()
}

@HiltViewModel
class ImportExportViewModel @Inject constructor(
    private val exportVault: ExportVaultUseCase,
    private val importVault: ImportVaultUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ImportExportUiState>(ImportExportUiState.Idle)
    val uiState: StateFlow<ImportExportUiState> = _uiState.asStateFlow()

    fun export(uri: Uri, format: ExportFormat, context: Context) {
        viewModelScope.launch {
            _uiState.value = ImportExportUiState.Loading
            val tmpFile = withContext(Dispatchers.IO) {
                File(context.cacheDir, "export.${format.name.lowercase()}").also { it.createNewFile() }
            }
            val result = exportVault(format, tmpFile.absolutePath)
            if (result is AppResult.Error) {
                _uiState.value = ImportExportUiState.Error(result.message ?: "Export failed")
                return@launch
            }
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { out -> tmpFile.inputStream().copyTo(out) }
                tmpFile.delete()
            }
            _uiState.value = ImportExportUiState.Success("Export complete")
        }
    }

    fun import(uri: Uri, format: ExportFormat, context: Context) {
        viewModelScope.launch {
            _uiState.value = ImportExportUiState.Loading
            val tmpFile = withContext(Dispatchers.IO) {
                val f = File(context.cacheDir, "import.${format.name.lowercase()}")
                context.contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                f
            }
            when (val result = importVault(format, tmpFile.absolutePath)) {
                is AppResult.Success -> {
                    tmpFile.delete()
                    _uiState.value = ImportExportUiState.Success("Imported ${result.data} credentials")
                }
                is AppResult.Error -> {
                    tmpFile.delete()
                    _uiState.value = ImportExportUiState.Error(result.message ?: "Import failed")
                }
            }
        }
    }
}
