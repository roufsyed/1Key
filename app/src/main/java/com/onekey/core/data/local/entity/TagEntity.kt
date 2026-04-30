package com.onekey.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val name: String,
    @ColumnInfo(name = "color") val color: Int,
    @ColumnInfo(name = "icon") val icon: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    // Matches MIGRATION_2_3's DEFAULT 0 clause; see CredentialEntity for why the
    // identity hash bump from this annotation requires the v7→v8 no-op migration.
    @ColumnInfo(name = "is_default", defaultValue = "0") val isDefault: Boolean = false,
)
