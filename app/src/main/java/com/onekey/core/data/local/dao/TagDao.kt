package com.onekey.core.data.local.dao

import androidx.room.*
import com.onekey.core.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

data class TagWithCountRow(
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "color") val color: Int,
    @ColumnInfo(name = "icon") val icon: String,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,
    @ColumnInfo(name = "count") val count: Int,
)

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    // The credentials.tags column is a Gson-serialized JSON array. Anchor on the quoted
    // token (`"name"`) so a tag whose name is a substring of another doesn't bleed counts.
    @Query("""
        SELECT t.name, t.color, t.icon, t.is_default,
               (SELECT COUNT(*) FROM credentials
                WHERE deleted_at IS NULL AND tags LIKE '%"' || t.name || '"%') AS count
        FROM tags t
        ORDER BY t.name ASC
    """)
    fun observeTagsWithCounts(): Flow<List<TagWithCountRow>>

    @Query("SELECT * FROM tags ORDER BY name ASC")
    suspend fun getAll(): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<TagEntity>)

    @Delete
    suspend fun delete(tag: TagEntity)

    @Query("DELETE FROM tags WHERE name = :name")
    suspend fun deleteByName(name: String)
}
