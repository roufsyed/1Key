package com.onekey.core.domain.repository

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Tag
import com.onekey.core.domain.model.TagWithCount
import kotlinx.coroutines.flow.Flow

interface TagRepository {
    fun observeTags(): Flow<List<Tag>>
    fun observeTagsWithCounts(): Flow<List<TagWithCount>>
    suspend fun getTags(): AppResult<List<Tag>>
    suspend fun addTag(tag: Tag): AppResult<Unit>
    suspend fun deleteTag(name: String): AppResult<Unit>
}
