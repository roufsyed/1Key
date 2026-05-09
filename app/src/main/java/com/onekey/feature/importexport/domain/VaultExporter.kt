package com.onekey.feature.importexport.domain

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.usecase.ExportFormat

interface VaultExporter {
    suspend fun export(credentials: List<Credential>, format: ExportFormat, path: String): AppResult<Unit>
    suspend fun exportEncrypted(
        credentials: List<Credential>,
        password: CharArray,
        format: ExportFormat,
        path: String,
        createdAtMs: Long = System.currentTimeMillis(),
        vaultVersion: Int = 0,
    ): AppResult<Unit>
}

interface VaultImporter {
    suspend fun isEncrypted(path: String): Boolean
    suspend fun parse(path: String): AppResult<ParsedImport>
    suspend fun parseEncrypted(path: String, password: CharArray): AppResult<ParsedImport>
}
