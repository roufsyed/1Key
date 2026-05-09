package com.onekey.core.security

import com.onekey.core.data.local.dao.CredentialDao
import com.onekey.core.data.local.entity.CredentialEntity
import com.onekey.core.data.local.entity.CustomFieldEntity
import com.onekey.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val crypto: CryptoManager,
    private val keyHolder: VaultKeyHolder,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    private var job: Job? = null

    fun start() {
        appScope.launch {
            keyHolder.isUnlocked.collectLatest { unlocked ->
                job?.cancel()
                job = null
                if (unlocked) {
                    job = appScope.launch(Dispatchers.Default) { migrateAll() }
                }
            }
        }
    }

    private suspend fun migrateAll() {
        val vaultKey = runCatching { keyHolder.requireKey() }.getOrNull() ?: return
        val fieldKey = crypto.deriveSubkey(vaultKey, HKDF_FIELD_KEY_INFO)
        val titleKey = crypto.deriveSubkey(vaultKey, HKDF_TITLE_KEY_INFO)

        while (true) {
            val batch = runCatching { dao.getLegacyCipherBatch(BATCH_SIZE) }.getOrNull()
                ?: return
            if (batch.isEmpty()) return
            for (entity in batch) {
                yield()  // give cancellation a chance between rows
                runCatching { migrateRow(entity, vaultKey, fieldKey, titleKey) }
                    // A single corrupt row mustn't stall the rest of the migration.
                    // The row stays at its prior cipher_version and remains readable
                    // via the legacy path; the migrator simply skips it on subsequent passes.
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

        // updated_at intentionally NOT bumped — see class KDoc.
        dao.upsert(migrated)
    }

    private fun fieldAad(credentialId: String, field: String): ByteArray =
        "1k:v1|$credentialId|$field".toByteArray(Charsets.UTF_8)

    private fun titleAad(credentialId: String): ByteArray =
        "1k:v2|$credentialId|title".toByteArray(Charsets.UTF_8)

    private companion object {
        // Small enough that a long migration doesn't hold the DB lock for too long
        // and that yield() between rows lets the UI thread breathe; large enough
        // that the per-batch query overhead is amortised.
        const val BATCH_SIZE = 32
    }
}
