package com.onekey.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.onekey.core.data.local.database.Converters

@Entity(
    tableName = "credentials",
    indices = [Index(value = ["deleted_at"], name = "index_credentials_deleted_at")],
)
@TypeConverters(Converters::class)
data class CredentialEntity(
    @PrimaryKey val id: String,
    // Plaintext title (legacy). Read for v0/v1 rows; cleared and ignored on
    // v2+ rows in favour of `titleEncrypted`. Kept as a non-null column for
    // schema compat - empty string for v2 rows. See `cipherVersion` doc.
    @ColumnInfo(name = "title") val title: String,
    // Title ciphertext + IV. Populated on cipher_version >= 2; null otherwise.
    // Encrypted under the HKDF title-subkey ("1key-title-enc-v1") with
    // AAD = "1k:v2|<id>|title", binding the row to its own title.
    @ColumnInfo(name = "title_encrypted") val titleEncrypted: ByteArray? = null,
    @ColumnInfo(name = "iv_title") val ivTitle: ByteArray? = null,
    @ColumnInfo(name = "username_encrypted") val usernameEncrypted: ByteArray,
    @ColumnInfo(name = "password_encrypted") val passwordEncrypted: ByteArray,
    // url stored plaintext pre-v4; encrypted going forward. Legacy column kept for schema compat.
    @ColumnInfo(name = "url") val url: String = "",
    @ColumnInfo(name = "url_encrypted") val urlEncrypted: ByteArray? = null,
    @ColumnInfo(name = "iv_url") val ivUrl: ByteArray? = null,
    @ColumnInfo(name = "notes_encrypted") val notesEncrypted: ByteArray,
    @ColumnInfo(name = "totp_secret_encrypted") val totpSecretEncrypted: ByteArray?,
    @ColumnInfo(name = "tags") val tags: List<String>,
    @ColumnInfo(name = "custom_fields") val customFields: List<CustomFieldEntity>,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    // Last "used" timestamp from the source export (Firefox `timeLastUsed`,
    // similar in others). Nullable so legacy rows that pre-date DB v10 stay
    // null instead of being misread as "accessed at epoch 0". Not auto-updated
    // when the user views a credential - purely import-driven for now.
    @ColumnInfo(name = "accessed_at") val accessedAt: Long? = null,
    // Epoch-ms timestamp marking when a row arrived via a foreign import
    // (JSON / CSV / encrypted backup). Null for in-app creates and for pre-v15
    // rows. Plaintext audit-trail column - the value is metadata, not a secret.
    // Written exclusively by `VaultImporterImpl`; never set by save / update
    // paths so a manual edit of an imported credential keeps the original
    // import marker. See MIGRATION_14_15.
    @ColumnInfo(name = "imported_at") val importedAt: Long? = null,
    @ColumnInfo(name = "iv_username") val ivUsername: ByteArray,
    @ColumnInfo(name = "iv_password") val ivPassword: ByteArray,
    @ColumnInfo(name = "iv_notes") val ivNotes: ByteArray,
    @ColumnInfo(name = "iv_totp") val ivTotp: ByteArray?,
    // defaultValue mirrors the DEFAULT clause MIGRATION_1_2 supplies. Adding the
    // annotation post-ship changes Room's entity identity hash, so DB version was
    // bumped to 8 with a no-op MIGRATION_7_8 to give existing installs a clean
    // migration path that re-stores the new hash.
    @ColumnInfo(name = "is_favorite", defaultValue = "0") val isFavorite: Boolean = false,
    // Stored as enum name string (e.g. "LOGIN"). Matches MIGRATION_5_6's DEFAULT 'LOGIN'.
    @ColumnInfo(name = "type", defaultValue = "LOGIN") val type: String = "LOGIN",
    // Null = active. Non-null = soft-deleted at this epoch ms; auto-purged after 30 days.
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    // ── 2FA params (added in DB v9) ──────────────────────────────────────────
    // These are *metadata* describing how to use `totpSecretEncrypted`, not secrets
    // themselves - algorithm/period/digits/counter being plaintext doesn't help an
    // attacker without the secret. Storing them as columns (vs. an encrypted blob)
    // lets the DAO partition rotating-vs-HOTP entries with a WHERE clause, which is
    // load-bearing for keeping HOTP out of the per-second recompute loop.
    //
    // For pre-v9 rows the DEFAULT clauses on MIGRATION_8_9 yield TOTP / SHA1 / 6 / 30 -
    // exactly the constants the old `TotpGenerator` hard-coded, so existing entries
    // produce bit-identical codes.
    @ColumnInfo(name = "otp_type", defaultValue = "TOTP") val otpType: String = "TOTP",
    @ColumnInfo(name = "totp_algorithm", defaultValue = "SHA1") val totpAlgorithm: String = "SHA1",
    @ColumnInfo(name = "totp_digits", defaultValue = "6") val totpDigits: Int = 6,
    @ColumnInfo(name = "totp_period", defaultValue = "30") val totpPeriod: Long = 30L,
    // Null for non-HOTP entries; persisted counter for HOTP. Increment is a transactional
    // DAO write (CredentialDao.atomicIncrementHotpCounter) - never mutated in memory.
    @ColumnInfo(name = "hotp_counter") val hotpCounter: Long? = null,
    // ── Cipher version (added in DB v12) ─────────────────────────────────────
    // 0 = legacy: AES-GCM with the raw vault key, no AAD on credential fields.
    //     Title is plaintext.
    // 1 = HKDF field-subkey + per-field AAD ("1k:v1|<id>|<field>"). Title still
    //     plaintext.
    // 2 = adds: title encrypted under the HKDF title-subkey with AAD
    //     "1k:v2|<id>|title". Plaintext `title` column is cleared.
    //
    // Read path dispatches on this value; write path always emits v2. Existing
    // rows are silently re-encrypted to v2 on the next unlock by
    // CredentialCipherMigrator - without bumping `updated_at`.
    @ColumnInfo(name = "cipher_version", defaultValue = "0") val cipherVersion: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CredentialEntity) return false
        return id == other.id && updatedAt == other.updatedAt
    }

    override fun hashCode(): Int = id.hashCode()
}

data class CustomFieldEntity(
    // key stored plaintext pre-v4; encrypted going forward. Legacy field kept for JSON compat.
    val key: String = "",
    val keyEncrypted: ByteArray? = null,
    val keyIv: ByteArray? = null,
    val valueEncrypted: ByteArray,
    val iv: ByteArray,
    val isSensitive: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustomFieldEntity) return false
        return valueEncrypted.contentEquals(other.valueEncrypted) && iv.contentEquals(other.iv)
    }

    override fun hashCode(): Int = 31 * valueEncrypted.contentHashCode() + iv.contentHashCode()
}
