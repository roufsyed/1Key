package com.onekey.core.data.snapshot

import androidx.paging.PagingSource
import androidx.sqlite.db.SupportSQLiteQuery
import com.onekey.core.data.local.dao.CredentialDao
import com.onekey.core.data.local.entity.CredentialEntity
import com.onekey.core.security.CryptoManager
import com.onekey.core.security.HKDF_FIELD_KEY_INFO
import com.onekey.core.security.HKDF_TITLE_KEY_INFO
import com.onekey.core.security.VaultKeyHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Behavioural locks for [VaultSnapshotStore]. Plain JVM - no Robolectric,
 * no Room - built around a fake [CredentialDao] (controllable
 * `observeListRaw` + `observeCount`) and a real
 * [CredentialDecryptor] + [CryptoManager] + [VaultKeyHolder]. Synthetic v2
 * entities are produced via the production encrypt path so AAD shapes
 * round-trip with no special-case test path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultSnapshotStoreTest {

    private val crypto = CryptoManager()
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var keyHolder: VaultKeyHolder
    private lateinit var vaultKey: SecretKey
    private lateinit var decryptor: CredentialDecryptor
    private lateinit var fakeDao: FakeCredentialDao
    private lateinit var migrationStatus: MutableStateFlow<Boolean>
    private lateinit var snapshotScope: CoroutineScope
    private lateinit var store: VaultSnapshotStore

    @Before fun setup() {
        keyHolder = VaultKeyHolder()
        vaultKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        decryptor = CredentialDecryptor(crypto, keyHolder)
        fakeDao = FakeCredentialDao()
        migrationStatus = MutableStateFlow(false)
        snapshotScope = CoroutineScope(SupervisorJob() + testDispatcher)
        store = VaultSnapshotStore(
            dao = fakeDao,
            decryptor = decryptor,
            keyHolder = keyHolder,
            migrationStatus = migrationStatus.asStateFlow(),
            snapshotScope = snapshotScope,
        )
    }

    @After fun teardown() {
        snapshotScope.cancel()
    }

    // ── lifecycle ────────────────────────────────────────────────────────

    @Test fun initial_state_is_Locked_before_any_unlock() {
        // Vault is locked, no advance has happened. The store starts at
        // Locked by construction.
        assertEquals(SnapshotState.Locked, store.state.value)
    }

    @Test fun unlock_emits_Loaded_with_lean_credentials() = runTest(testDispatcher) {
        val entities = listOf(
            buildV2Entity("a", "GitHub", username = "alice"),
            buildV2Entity("b", "Gmail", username = "bob"),
        )
        fakeDao.rows.value = entities
        fakeDao.count.value = entities.size

        keyHolder.setKey(vaultKey)
        advanceUntilIdle()

        val loaded = store.state.value as SnapshotState.Loaded
        assertEquals(setOf("a", "b"), loaded.credentials.map { it.id }.toSet())
        assertEquals("GitHub", loaded.credentials.first { it.id == "a" }.title)
        assertEquals("alice", loaded.credentials.first { it.id == "a" }.username)
    }

    @Test fun lock_synchronously_transitions_state_to_Locked() = runTest(testDispatcher) {
        val entities = listOf(buildV2Entity("a", "T"))
        fakeDao.rows.value = entities
        fakeDao.count.value = 1

        keyHolder.setKey(vaultKey)
        advanceUntilIdle()
        // Sanity precondition.
        assertTrue(store.state.value is SnapshotState.Loaded)

        // The contract: lock() on the test thread → state.value MUST be
        // Locked on the very next line, WITHOUT advanceUntilIdle.
        keyHolder.lock()

        assertEquals(SnapshotState.Locked, store.state.value)
    }

    @Test fun lock_clears_decryptor_subkey_cache() = runTest(testDispatcher) {
        fakeDao.rows.value = listOf(buildV2Entity("a", "T"))
        fakeDao.count.value = 1
        keyHolder.setKey(vaultKey)
        advanceUntilIdle()
        // Sanity - cache populated after first decrypt.
        assertEquals(vaultKey, readPrivate(decryptor, "memoisedFor"))

        keyHolder.lock()

        assertNull(readPrivate(decryptor, "memoisedFor"))
        assertNull(readPrivate(decryptor, "memoFieldKey"))
        assertNull(readPrivate(decryptor, "memoTitleKey"))
    }

    @Test fun migrating_branch_emits_Loading() = runTest(testDispatcher) {
        fakeDao.rows.value = listOf(buildV2Entity("a", "T"))
        fakeDao.count.value = 1

        keyHolder.setKey(vaultKey)
        migrationStatus.value = true
        advanceUntilIdle()

        assertEquals(SnapshotState.Loading, store.state.value)
    }

    @Test fun migrating_branch_transitions_to_Loaded_after_migration_completes() = runTest(testDispatcher) {
        fakeDao.rows.value = listOf(buildV2Entity("a", "T"))
        fakeDao.count.value = 1

        keyHolder.setKey(vaultKey)
        migrationStatus.value = true
        advanceUntilIdle()
        assertEquals(SnapshotState.Loading, store.state.value)

        // Migration completes. Snapshot resumes its upstream.
        migrationStatus.value = false
        advanceUntilIdle()

        assertTrue(store.state.value is SnapshotState.Loaded)
        assertEquals(1, (store.state.value as SnapshotState.Loaded).credentials.size)
    }

    @Test fun late_migration_flip_after_initial_decrypt_recovers_cleanly() = runTest(testDispatcher) {
        // Documents the known race called out in CredentialCipherMigrator's
        // KDoc: the snapshot's coordinator may resume on the unlock tick
        // BEFORE the migrator's own collector flips isMigrating=true. The
        // snapshot then completes one decrypt pass and settles on Loaded.
        // When the migrator finally resumes and flips the flag, the
        // snapshot must transition cleanly to Loading and then back to
        // Loaded once migration completes. No stuck-state, no leaked job.

        fakeDao.rows.value = listOf(buildV2Entity("a", "T1"))
        fakeDao.count.value = 1

        // Step 1 - unlock with migrating=false. Snapshot decrypts.
        keyHolder.setKey(vaultKey)
        advanceUntilIdle()
        assertTrue(
            "Initial decrypt with no migration in flight produces Loaded",
            store.state.value is SnapshotState.Loaded,
        )

        // Step 2 - migrator finally flips its flag to true (the race).
        // Snapshot's coordinator must cancel its upstream and transition
        // to Loading.
        migrationStatus.value = true
        advanceUntilIdle()
        assertEquals(
            "Late migrator-flip transitions snapshot to Loading mid-session",
            SnapshotState.Loading,
            store.state.value,
        )

        // Step 3 - migration completes. Snapshot resumes upstream and
        // settles on Loaded with the post-migration view of the data.
        migrationStatus.value = false
        advanceUntilIdle()
        val loaded = store.state.value as SnapshotState.Loaded
        assertEquals(listOf("a"), loaded.credentials.map { it.id })
    }

    @Test fun cap_exceeded_emits_Bypassed() = runTest(testDispatcher) {
        fakeDao.rows.value = listOf(buildV2Entity("a", "T"))
        // Force the cap-exceeded branch.
        fakeDao.count.value = VaultSnapshotStore.SNAPSHOT_CAP + 1

        keyHolder.setKey(vaultKey)
        advanceUntilIdle()

        assertEquals(SnapshotState.Bypassed, store.state.value)
    }

    @Test fun re_unlock_after_lock_re_emits_Loaded() = runTest(testDispatcher) {
        fakeDao.rows.value = listOf(buildV2Entity("a", "TA"))
        fakeDao.count.value = 1

        keyHolder.setKey(vaultKey)
        advanceUntilIdle()
        assertTrue(store.state.value is SnapshotState.Loaded)

        keyHolder.lock()
        assertEquals(SnapshotState.Locked, store.state.value)
        // Drain the false-emission so the coordinator's combine actually
        // observes the transition. Without this, StateFlow conflation can
        // skip the false value (lock() → setKey() on the same test thread)
        // and the coordinator stays in its prior branch. In production
        // there's always a UI-driven gap between lock and re-unlock; here
        // we advance virtual time once to mirror that gap.
        advanceUntilIdle()

        // Re-unlock with same key.
        keyHolder.setKey(vaultKey)
        advanceUntilIdle()
        val loaded = store.state.value as SnapshotState.Loaded
        assertEquals(listOf("a"), loaded.credentials.map { it.id })
        assertEquals("TA", loaded.credentials.single().title)
    }

    @Test fun corrupt_row_drops_silently_other_rows_remain() = runTest(testDispatcher) {
        val good = buildV2Entity("good", "Real")
        val corrupt = good.copy(
            id = "corrupt",
            usernameEncrypted = good.usernameEncrypted.copyOf().also {
                it[0] = (it[0].toInt() xor 0xFF).toByte()
            },
        )
        fakeDao.rows.value = listOf(corrupt, good)
        fakeDao.count.value = 2

        keyHolder.setKey(vaultKey)
        advanceUntilIdle()

        val loaded = store.state.value as SnapshotState.Loaded
        assertEquals(listOf("good"), loaded.credentials.map { it.id })
    }

    @Test fun dao_invalidation_re_emits_with_updated_rows() = runTest(testDispatcher) {
        fakeDao.rows.value = listOf(buildV2Entity("a", "T1"))
        fakeDao.count.value = 1
        keyHolder.setKey(vaultKey)
        advanceUntilIdle()
        assertEquals("T1", (store.state.value as SnapshotState.Loaded).credentials.single().title)

        // DAO emits a new row set (simulating a credential save).
        fakeDao.rows.value = listOf(
            buildV2Entity("a", "T1"),
            buildV2Entity("b", "T2"),
        )
        fakeDao.count.value = 2
        advanceUntilIdle()

        val loaded = store.state.value as SnapshotState.Loaded
        assertEquals(setOf("a", "b"), loaded.credentials.map { it.id }.toSet())
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun readPrivate(target: Any, fieldName: String): Any? {
        val field = target::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(target)
    }

    private fun buildV2Entity(
        id: String,
        title: String,
        username: String = "u@example.com",
        password: String = "secret",
        url: String = "https://x.example",
        notes: String = "",
    ): CredentialEntity {
        val fieldKey = crypto.deriveSubkey(vaultKey, HKDF_FIELD_KEY_INFO)
        val titleKey = crypto.deriveSubkey(vaultKey, HKDF_TITLE_KEY_INFO)
        val encTitle = crypto.encrypt(title.toByteArray(Charsets.UTF_8), titleKey, titleAad(id))
        val encUsername = crypto.encrypt(username.toByteArray(Charsets.UTF_8), fieldKey, fieldAad(id, "username"))
        val encPassword = crypto.encrypt(password.toByteArray(Charsets.UTF_8), fieldKey, fieldAad(id, "password"))
        val encNotes = crypto.encrypt(notes.toByteArray(Charsets.UTF_8), fieldKey, fieldAad(id, "notes"))
        val encUrl = crypto.encrypt(url.toByteArray(Charsets.UTF_8), fieldKey, fieldAad(id, "url"))
        return CredentialEntity(
            id = id,
            title = "",
            titleEncrypted = encTitle.ciphertext,
            ivTitle = encTitle.iv,
            usernameEncrypted = encUsername.ciphertext,
            passwordEncrypted = encPassword.ciphertext,
            url = "",
            urlEncrypted = encUrl.ciphertext,
            ivUrl = encUrl.iv,
            notesEncrypted = encNotes.ciphertext,
            totpSecretEncrypted = null,
            tags = emptyList(),
            customFields = emptyList(),
            createdAt = 1L,
            updatedAt = 2L,
            accessedAt = 3L,
            ivUsername = encUsername.iv,
            ivPassword = encPassword.iv,
            ivNotes = encNotes.iv,
            ivTotp = null,
            isFavorite = false,
            type = "LOGIN",
            deletedAt = null,
            otpType = "TOTP",
            totpAlgorithm = "SHA1",
            totpDigits = 6,
            totpPeriod = 30L,
            hotpCounter = null,
            cipherVersion = 2,
        )
    }

    /**
     * Minimal CredentialDao fake - only the methods VaultSnapshotStore reads
     * from are implemented (observeListRaw and observeCount). Everything else
     * throws so any accidental new dependency on a previously-unread DAO
     * method is loud.
     */
    private class FakeCredentialDao : CredentialDao {
        val rows = MutableStateFlow<List<CredentialEntity>>(emptyList())
        val count = MutableStateFlow(0)

        override fun observeListRaw(query: SupportSQLiteQuery): Flow<List<CredentialEntity>> = rows
        override fun observeCount(): Flow<Int> = count

        // Unused-by-store members - fail loud on unexpected use.
        override fun observeById(id: String): Flow<CredentialEntity?> = error("unused")
        override suspend fun getById(id: String): CredentialEntity? = error("unused")
        override suspend fun getByIdIncludingDeleted(id: String): CredentialEntity? = error("unused")
        override fun observeByIdIncludingDeleted(id: String): Flow<CredentialEntity?> = error("unused")
        override suspend fun upsert(entity: CredentialEntity) = error("unused")
        override suspend fun delete(entity: CredentialEntity) = error("unused")
        override suspend fun deleteById(id: String) = error("unused")
        override suspend fun softDeleteById(id: String, deletedAt: Long) = error("unused")
        override suspend fun restoreById(id: String, now: Long) = error("unused")
        override suspend fun emptyRecycleBin() = error("unused")
        override suspend fun purgeOlderThan(cutoff: Long): Int = error("unused")
        override fun observeCountForTag(tag: String): Flow<Int> = error("unused")
        override fun observeFavoriteCount(): Flow<Int> = error("unused")
        override fun observeRecycleBinCount(): Flow<Int> = error("unused")
        override suspend fun getAll(): List<CredentialEntity> = error("unused")
        override suspend fun getLegacyCipherBatch(limit: Int): List<CredentialEntity> = error("unused")
        override suspend fun countLegacyCipher(): Int = error("unused")
        override suspend fun getAllInRecycleBin(): List<CredentialEntity> = error("unused")
        override fun observeRecycleBin(): Flow<List<CredentialEntity>> = error("unused")
        override suspend fun upsertAll(entities: List<CredentialEntity>) = error("unused")
        override suspend fun deleteAll() = error("unused")
        override fun observeFavorites(): Flow<List<CredentialEntity>> = error("unused")
        override fun favoritesPagingSource(): PagingSource<Int, CredentialEntity> = error("unused")
        override fun observeFavoritesForAlphabet(): Flow<List<CredentialEntity>> = error("unused")
        override fun observeAllForAlphabet(tag: String): Flow<List<CredentialEntity>> = error("unused")
        override fun observeRotatingOtp(): Flow<List<CredentialEntity>> = error("unused")
        override fun observeHotpEntries(): Flow<List<CredentialEntity>> = error("unused")
        override suspend fun getHotpCounter(id: String): Long? = error("unused")
        override suspend fun setHotpCounter(id: String, counter: Long, now: Long) = error("unused")
        override suspend fun setFavorite(id: String, isFavorite: Boolean) = error("unused")
        override suspend fun touchAccessedAt(id: String, now: Long) = error("unused")
        override fun pagingSourceRaw(query: SupportSQLiteQuery): PagingSource<Int, CredentialEntity> = error("unused")
        override fun favoritesPagingSourceRaw(query: SupportSQLiteQuery): PagingSource<Int, CredentialEntity> = error("unused")
    }
}
