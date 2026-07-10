package com.roufsyed.onekey.core.data.snapshot

import com.roufsyed.onekey.core.data.local.entity.CredentialEntity
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.CustomField
import com.roufsyed.onekey.core.domain.model.OtpAlgorithm
import com.roufsyed.onekey.core.domain.model.OtpParams
import com.roufsyed.onekey.core.domain.model.OtpType
import com.roufsyed.onekey.core.security.CryptoManager
import com.roufsyed.onekey.core.security.EncryptedData
import com.roufsyed.onekey.core.security.HKDF_FIELD_KEY_INFO
import com.roufsyed.onekey.core.security.HKDF_TITLE_KEY_INFO
import com.roufsyed.onekey.core.security.VaultKeyHolder
import com.roufsyed.onekey.core.security.VaultLockedException
import kotlinx.coroutines.yield
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for credential-row decryption.
 *
 * Replaces the per-call inline `toDomain` bodies that previously lived inside
 * [com.roufsyed.onekey.core.data.repository.CredentialRepositoryImpl]. Three roles:
 *
 *  1. [decryptAllLeanWithLockCheck] - bulk lean projection for the
 *     [VaultSnapshotStore]. Decrypts only title/username/url per row, skips
 *     password/notes/OTP secret/custom fields. Yields between rows and
 *     re-checks [VaultKeyHolder.isUnlocked] after each yield so a mid-loop
 *     lock aborts cleanly instead of producing a partial list. Memoises the
 *     HKDF subkeys for the lifetime of the unlocked vault so an N-row decrypt
 *     does N field-AES-GCM operations, not N × 2 HKDF derivations as well.
 *
 *  2. [decrypt] - full single-row decrypt. Used by repository per-id paths
 *     (`getCredential`, `observeCredential`). Throws on failure; caller chooses
 *     whether to surface the error or wrap in `runCatching`.
 *
 *  3. [decryptOrNull] - full single-row decrypt that returns null on failure.
 *     Used by long-lived list observers (`observeRecycleBin`, `observeFavorites`,
 *     `observeRotatingOtp`, etc.). Matches the previous `toDomainOrNull()`
 *     semantics - a corrupt row drops out without poisoning the upstream
 *     StateFlow.
 *
 *  4. [onLock] - invoked synchronously by [VaultSnapshotStore.snapshotHook]
 *     from inside [VaultKeyHolder.lock]. Drops the memoised HKDF subkeys so
 *     cross-vault generations (reset -> re-setup with a different password)
 *     don't reuse subkeys derived from the prior key.
 *
 * Threading: [decryptAllLeanWithLockCheck] is `suspend` and expected to run
 * on [kotlinx.coroutines.Dispatchers.Default] (HKDF + AES-GCM are CPU-bound).
 * The single-row [decrypt] / [decryptOrNull] are plain `fun` - they don't
 * yield internally and may be called from any dispatcher the caller chose
 * via `.flowOn(...)`.
 *
 * Memoisation safety: the cached subkeys are tied to the [SecretKey]
 * instance identity ([memoisedFor] field - referential, not equality, since
 * `SecretKeySpec.equals` is content-based and would mask a key swap that
 * happened to land on the same bytes). If the vault is reset and re-set up
 * with a fresh key, the new [SecretKey] is a different instance and the
 * cache rederives. [onLock] additionally nulls the cache to remove any
 * derived subkey material from the heap between lock and re-unlock.
 */
@Singleton
class CredentialDecryptor @Inject constructor(
    private val crypto: CryptoManager,
    private val keyHolder: VaultKeyHolder,
) {

    /**
     * Identity reference of the [SecretKey] the subkey cache was derived
     * from. `@Volatile` for visibility across the lock() caller's thread
     * (which writes via [onLock]) and the Default-thread decryption loop.
     */
    @Volatile private var memoisedFor: SecretKey? = null
    @Volatile private var memoFieldKey: SecretKey? = null
    @Volatile private var memoTitleKey: SecretKey? = null

    /**
     * Lean bulk decrypt with cooperative cancellation and per-row lock
     * checks. Throws [VaultLockedException] if the vault locks mid-iteration
     * - caller (typically [VaultSnapshotStore]) catches and emits
     * [SnapshotState.Locked]. **Partial lists are NEVER returned.**
     *
     * Per-row failures (corrupt entity, mismatched cipher version) drop the
     * row silently via [decryptLeanOrNull] - matches `toDomainListSafe`
     * semantics. The flow stays alive.
     */
    suspend fun decryptAllLeanWithLockCheck(
        entities: List<CredentialEntity>,
    ): List<SnapshotCredential> {
        if (entities.isEmpty()) return emptyList()
        val vaultKey = keyHolder.requireKey()                  // throws if already locked
        val (fieldKey, titleKey) = subkeysFor(vaultKey)
        val out = ArrayList<SnapshotCredential>(entities.size)
        for (entity in entities) {
            yield()                                            // cancellation point
            if (!keyHolder.isUnlocked.value) {                 // post-yield lock check
                throw VaultLockedException()
            }
            val lean = decryptLeanOrNull(entity, vaultKey, fieldKey, titleKey)
            if (lean != null) out += lean
        }
        return out
    }

    /**
     * Full single-row decrypt. Throws if the vault is locked or the row's
     * cipher metadata is inconsistent. Used by suspend-style callers
     * (`getCredential`, `getAllCredentials`, `importCredentials`) that
     * want errors surfaced as `AppResult.Error`.
     */
    fun decrypt(entity: CredentialEntity): Credential {
        val vaultKey = keyHolder.requireKey()
        return decryptFull(entity, vaultKey)
    }

    /**
     * Full single-row decrypt that returns null on any failure. Used by
     * long-lived observers (`observeCredential`, `observeRecycleBin`,
     * `observeFavorites`, `observeRotatingOtp`, `observeHotpEntries`,
     * `observeCredentials`) where a transient throw must not poison the
     * upstream StateFlow / cold flow.
     */
    fun decryptOrNull(entity: CredentialEntity): Credential? =
        runCatching { decrypt(entity) }.getOrNull()

    /**
     * Called synchronously from [VaultKeyHolder.lock] via the
     * [com.roufsyed.onekey.core.security.VaultLockHook] installed by
     * [VaultSnapshotStore]. Drops cached subkey references so the next
     * unlocked session re-derives from the new (or same) vault key - and
     * so subkey material does not survive in the heap between sessions.
     *
     * Race note: a concurrent decryption loop on Default may have captured
     * a local reference to the cached subkey before this nullification.
     * That's safe - the loop completes its current row using the local
     * reference, then hits the next `yield()` + `isUnlocked` check and
     * throws. The instance-field nullification here is forward-looking
     * (no NEW row inside the same loop will reuse stale subkeys).
     */
    fun onLock() {
        memoisedFor = null
        memoFieldKey = null
        memoTitleKey = null
    }

    // ─────────────────────────────────────────────────────────────────────
    // internal helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns (fieldKey, titleKey) for [vaultKey], deriving if necessary.
     * Identity-checked against [memoisedFor] so a vault-reset that happens
     * to produce a byte-equal key (cryptographically negligible probability
     * but defensively guarded) does not pollute the cache.
     */
    private fun subkeysFor(vaultKey: SecretKey): Pair<SecretKey, SecretKey> {
        val cachedFor = memoisedFor
        val cachedField = memoFieldKey
        val cachedTitle = memoTitleKey
        if (cachedFor === vaultKey && cachedField != null && cachedTitle != null) {
            return cachedField to cachedTitle
        }
        val fieldKey = crypto.deriveSubkey(vaultKey, HKDF_FIELD_KEY_INFO)
        val titleKey = crypto.deriveSubkey(vaultKey, HKDF_TITLE_KEY_INFO)
        memoFieldKey = fieldKey
        memoTitleKey = titleKey
        memoisedFor = vaultKey
        return fieldKey to titleKey
    }

    /**
     * Decrypts only the snapshot-projection fields: title, username, url.
     * Skips password, notes, OTP secret, custom-field key/value. [hasOtp]
     * is derived from the presence of the encrypted-secret column without
     * decrypting it.
     *
     * `null` on failure -> caller drops the row.
     */
    private fun decryptLeanOrNull(
        entity: CredentialEntity,
        vaultKey: SecretKey,
        fieldKey: SecretKey,
        titleKey: SecretKey,
    ): SnapshotCredential? = runCatching {
        val cipherKey = if (entity.cipherVersion >= 1) fieldKey else vaultKey

        val username = decryptField(
            entity = entity,
            field = "username",
            ct = entity.usernameEncrypted,
            iv = entity.ivUsername,
            cipherKey = cipherKey,
        )

        val url = if (entity.urlEncrypted != null && entity.ivUrl != null) {
            decryptField(
                entity = entity,
                field = "url",
                ct = entity.urlEncrypted,
                iv = entity.ivUrl,
                cipherKey = cipherKey,
            )
        } else {
            entity.url
        }

        val resolvedTitle = if (entity.cipherVersion >= 2 &&
            entity.titleEncrypted != null &&
            entity.ivTitle != null
        ) {
            crypto.decrypt(
                EncryptedData(entity.titleEncrypted, entity.ivTitle),
                titleKey,
                titleAad(entity.id),
            ).toString(Charsets.UTF_8)
        } else {
            entity.title
        }

        SnapshotCredential(
            id = entity.id,
            title = resolvedTitle,
            username = username,
            url = url,
            tags = entity.tags,
            isFavorite = entity.isFavorite,
            type = CredentialType.fromNameOrDefault(entity.type),
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            accessedAt = entity.accessedAt ?: entity.updatedAt,
            hasOtp = entity.totpSecretEncrypted != null && entity.ivTotp != null,
        )
    }.getOrNull()

    /**
     * Full per-row decrypt - title, username, password, notes, url,
     * otpParams, custom fields. Mirrors the prior
     * `CredentialRepositoryImpl.toDomain()` body byte-for-byte (lines 352-405
     * pre-refactor) so the per-id observation paths preserve their existing
     * behaviour.
     */
    private fun decryptFull(entity: CredentialEntity, vaultKey: SecretKey): Credential {
        // v0 rows: encrypted with the raw vault key, no AAD (legacy, pre-DB-v12).
        // v1 rows: encrypted with the HKDF-derived field subkey, AAD = ("1k:v1|<id>|<field>"),
        //          title still plaintext.
        // v2 rows: as v1, plus title encrypted under the title subkey with AAD
        //          "1k:v2|<id>|title". Plaintext `title` column is empty.
        val cipherKey = if (entity.cipherVersion >= 1) {
            crypto.deriveSubkey(vaultKey, HKDF_FIELD_KEY_INFO)
        } else {
            vaultKey
        }

        val resolvedTitle = if (entity.cipherVersion >= 2 &&
            entity.titleEncrypted != null &&
            entity.ivTitle != null
        ) {
            val titleKey = crypto.deriveSubkey(vaultKey, HKDF_TITLE_KEY_INFO)
            crypto.decrypt(
                EncryptedData(entity.titleEncrypted, entity.ivTitle),
                titleKey,
                titleAad(entity.id),
            ).toString(Charsets.UTF_8)
        } else {
            entity.title
        }

        return Credential(
            id = entity.id,
            title = resolvedTitle,
            username = decryptField(entity, "username", entity.usernameEncrypted, entity.ivUsername, cipherKey),
            password = decryptField(entity, "password", entity.passwordEncrypted, entity.ivPassword, cipherKey),
            url = if (entity.urlEncrypted != null && entity.ivUrl != null)
                decryptField(entity, "url", entity.urlEncrypted, entity.ivUrl, cipherKey)
            else entity.url,
            notes = decryptField(entity, "notes", entity.notesEncrypted, entity.ivNotes, cipherKey),
            otpParams = decryptOtpParams(entity, cipherKey),
            tags = entity.tags,
            customFields = entity.customFields.mapIndexed { idx, cf ->
                CustomField(
                    key = if (cf.keyEncrypted != null && cf.keyIv != null)
                        decryptField(entity, "cf|$idx|k", cf.keyEncrypted, cf.keyIv, cipherKey)
                    else cf.key,
                    value = decryptField(entity, "cf|$idx|v", cf.valueEncrypted, cf.iv, cipherKey),
                    isSensitive = cf.isSensitive,
                )
            },
            isFavorite = entity.isFavorite,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            type = CredentialType.fromNameOrDefault(entity.type),
            deletedAt = entity.deletedAt,
            // Pre-MIGRATION_10_11 rows can still be null for a brief window
            // if the migration hasn't completed before a query runs. Fall
            // back to updatedAt - matches the migration's backfill rule.
            accessedAt = entity.accessedAt ?: entity.updatedAt,
            // Plaintext pass-through: null means "manually created" (or pre-v15);
            // non-null means "arrived via a foreign import". Never back-filled
            // for legacy rows - we don't know what was imported.
            importedAt = entity.importedAt,
        )
    }

    private fun decryptField(
        entity: CredentialEntity,
        field: String,
        ct: ByteArray,
        iv: ByteArray,
        cipherKey: SecretKey,
    ): String {
        val aad = if (entity.cipherVersion >= 1) fieldAad(entity.id, field) else null
        return crypto.decrypt(EncryptedData(ct, iv), cipherKey, aad).toString(Charsets.UTF_8)
    }

    private fun decryptOtpParams(entity: CredentialEntity, cipherKey: SecretKey): OtpParams? {
        if (entity.totpSecretEncrypted == null || entity.ivTotp == null) return null
        val aad = if (entity.cipherVersion >= 1) fieldAad(entity.id, "totp") else null
        val secret = crypto.decrypt(EncryptedData(entity.totpSecretEncrypted, entity.ivTotp), cipherKey, aad)
            .toString(Charsets.UTF_8)
        val safeDigits = entity.totpDigits.takeIf { it in OtpParams.MIN_DIGITS..OtpParams.MAX_DIGITS }
            ?: OtpParams.DEFAULT_DIGITS
        val safePeriod = entity.totpPeriod.takeIf { it > 0L } ?: OtpParams.DEFAULT_PERIOD_SECONDS
        val type = OtpType.fromNameOrDefault(entity.otpType)
        return OtpParams(
            type = type,
            secret = secret,
            algorithm = OtpAlgorithm.fromNameOrDefault(entity.totpAlgorithm),
            digits = safeDigits,
            period = safePeriod,
            counter = if (type == OtpType.HOTP) entity.hotpCounter?.coerceAtLeast(0L) ?: 0L else 0L,
        )
    }
}

/**
 * AAD format for per-field AES-GCM under the HKDF field subkey (cipher v1+).
 * Must stay byte-equivalent with the matching write-path AAD in
 * [com.roufsyed.onekey.core.data.repository.CredentialRepositoryImpl] and with the
 * migration AAD in
 * [com.roufsyed.onekey.core.security.CredentialCipherMigrator]. A drift here would
 * silently fail GCM tag verification on already-stored rows.
 */
internal fun fieldAad(credentialId: String, field: String): ByteArray =
    "1k:v1|$credentialId|$field".toByteArray(Charsets.UTF_8)

/**
 * AAD format for title encryption under the HKDF title subkey (cipher v2+).
 * Must stay byte-equivalent with the matching write-path AAD.
 */
internal fun titleAad(credentialId: String): ByteArray =
    "1k:v2|$credentialId|title".toByteArray(Charsets.UTF_8)
