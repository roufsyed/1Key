package com.onekey.feature.importexport.domain

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.usecase.ExportFormat

interface VaultExporter {
    suspend fun export(credentials: List<Credential>, format: ExportFormat, path: String): AppResult<Unit>

    /**
     * Writes an encrypted backup envelope. New exports use V5, which embeds
     * KDF parameters and an optional Secret Key flag. The active KDF params
     * and (when SK is enabled) the in-memory SK bytes are read from the
     * caller-supplied [EncryptedExportContext] rather than from a direct
     * AuthRepository dependency - that would create a Hilt cycle through
     * SyncEngine. The export use case constructs the context from the
     * AuthRepository before calling this method.
     */
    suspend fun exportEncrypted(
        credentials: List<Credential>,
        password: CharArray,
        format: ExportFormat,
        path: String,
        context: EncryptedExportContext,
        createdAtMs: Long = System.currentTimeMillis(),
        vaultVersion: Int = 0,
    ): AppResult<Unit>

    /**
     * Returns the plaintext serialised bytes of [credentials] in [format] without writing
     * to disk or encrypting. Used by the Sync feature to feed the already-derived backup
     * encryption pipeline ([com.onekey.feature.importexport.domain.BackupEncryption.encryptWithKey])
     * directly, instead of going through the path-based [exportEncrypted].
     */
    fun serializeForSync(credentials: List<Credential>, format: ExportFormat): ByteArray
}

/**
 * Carry for the per-export configuration that VaultExporter cannot read
 * directly without creating a Hilt cycle (AuthRepository -> SyncEngine ->
 * VaultExporter -> AuthRepository). The use case layer assembles this
 * struct from the AuthRepository's `activeKdfParams()` and
 * `isSecretKeyEnabled()` reads, then hands it to the exporter.
 *
 * @param kdfParams the Argon2id (m, t, p, hashLen) tuple to embed in the V5
 *                  envelope and use for derivation.
 * @param secretKeyEnabled `true` when the V5 envelope should set FLAGS bit 0
 *                        and pull SK bytes from the holder. When `false` the
 *                        envelope is SK-free (FLAGS=0).
 */
data class EncryptedExportContext(
    val kdfParams: com.onekey.core.security.KdfParams,
    val secretKeyEnabled: Boolean,
)

interface VaultImporter {
    suspend fun isEncrypted(path: String): Boolean
    suspend fun parse(path: String): AppResult<ParsedImport>

    /**
     * Decrypts and parses an encrypted backup file.
     *
     * **Consumes [password]:** the [CharArray] is zeroed in place during decryption
     * (via [BackupEncryption.decrypt]). Callers that need the password again - e.g.
     * to also call [com.onekey.core.domain.repository.AuthRepository.setupMasterPassword]
     * during onboarding-from-backup - MUST pass a copy created with [CharArray.copyOf].
     * Failing to do so leaves the next consumer reading ASCII-space input, which silently
     * corrupts the master-password contract (vault becomes unrecoverable on next unlock).
     *
     * @param secretKey optional 16-byte raw Secret Key required when restoring a
     *                  V5 envelope that was written with the SK feature enabled.
     *                  Pass `null` on the first attempt; if the file demands SK
     *                  the result is [EncryptedParseResult.SecretKeyRequired] and
     *                  the caller retries after sourcing the SK (typically via
     *                  the QR-scanner pivot in onboarding-from-backup).
     */
    suspend fun parseEncrypted(
        path: String,
        password: CharArray,
        secretKey: ByteArray? = null,
    ): EncryptedParseResult
}
