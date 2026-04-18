package com.onekey.core.data.repository

import com.onekey.core.data.local.dao.TagDao
import com.onekey.core.data.local.entity.TagEntity
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Tag
import com.onekey.core.domain.model.runCatchingResult
import com.onekey.core.domain.repository.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagRepositoryImpl @Inject constructor(
    private val dao: TagDao,
) : TagRepository {

    override fun observeTags(): Flow<List<Tag>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getTags(): AppResult<List<Tag>> = runCatchingResult {
        dao.getAll().map { it.toDomain() }
    }

    override suspend fun addTag(tag: Tag): AppResult<Unit> = runCatchingResult {
        dao.insert(tag.toEntity())
    }

    override suspend fun deleteTag(name: String): AppResult<Unit> = runCatchingResult {
        dao.deleteByName(name)
    }

    private fun TagEntity.toDomain() = Tag(name, color, icon)
    private fun Tag.toEntity() = TagEntity(name, color, icon, System.currentTimeMillis())
}
