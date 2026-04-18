package com.onekey.core.data.local.dao

import androidx.room.*
import com.onekey.core.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

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
