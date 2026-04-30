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
    @ColumnInfo(name = "title") val title: String,
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
