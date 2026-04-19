package com.onekey.core.data.local.entity

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
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "username_encrypted") val usernameEncrypted: ByteArray,
    @ColumnInfo(name = "iv_username") val ivUsername: ByteArray,
    @ColumnInfo(name = "password_encrypted") val passwordEncrypted: ByteArray,
    @ColumnInfo(name = "iv_password") val ivPassword: ByteArray,
    @ColumnInfo(name = "url_encrypted") val urlEncrypted: ByteArray?,
    @ColumnInfo(name = "iv_url") val ivUrl: ByteArray?,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CredentialHistoryEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
