package com.roufsyed.onekey.core.data.repository

import com.roufsyed.onekey.core.data.local.dao.PasskeyDao
import com.roufsyed.onekey.core.data.local.entity.PasskeyEntity
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Passkey
import com.roufsyed.onekey.core.domain.model.PasskeyOrigin
import com.roufsyed.onekey.core.security.CryptoManager
import com.roufsyed.onekey.core.security.VaultKeyHolder
import com.roufsyed.onekey.core.security.VaultLockedException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-JVM behavioural locks for [PasskeyRepositoryImpl]: real [CryptoManager]
 * and [VaultKeyHolder] (so AAD and HKDF labels are exercised for real), fake DAO.
 */
class PasskeyRepositoryImplTest {

    private val crypto = CryptoManager()
    private lateinit var keyHolder: VaultKeyHolder
    private lateinit var dao: FakePasskeyDao
    private lateinit var repo: PasskeyRepositoryImpl

    @Before fun setup() {
        keyHolder = VaultKeyHolder()
        keyHolder.setKey(SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES"))
        dao = FakePasskeyDao()
        repo = PasskeyRepositoryImpl(dao, crypto, keyHolder)
    }

    private fun passkey(
        id: String = "",
        credentialId: String = "cred-1",
        rpId: String = "github.com",
        userName: String = "rouf",
        origin: PasskeyOrigin = PasskeyOrigin.Browser("https://github.com"),
        prf: ByteArray? = null,
        backedUpAt: Long? = null,
    ) = Passkey(
        id = id,
        credentialId = credentialId,
        rpId = rpId,
        rpName = "GitHub",
        userName = userName,
        userDisplayName = "Rouf Syed",
        userHandle = ByteArray(16) { 7 },
        webauthnCredentialId = ByteArray(32) { 9 },
        privateKeyPkcs8 = ByteArray(138) { (it * 3).toByte() },
        prfSecret = prf,
        origin = origin,
        createdAt = 1_700_000_000_000L,
        lastUsedAt = null,
        backedUpAt = backedUpAt,
    )

    // ── save / getById ─────────────────────────────────────────────────────

    @Test fun save_assigns_id_and_full_round_trip_restores_every_field() = runBlocking {
        val saved = (repo.save(passkey(prf = ByteArray(32) { 1 }, origin = PasskeyOrigin.App("com.example", "ab" .repeat(32)))) as AppResult.Success).data
        assertTrue(saved.id.isNotBlank())

        val loaded = (repo.getById(saved.id) as AppResult.Success).data
        assertEquals("github.com", loaded.rpId)
        assertEquals("GitHub", loaded.rpName)
        assertEquals("rouf", loaded.userName)
        assertEquals("Rouf Syed", loaded.userDisplayName)
        assertArrayEquals(ByteArray(16) { 7 }, loaded.userHandle)
        assertArrayEquals(ByteArray(32) { 9 }, loaded.webauthnCredentialId)
        assertArrayEquals(ByteArray(138) { (it * 3).toByte() }, loaded.privateKeyPkcs8)
        assertArrayEquals(ByteArray(32) { 1 }, loaded.prfSecret)
        assertEquals(PasskeyOrigin.App("com.example", "ab".repeat(32)), loaded.origin)
        assertEquals(1_700_000_000_000L, loaded.createdAt)
        assertNull(loaded.lastUsedAt)
        assertFalse(loaded.isBackedUp)
    }

    @Test fun stored_row_is_ciphertext_not_plaintext() = runBlocking {
        val saved = (repo.save(passkey()) as AppResult.Success).data
        val row = dao.rows.getValue(saved.id)
        val secret = ByteArray(138) { (it * 3).toByte() }
        assertFalse(row.privateKeyEncrypted.contentEquals(secret))
        assertFalse(String(row.rpIdEncrypted, Charsets.ISO_8859_1).contains("github"))
        assertEquals(2, row.cipherVersion)
        assertNull(row.prfSecretEncrypted)
    }

    @Test fun aad_binds_ciphertext_to_the_row_id_so_swapped_rows_fail() = runBlocking {
        val a = (repo.save(passkey(credentialId = "c-a")) as AppResult.Success).data
        val b = (repo.save(passkey(credentialId = "c-b", userName = "other")) as AppResult.Success).data
        // Move b's ciphertext under a's id: every field decrypt must fail the GCM tag.
        val rowB = dao.rows.getValue(b.id)
        dao.rows[a.id] = rowB.copy(id = a.id)
        val result = repo.getById(a.id)
        assertTrue(result is AppResult.Error)
    }

    @Test fun aad_namespace_is_pk_prefixed() {
        assertEquals("1k:v1|pk:row-7|private_key", String(PasskeyRepositoryImpl.passkeyAad("row-7", "private_key"), Charsets.UTF_8))
    }

    @Test fun locked_vault_yields_error_not_throw() = runBlocking {
        keyHolder.lock()
        val saved = repo.save(passkey())
        assertTrue(saved is AppResult.Error)
        assertTrue((saved as AppResult.Error).exception is VaultLockedException)
        val listed = repo.listActiveForRpId("github.com")
        assertTrue(listed is AppResult.Error)
    }

    // ── lean listing ───────────────────────────────────────────────────────

    @Test fun listActiveForRpId_filters_by_rp_and_skips_recycle_bin_credentials() = runBlocking {
        repo.save(passkey(credentialId = "active", rpId = "github.com"))
        repo.save(passkey(credentialId = "active", rpId = "google.com"))
        repo.save(passkey(credentialId = "binned", rpId = "github.com"))
        dao.deletedCredentialIds += "binned"

        val list = (repo.listActiveForRpId("github.com") as AppResult.Success).data
        assertEquals(1, list.size)
        assertEquals("active", list.single().credentialId)
        assertEquals("github.com", list.single().rpId)
        assertEquals(PasskeyOrigin.Browser("https://github.com"), list.single().origin)
    }

    @Test fun corrupt_rows_are_skipped_in_listing_and_counted_in_export() = runBlocking {
        val good = (repo.save(passkey()) as AppResult.Success).data
        val bad = (repo.save(passkey(userName = "bad")) as AppResult.Success).data
        val row = dao.rows.getValue(bad.id)
        dao.rows[bad.id] = row.copy(ivRpId = ByteArray(12))  // breaks GCM tag on rp_id

        val listed = (repo.listActiveForRpId("github.com") as AppResult.Success).data
        assertEquals(listOf(good.id), listed.map { it.id })

        val batch = (repo.allForExport() as AppResult.Success).data
        assertEquals(listOf(good.id), batch.passkeys.map { it.id })
        assertEquals(1, batch.skipped)
    }

    @Test fun observeForCredential_is_empty_while_locked_and_lean_when_unlocked() = runBlocking {
        val saved = (repo.save(passkey(credentialId = "cred-9")) as AppResult.Success).data
        val unlocked = repo.observeForCredential("cred-9").first()
        assertEquals(listOf(saved.id), unlocked.map { it.id })

        keyHolder.lock()
        assertTrue(repo.observeForCredential("cred-9").first().isEmpty())
    }

    // ── mutations ──────────────────────────────────────────────────────────

    @Test fun delete_reassign_touch_and_markBackedUp_hit_the_dao() = runBlocking {
        val a = (repo.save(passkey(credentialId = "c-1")) as AppResult.Success).data
        val b = (repo.save(passkey(credentialId = "c-2")) as AppResult.Success).data

        repo.touchLastUsed(a.id, 42L)
        assertEquals(42L, dao.rows.getValue(a.id).lastUsedAt)

        repo.markBackedUp(listOf(a.id, b.id), 99L)
        assertEquals(99L, dao.rows.getValue(a.id).backedUpAt)
        repo.markBackedUp(listOf(a.id), 123L)   // first backup time is kept
        assertEquals(99L, dao.rows.getValue(a.id).backedUpAt)

        repo.reassignCredential("c-2", "c-1")
        assertEquals("c-1", dao.rows.getValue(b.id).credentialId)

        repo.deleteForCredential("c-1")
        assertTrue(dao.rows.isEmpty())

        repo.save(passkey(id = "keep"))
        repo.deleteAll()
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun save_with_explicit_id_replaces_in_place() = runBlocking {
        repo.save(passkey(id = "fixed", userName = "one"))
        repo.save(passkey(id = "fixed", userName = "two"))
        assertEquals(1, dao.rows.size)
        assertEquals("two", (repo.getById("fixed") as AppResult.Success).data.userName)
    }

    @Test fun origin_encoding_round_trips_both_shapes() {
        val browser = PasskeyOrigin.Browser("https://accounts.google.com")
        val app = PasskeyOrigin.App("com.example.app", "00".repeat(32))
        assertEquals(browser, PasskeyRepositoryImpl.decodeOrigin(PasskeyRepositoryImpl.encodeOrigin(browser)))
        assertEquals(app, PasskeyRepositoryImpl.decodeOrigin(PasskeyRepositoryImpl.encodeOrigin(app)))
        assertNotEquals(PasskeyRepositoryImpl.encodeOrigin(browser), PasskeyRepositoryImpl.encodeOrigin(app))
    }

    // ── fake DAO ───────────────────────────────────────────────────────────

    class FakePasskeyDao : PasskeyDao {
        val rows = LinkedHashMap<String, PasskeyEntity>()
        val deletedCredentialIds = mutableSetOf<String>()
        private val changes = MutableStateFlow(0)

        private fun bump() { changes.value++ }

        override suspend fun upsert(entity: PasskeyEntity) { rows[entity.id] = entity; bump() }
        override suspend fun getById(id: String): PasskeyEntity? = rows[id]
        override suspend fun getAll(): List<PasskeyEntity> = rows.values.sortedBy { it.createdAt }
        override suspend fun getAllActive(): List<PasskeyEntity> =
            rows.values.filter { it.credentialId !in deletedCredentialIds }.sortedBy { it.createdAt }
        override suspend fun getForCredential(credentialId: String): List<PasskeyEntity> =
            rows.values.filter { it.credentialId == credentialId }
        override fun observeForCredential(credentialId: String): Flow<List<PasskeyEntity>> =
            changes.map { rows.values.filter { it.credentialId == credentialId } }
        override fun observeCountForCredential(credentialId: String): Flow<Int> =
            changes.map { rows.values.count { it.credentialId == credentialId } }
        override suspend fun deleteById(id: String) { rows.remove(id); bump() }
        override suspend fun deleteForCredential(credentialId: String) {
            rows.values.filter { it.credentialId == credentialId }.forEach { rows.remove(it.id) }; bump()
        }
        override suspend fun reassignCredential(fromCredentialId: String, toCredentialId: String) {
            rows.replaceAll { _, e -> if (e.credentialId == fromCredentialId) e.copy(credentialId = toCredentialId) else e }; bump()
        }
        override suspend fun deleteAll() { rows.clear(); bump() }
        override suspend fun touchLastUsed(id: String, now: Long) {
            rows[id]?.let { rows[id] = it.copy(lastUsedAt = now) }; bump()
        }
        override suspend fun markBackedUp(ids: List<String>, now: Long) {
            ids.forEach { id -> rows[id]?.let { if (it.backedUpAt == null) rows[id] = it.copy(backedUpAt = now) } }; bump()
        }
    }
}
