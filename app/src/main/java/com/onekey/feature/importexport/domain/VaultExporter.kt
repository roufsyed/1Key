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

    /**
     * Decrypts and parses an encrypted backup file.
     *
     * **Consumes [password]:** the [CharArray] is zeroed in place during decryption
     * (via [BackupEncryption.decrypt]). Callers that need the password again — e.g.
     * to also call [com.onekey.core.domain.repository.AuthRepository.setupMasterPassword]
     * during onboarding-from-backup — MUST pass a copy created with [CharArray.copyOf].
     * Failing to do so leaves the next consumer reading ASCII-space input, which silently
     * corrupts the master-password contract (vault becomes unrecoverable on next unlock).
     */
    suspend fun parseEncrypted(path: String, password: CharArray): AppResult<ParsedImport>
}
