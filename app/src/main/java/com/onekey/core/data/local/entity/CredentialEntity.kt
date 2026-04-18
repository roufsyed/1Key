package com.onekey.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.onekey.core.data.local.database.Converters

@Entity(tableName = "credentials")
@TypeConverters(Converters::class)
data class CredentialEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "username_encrypted") val usernameEncrypted: ByteArray,
    @ColumnInfo(name = "password_encrypted") val passwordEncrypted: ByteArray,
    @ColumnInfo(name = "url") val url: String,
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
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CredentialEntity) return false
        return id == other.id && updatedAt == other.updatedAt
    }

    override fun hashCode(): Int = id.hashCode()
}

data class CustomFieldEntity(
    val key: String,
    val valueEncrypted: ByteArray,
    val iv: ByteArray,
    val isSensitive: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustomFieldEntity) return false
        return key == other.key
    }

    override fun hashCode(): Int = key.hashCode()
}
