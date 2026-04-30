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
    // defaultValue matches MIGRATION_2_3's DEFAULT 0 clause so Room's _TableInfo check
    // doesn't reject the migrated schema for a missing default.
    @ColumnInfo(name = "is_default", defaultValue = "0") val isDefault: Boolean = false,
)
