package com.onekey.feature.importexport.domain

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.usecase.ExportFormat

interface VaultExporter {
    suspend fun export(credentials: List<Credential>, format: ExportFormat, path: String): AppResult<Unit>
}

interface VaultImporter {
    suspend fun parse(path: String, format: ExportFormat): AppResult<List<Credential>>
}
