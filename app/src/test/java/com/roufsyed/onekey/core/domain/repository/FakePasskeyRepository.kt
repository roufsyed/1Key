package com.roufsyed.onekey.core.domain.repository

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Passkey
import com.roufsyed.onekey.core.domain.model.PasskeySummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * In-memory [PasskeyRepository] for use-case and ViewModel tests. Records the
 * cascade calls (deleteForCredential, reassignCredential, deleteAll) so tests
 * can assert that deleting a credential never leaves passkey rows behind.
 */
class FakePasskeyRepository : PasskeyRepository {

    private val store = MutableStateFlow<Map<String, Passkey>>(emptyMap())

    val deletedForCredential = mutableListOf<String>()
    val reassigned = mutableListOf<Pair<String, String>>()
    var deleteAllCalls = 0
        private set
    var failNextDeleteAll: Throwable? = null

    val all: List<Passkey> get() = store.value.values.toList()

    override suspend fun save(passkey: Passkey): AppResult<Passkey> {
        val stored = if (passkey.id.isBlank()) passkey.copyWithId(UUID.randomUUID().toString()) else passkey
        store.value = store.value + (stored.id to stored)
        return AppResult.Success(stored)
    }

    override suspend fun getById(id: String): AppResult<Passkey> =
        store.value[id]?.let { AppResult.Success(it) }
            ?: AppResult.Error(NoSuchElementException("Passkey $id not found"))

    override suspend fun listActiveForRpId(rpId: String): AppResult<List<PasskeySummary>> =
        AppResult.Success(store.value.values.filter { it.rpId == rpId }.map { it.toSummary() })

    override fun observeForCredential(credentialId: String): Flow<List<PasskeySummary>> =
        store.map { m -> m.values.filter { it.credentialId == credentialId }.map { it.toSummary() } }

    override fun observeAllActive(): Flow<List<PasskeySummary>> =
        store.map { m -> m.values.map { it.toSummary() } }

    override suspend fun allForExport(): AppResult<PasskeyRepository.ExportBatch> =
        AppResult.Success(PasskeyRepository.ExportBatch(all, 0))

    override suspend fun deleteById(id: String): AppResult<Unit> {
        store.value = store.value - id
        return AppResult.Success(Unit)
    }

    override suspend fun deleteForCredential(credentialId: String): AppResult<Unit> {
        deletedForCredential += credentialId
        store.value = store.value.filterValues { it.credentialId != credentialId }
        return AppResult.Success(Unit)
    }

    override suspend fun reassignCredential(fromCredentialId: String, toCredentialId: String): AppResult<Unit> {
        reassigned += fromCredentialId to toCredentialId
        store.value = store.value.mapValues { (_, p) ->
            if (p.credentialId == fromCredentialId) p.copyWithCredentialId(toCredentialId) else p
        }
        return AppResult.Success(Unit)
    }

    override suspend fun deleteAll(): AppResult<Unit> {
        deleteAllCalls++
        failNextDeleteAll?.let { failNextDeleteAll = null; return AppResult.Error(it) }
        store.value = emptyMap()
        return AppResult.Success(Unit)
    }

    override suspend fun touchLastUsed(id: String, now: Long): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun markBackedUp(ids: List<String>, now: Long): AppResult<Unit> = AppResult.Success(Unit)

    private fun Passkey.copyWithId(newId: String) = Passkey(
        newId, credentialId, rpId, rpName, userName, userDisplayName, userHandle, webauthnCredentialId,
        privateKeyPkcs8, prfSecret, origin, createdAt, lastUsedAt, backedUpAt,
    )

    private fun Passkey.copyWithCredentialId(newCredentialId: String) = Passkey(
        id, newCredentialId, rpId, rpName, userName, userDisplayName, userHandle, webauthnCredentialId,
        privateKeyPkcs8, prfSecret, origin, createdAt, lastUsedAt, backedUpAt,
    )
}
