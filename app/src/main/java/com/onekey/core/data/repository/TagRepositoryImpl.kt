package com.onekey.core.data.repository

import com.onekey.core.data.local.dao.TagDao
import com.onekey.core.data.local.entity.TagEntity
import com.onekey.core.di.ApplicationScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Tag
import com.onekey.core.domain.model.TagWithCount
import com.onekey.core.domain.model.runCatchingResult
import com.onekey.core.domain.repository.TagRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagRepositoryImpl @Inject constructor(
    private val dao: TagDao,
    @ApplicationScope appScope: CoroutineScope,
) : TagRepository {

    // Started eagerly at singleton creation time (app startup) so that
    // VaultScreen always receives a pre-loaded value on first render.
    private val tagsWithCountsFlow: StateFlow<List<TagWithCount>> =
        dao.observeTagsWithCounts()
            .map { list ->
                list.map { row -> TagWithCount(Tag(row.name, row.color, row.icon, row.isDefault), row.count) }
            }
            .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    override fun observeTags(): Flow<List<Tag>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeTagsWithCounts(): Flow<List<TagWithCount>> = tagsWithCountsFlow

    override suspend fun getTags(): AppResult<List<Tag>> = runCatchingResult {
        dao.getAll().map { it.toDomain() }
    }

    override suspend fun addTag(tag: Tag): AppResult<Unit> = runCatchingResult {
        dao.insert(tag.toEntity())
    }

    override suspend fun deleteTag(name: String): AppResult<Unit> = runCatchingResult {
        dao.deleteByName(name)
    }

    private fun TagEntity.toDomain() = Tag(name, color, icon, isDefault)
    private fun Tag.toEntity() = TagEntity(name, color, icon, System.currentTimeMillis(), isDefault)
}
