package com.roufsyed.onekey.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "credential_history",
    indices = [Index("credential_id")],
)
data class CredentialHistoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "credential_id") val credentialId: String,
    // Plaintext title (legacy). Used for v0 rows; cleared and ignored on v2+
    // rows in favour of `titleEncrypted`. Empty string for v2.
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "title_encrypted") val titleEncrypted: ByteArray? = null,
    @ColumnInfo(name = "iv_title") val ivTitle: ByteArray? = null,
    @ColumnInfo(name = "username_encrypted") val usernameEncrypted: ByteArray,
    @ColumnInfo(name = "iv_username") val ivUsername: ByteArray,
    @ColumnInfo(name = "password_encrypted") val passwordEncrypted: ByteArray,
    @ColumnInfo(name = "iv_password") val ivPassword: ByteArray,
    @ColumnInfo(name = "url_encrypted") val urlEncrypted: ByteArray?,
    @ColumnInfo(name = "iv_url") val ivUrl: ByteArray?,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    // ── Cipher version (added in DB v14) ─────────────────────────────────────
    // 0 = legacy: AES-GCM with the raw vault key, no AAD, plaintext title.
    // 2 = HKDF field/title subkeys + per-field AAD + encrypted title. Matches the
    //     `credentials` table's v2 semantics; v1 is unused for this table.
    //
    // Read path dispatches on this; write path always emits v2. Existing rows
    // are silently re-encrypted by CredentialCipherMigrator on the next unlock,
    // without bumping `modified_at`.
    @ColumnInfo(name = "cipher_version", defaultValue = "0") val cipherVersion: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CredentialHistoryEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
