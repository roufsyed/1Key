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
    @ColumnInfo(name = "is_default") val isDefault: Boolean = false,
)
