package com.onekey.core.security

import com.onekey.core.data.local.dao.CredentialDao
import com.onekey.core.data.local.dao.CredentialHistoryDao
import com.onekey.core.data.local.entity.CredentialEntity
import com.onekey.core.data.local.entity.CredentialHistoryEntity
import com.onekey.core.data.local.entity.CustomFieldEntity
import com.onekey.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-encrypts older credential rows to the latest cipher version after every unlock.
 *
 * Why: DB v12 introduced HKDF-derived field subkeys (L4) and per-field AAD (H3).
 * DB v13 adds title encryption (H1). Existing rows stay readable because
 * [CredentialRepositoryImpl.toDomain] dispatches on `cipher_version`, but they
 * don't get the new protections until they're rewritten. This migrator walks the
 * legacy rows in small batches and converts them in-place to v2.
 *
 * Migration paths:
 *   v0 → v2: re-encrypt every field with the HKDF field subkey + per-field AAD,
 *            and move the title into title_encrypted.
 *   v1 → v2: keep field ciphertexts as they are (already v1-correct) and add the
 *            title ciphertext.
 *
 * Properties:
 *   - Idempotent. Re-running is a no-op once `countLegacyCipher() == 0`.
 *   - Resumable. The DB is the source of truth for progress, so a kill mid-batch
 *     simply means the next unlock continues from where it left off.
 *   - Doesn't touch `updated_at`. The original timestamp is preserved so the user's
 *     "last edited" view doesn't shift just because the cipher rotated underneath.
 *   - Cooperative cancellation. Lock cancels the in-flight job; the partially
 *     migrated state on disk is consistent at every row boundary.
 */
@Singleton
class CredentialCipherMigrator @Inject constructor(
    private val dao: CredentialDao,
    private val historyDao: CredentialHistoryDao,
    private val crypto: CryptoManager,
    private val keyHolder: VaultKeyHolder,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    private var job: Job? = null

    private val _isMigrating = MutableStateFlow(false)

    /**
     * True while [migrateAll]'s row/history loops are active. Used by
     * [com.onekey.core.data.snapshot.VaultSnapshotStore] to gate the upstream
     * `dao.observeListRaw` subscription - while a migration is rewriting
     * legacy rows, every per-batch `upsert` fires a Room invalidation that
     * would trigger the snapshot to re-decrypt the entire vault. By
     * suppressing the snapshot's subscription during migration, the storm
     * is eliminated entirely (one re-decrypt at the end vs. ~N/BATCH_SIZE).
     *
     * Emits `false` on construction, `true` while [migrateAll] runs, `false`
     * when it completes or is cancelled (via the [collectLatest] re-emit on
     * lock - the previous job's `finally` flips it back).
     */
    val isMigrating: StateFlow<Boolean> = _isMigrating.asStateFlow()

    fun start() {
        appScope.launch {
            keyHolder.isUnlocked.collectLatest { unlocked ->
                job?.cancel()
                job = null
                if (unlocked) {
                    // Flip the flag BEFORE launching the migration
                    // coroutine. This narrows but does NOT eliminate the
                    // race against [com.onekey.core.data.snapshot.VaultSnapshotStore]'s
                    // own coordinator: both observe `keyHolder.isUnlocked`
                    // via independent collectors, so whichever resumes
                    // first on the unlock tick wins. The snapshot may
                    // observe (unlocked=true, migrating=false) for one
                    // coordinator pass, subscribe to `dao.observeListRaw`,
                    // and start one decrypt pass before this collector
                    // resumes and flips the flag. The flag flip then
                    // cancels the snapshot's upstream and the snapshot
                    // settles on Loading. Net cost: at most one wasted
                    // partial decrypt on the unlock-immediately-followed-
                    // by-migration path. Re-decryption is idempotent and
                    // safe - the snapshot eventually settles on the
                    // correct Loaded(...) after migration completes.
                    _isMigrating.value = true
                    job = appScope.launch(Dispatchers.Default) {
                        try {
                            migrateAll()
                        } finally {
                            _isMigrating.value = false
                        }
                    }
                } else {
                    // Belt-and-suspenders: ensure the flag is false whenever
                    // a previous job was cancelled before its finally block
                    // ran. In practice the finally fires, but resetting here
                    // guarantees consistency at the collectLatest level.
                    _isMigrating.value = false
                }
            }
        }
    }

    private suspend fun migrateAll() {
        val vaultKey = runCatching { keyHolder.requireKey() }.getOrNull() ?: return
        val fieldKey = crypto.deriveSubkey(vaultKey, HKDF_FIELD_KEY_INFO)
        val titleKey = crypto.deriveSubkey(vaultKey, HKDF_TITLE_KEY_INFO)

        // Pass 1 - credentials table.
        while (true) {
            val batch = runCatching { dao.getLegacyCipherBatch(BATCH_SIZE) }.getOrNull()
                ?: break
            if (batch.isEmpty()) break
            for (entity in batch) {
                yield()  // give cancellation a chance between rows
                runCatching { migrateRow(entity, vaultKey, fieldKey, titleKey) }
                    // A single corrupt row mustn't stall the rest of the migration.
                    // The row stays at its prior cipher_version and remains readable
                    // via the legacy path; the migrator simply skips it on subsequent passes.
            }
        }

        // Pass 2 - credential_history table. Same legacy AES-GCM/raw-vault-key
        // shape as v0 credentials, but no notes/totp/custom fields to deal with.
        while (true) {
            val batch = runCatching { historyDao.getLegacyCipherBatch(BATCH_SIZE) }.getOrNull()
                ?: return
            if (batch.isEmpty()) return
            for (entity in batch) {
                yield()
                runCatching { migrateHistoryRow(entity, vaultKey, fieldKey, titleKey) }
            }
        }
    }

    private suspend fun migrateRow(
        entity: CredentialEntity,
        vaultKey: SecretKey,
        fieldKey: SecretKey,
        titleKey: SecretKey,
    ) {
        // Always need to encrypt the title for v2.
        val titleBytes = entity.title.toByteArray(Charsets.UTF_8)
        val encTitle = crypto.encrypt(titleBytes, titleKey, titleAad(entity.id))
        titleBytes.fill(0)

        val migrated: CredentialEntity = if (entity.cipherVersion >= 1) {
            // v1 → v2: field ciphertexts are already correct, just add the title.
            entity.copy(
                title = "",
                titleEncrypted = encTitle.ciphertext,
                ivTitle = encTitle.iv,
                cipherVersion = 2,
            )
        } else {
            // v0 → v2: re-encrypt every field with the HKDF subkey + AAD, plus title.
            val username = crypto.decrypt(EncryptedData(entity.usernameEncrypted, entity.ivUsername), vaultKey)
            val password = crypto.decrypt(EncryptedData(entity.passwordEncrypted, entity.ivPassword), vaultKey)
            val notes    = crypto.decrypt(EncryptedData(entity.notesEncrypted, entity.ivNotes), vaultKey)
            val urlBytes = if (entity.urlEncrypted != null && entity.ivUrl != null) {
                crypto.decrypt(EncryptedData(entity.urlEncrypted, entity.ivUrl), vaultKey)
            } else {
                entity.url.toByteArray(Charsets.UTF_8)
            }
            val totpBytes = if (entity.totpSecretEncrypted != null && entity.ivTotp != null) {
                crypto.decrypt(EncryptedData(entity.totpSecretEncrypted, entity.ivTotp), vaultKey)
            } else null

            val encUsername = crypto.encrypt(username, fieldKey, fieldAad(entity.id, "username"))
            val encPassword = crypto.encrypt(password, fieldKey, fieldAad(entity.id, "password"))
            val encNotes    = crypto.encrypt(notes,    fieldKey, fieldAad(entity.id, "notes"))
            val encUrl      = crypto.encrypt(urlBytes, fieldKey, fieldAad(entity.id, "url"))
            val encTotp     = totpBytes?.let { crypto.encrypt(it, fieldKey, fieldAad(entity.id, "totp")) }
            username.fill(0); password.fill(0); notes.fill(0); urlBytes.fill(0); totpBytes?.fill(0)

            val migratedCustomFields = entity.customFields.mapIndexed { idx, cf ->
                val keyBytes = if (cf.keyEncrypted != null && cf.keyIv != null) {
                    crypto.decrypt(EncryptedData(cf.keyEncrypted, cf.keyIv), vaultKey)
                } else {
                    cf.key.toByteArray(Charsets.UTF_8)
                }
                val valueBytes = crypto.decrypt(EncryptedData(cf.valueEncrypted, cf.iv), vaultKey)
                val encKey = crypto.encrypt(keyBytes, fieldKey, fieldAad(entity.id, "cf|$idx|k"))
                val encVal = crypto.encrypt(valueBytes, fieldKey, fieldAad(entity.id, "cf|$idx|v"))
                keyBytes.fill(0); valueBytes.fill(0)
                CustomFieldEntity(
                    key = "",
                    keyEncrypted = encKey.ciphertext,
                    keyIv = encKey.iv,
                    valueEncrypted = encVal.ciphertext,
                    iv = encVal.iv,
                    isSensitive = cf.isSensitive,
                )
            }

            entity.copy(
                title = "",
                titleEncrypted = encTitle.ciphertext, ivTitle = encTitle.iv,
                usernameEncrypted = encUsername.ciphertext, ivUsername = encUsername.iv,
                passwordEncrypted = encPassword.ciphertext, ivPassword = encPassword.iv,
                notesEncrypted    = encNotes.ciphertext,    ivNotes    = encNotes.iv,
                url = "",
                urlEncrypted = encUrl.ciphertext, ivUrl = encUrl.iv,
                totpSecretEncrypted = encTotp?.ciphertext, ivTotp = encTotp?.iv,
                customFields = migratedCustomFields,
                cipherVersion = 2,
            )
        }

        // updated_at intentionally NOT bumped - see class KDoc.
        dao.upsert(migrated)
    }

    private suspend fun migrateHistoryRow(
        entity: CredentialHistoryEntity,
        vaultKey: SecretKey,
        fieldKey: SecretKey,
        titleKey: SecretKey,
    ) {
        // Decrypt v0 fields under the raw vault key (no AAD).
        val username = crypto.decrypt(EncryptedData(entity.usernameEncrypted, entity.ivUsername), vaultKey)
        val password = crypto.decrypt(EncryptedData(entity.passwordEncrypted, entity.ivPassword), vaultKey)
        val urlBytes = if (entity.urlEncrypted != null && entity.ivUrl != null) {
            crypto.decrypt(EncryptedData(entity.urlEncrypted, entity.ivUrl), vaultKey)
        } else null

        // Re-encrypt under v2: HKDF subkeys + per-field AAD ("h:" prefix on the id
        // namespaces history rows separately from credential rows). AAD shape must
        // match the read path in CredentialHistoryRepositoryImpl - keep in lock-step.
        val encUsername = crypto.encrypt(username, fieldKey, historyFieldAad(entity.id, "username"))
        val encPassword = crypto.encrypt(password, fieldKey, historyFieldAad(entity.id, "password"))
        val encUrl      = urlBytes?.let { crypto.encrypt(it, fieldKey, historyFieldAad(entity.id, "url")) }
        username.fill(0); password.fill(0); urlBytes?.fill(0)

        val titleBytes = entity.title.toByteArray(Charsets.UTF_8)
        val encTitle = crypto.encrypt(titleBytes, titleKey, historyTitleAad(entity.id))
        titleBytes.fill(0)

        // Targeted UPDATE rather than @Update on the entity so columns we don't
        // touch (modified_at, credential_id) keep their values without us having
        // to re-supply them from the loaded entity.
        historyDao.upgradeRowToV2(
            id = entity.id,
            title = "",
            titleCt = encTitle.ciphertext, titleIv = encTitle.iv,
            uCt = encUsername.ciphertext, uIv = encUsername.iv,
            pCt = encPassword.ciphertext, pIv = encPassword.iv,
            urlCt = encUrl?.ciphertext, urlIv = encUrl?.iv,
        )
    }

    private fun fieldAad(credentialId: String, field: String): ByteArray =
        "1k:v1|$credentialId|$field".toByteArray(Charsets.UTF_8)

    private fun titleAad(credentialId: String): ByteArray =
        "1k:v2|$credentialId|title".toByteArray(Charsets.UTF_8)

    private fun historyFieldAad(historyId: String, field: String): ByteArray =
        "1k:v1|h:$historyId|$field".toByteArray(Charsets.UTF_8)

    private fun historyTitleAad(historyId: String): ByteArray =
        "1k:v2|h:$historyId|title".toByteArray(Charsets.UTF_8)

    private companion object {
        // Small enough that a long migration doesn't hold the DB lock for too long
        // and that yield() between rows lets the UI thread breathe; large enough
        // that the per-batch query overhead is amortised.
        const val BATCH_SIZE = 32
    }
}
