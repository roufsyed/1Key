package com.roufsyed.onekey.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One WebAuthn passkey (discoverable ES256 credential) belonging to a vault
 * credential. Added in DB v17 (`MIGRATION_16_17`).
 *
 * Every text and binary field is encrypted under the HKDF field subkey
 * (`1key-field-enc-v1`) with AAD `"1k:v1|pk:<id>|<field>"`: the `pk:`
 * namespace mirrors the history table's `h:` so ciphertext can never be swapped
 * between tables or rows. Only timestamps, the owning credential's id and the
 * cipher version are plaintext, the same "metadata, not secrets" rule
 * `CredentialEntity` follows.
 *
 * Deliberately NO foreign key to `credentials`: every credential write is a
 * `REPLACE` upsert (`CredentialDao.upsert`), which SQLite implements as
 * delete-then-insert, so an `ON DELETE CASCADE` child would be wiped on every
 * edit. Cleanup is explicit instead (`PasskeyDao.deleteForCredential`), called
 * from the same use cases that delete history rows.
 */
@Entity(
    tableName = "passkeys",
    indices = [Index("credential_id")],
)
data class PasskeyEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "credential_id") val credentialId: String,
    @ColumnInfo(name = "rp_id_encrypted") val rpIdEncrypted: ByteArray,
    @ColumnInfo(name = "iv_rp_id") val ivRpId: ByteArray,
    @ColumnInfo(name = "rp_name_encrypted") val rpNameEncrypted: ByteArray,
    @ColumnInfo(name = "iv_rp_name") val ivRpName: ByteArray,
    @ColumnInfo(name = "user_name_encrypted") val userNameEncrypted: ByteArray,
    @ColumnInfo(name = "iv_user_name") val ivUserName: ByteArray,
    @ColumnInfo(name = "user_display_name_encrypted") val userDisplayNameEncrypted: ByteArray,
    @ColumnInfo(name = "iv_user_display_name") val ivUserDisplayName: ByteArray,
    // The relying party's user.id bytes, stored verbatim.
    @ColumnInfo(name = "user_handle_encrypted") val userHandleEncrypted: ByteArray,
    @ColumnInfo(name = "iv_user_handle") val ivUserHandle: ByteArray,
    // The WebAuthn credential ID (32 random bytes chosen at registration).
    @ColumnInfo(name = "webauthn_id_encrypted") val webauthnIdEncrypted: ByteArray,
    @ColumnInfo(name = "iv_webauthn_id") val ivWebauthnId: ByteArray,
    // PKCS#8 DER of the P-256 private key. Decrypted only inside the signing
    // activity after user verification; never held in a StateFlow.
    @ColumnInfo(name = "private_key_encrypted") val privateKeyEncrypted: ByteArray,
    @ColumnInfo(name = "iv_private_key") val ivPrivateKey: ByteArray,
    // 32-byte PRF (hmac-secret) seed. Null until the prf extension ships.
    @ColumnInfo(name = "prf_secret_encrypted") val prfSecretEncrypted: ByteArray? = null,
    @ColumnInfo(name = "iv_prf_secret") val ivPrfSecret: ByteArray? = null,
    // Who created the passkey: a browser origin, or a native app's package name
    // plus signing-certificate SHA-256 (the pinning rule in the passkeys plan,
    // section 5.3). Encoded by PasskeyRepositoryImpl, encrypted like any field.
    @ColumnInfo(name = "origin_binding_encrypted") val originBindingEncrypted: ByteArray,
    @ColumnInfo(name = "iv_origin_binding") val ivOriginBinding: ByteArray,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long? = null,
    // Set when the passkey has been written into an encrypted backup or sync
    // file, or arrived through one. Drives the WebAuthn BS flag honestly.
    @ColumnInfo(name = "backed_up_at") val backedUpAt: Long? = null,
    // 2 = HKDF field subkey + per-field AAD, the only version this table has
    // ever had. The column exists so a future rotation can reuse the
    // CredentialCipherMigrator pattern. The defaultValue must match
    // MIGRATION_16_17's DEFAULT clause exactly or Room's schema validation fails.
    @ColumnInfo(name = "cipher_version", defaultValue = "2") val cipherVersion: Int = 2,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PasskeyEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
