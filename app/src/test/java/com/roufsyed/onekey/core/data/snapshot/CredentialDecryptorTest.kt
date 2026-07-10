package com.roufsyed.onekey.core.data.snapshot

import com.roufsyed.onekey.core.data.local.entity.CredentialEntity
import com.roufsyed.onekey.core.security.CryptoManager
import com.roufsyed.onekey.core.security.HKDF_FIELD_KEY_INFO
import com.roufsyed.onekey.core.security.HKDF_TITLE_KEY_INFO
import com.roufsyed.onekey.core.security.VaultKeyHolder
import com.roufsyed.onekey.core.security.VaultLockedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Behavioural locks for [CredentialDecryptor]. Pure-JVM - no Robolectric,
 * no Room - uses a real [CryptoManager] (JCE/JVM) and a real
 * [VaultKeyHolder] to build synthetic v2 entities via the same encrypt
 * path the production write code uses. This guarantees AAD and HKDF
 * info-label byte-equivalence between read and write paths.
 */
class CredentialDecryptorTest {

    private val crypto = CryptoManager()
    private lateinit var keyHolder: VaultKeyHolder
    private lateinit var vaultKey: SecretKey
    private lateinit var decryptor: CredentialDecryptor

    @Before fun setup() {
        keyHolder = VaultKeyHolder()
        vaultKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        keyHolder.setKey(vaultKey)
        decryptor = CredentialDecryptor(crypto, keyHolder)
    }

    // ── decryptAllLeanWithLockCheck ──────────────────────────────────────

    @Test fun lean_decrypt_emits_empty_for_empty_input() = runBlocking {
        assertEquals(emptyList<SnapshotCredential>(), decryptor.decryptAllLeanWithLockCheck(emptyList()))
    }

    @Test fun lean_decrypt_returns_lean_projection_for_v2_rows() = runBlocking {
        val entity = buildV2Entity(
            id = "alice",
            title = "GitHub",
            username = "alice@example.com",
            password = "supersecret",
            url = "https://github.com",
            notes = "personal account",
        )
        val out = decryptor.decryptAllLeanWithLockCheck(listOf(entity))
        assertEquals(1, out.size)
        val lean = out.single()
        assertEquals("alice", lean.id)
        assertEquals("GitHub", lean.title)
        assertEquals("alice@example.com", lean.username)
        assertEquals("https://github.com", lean.url)
        assertFalse(lean.hasOtp)
    }

    @Test fun lean_decrypt_hasOtp_true_when_totpSecret_present() = runBlocking {
        val entity = buildV2Entity(
            id = "withotp",
            title = "GitHub",
            username = "u",
            password = "p",
            url = "https://github.com",
            notes = "",
            totpSecret = "JBSWY3DPEHPK3PXP",
        )
        val out = decryptor.decryptAllLeanWithLockCheck(listOf(entity))
        assertTrue(out.single().hasOtp)
    }

    @Test fun lean_decrypt_throws_VaultLockedException_when_locked_before_call() {
        keyHolder.lock()
        val ex = assertThrows(VaultLockedException::class.java) {
            runBlocking { decryptor.decryptAllLeanWithLockCheck(listOf(simpleEntity())) }
        }
        assertNotNull(ex)
    }

    @Test fun lean_decrypt_aborts_mid_loop_when_lock_fires() {
        // Build many entities so the loop runs long enough to race a
        // concurrent lock(). Each yield()+check between rows is the
        // cancellation point.
        val entities = (1..200).map { buildV2Entity(id = "row-$it", title = "T$it") }

        val ex = assertThrows(VaultLockedException::class.java) {
            runBlocking(Dispatchers.Default) {
                val locked = CompletableDeferred<Unit>()
                // Background coroutine - wait briefly, then lock the vault.
                // A small real delay is fine here because the test is plain
                // JVM (no TestDispatcher). 5ms is enough for the decrypt
                // loop to process several rows.
                val locker = launch(Dispatchers.Default) {
                    delay(5)
                    keyHolder.lock()
                    locked.complete(Unit)
                }
                try {
                    decryptor.decryptAllLeanWithLockCheck(entities)
                } finally {
                    withTimeout(1_000) { locked.await() }
                    locker.join()
                }
            }
        }
        assertNotNull(ex)
    }

    @Test fun lean_decrypt_drops_corrupt_rows_silently() = runBlocking {
        val good = buildV2Entity(id = "good", title = "Real")
        val corrupt = good.copy(
            id = "corrupt",
            // Flip a ciphertext byte - AES-GCM tag verification will fail.
            usernameEncrypted = good.usernameEncrypted.copyOf().also {
                it[0] = (it[0].toInt() xor 0xFF).toByte()
            },
        )
        val out = decryptor.decryptAllLeanWithLockCheck(listOf(corrupt, good))
        assertEquals(listOf("good"), out.map { it.id })
    }

    // ── decrypt / decryptOrNull single-row ───────────────────────────────

    @Test fun decrypt_returns_full_Credential_with_password_and_notes() {
        val entity = buildV2Entity(
            id = "x",
            title = "T",
            username = "u",
            password = "p123",
            url = "https://x",
            notes = "secret notes here",
        )
        val full = decryptor.decrypt(entity)
        assertEquals("T", full.title)
        assertEquals("p123", full.password)
        assertEquals("secret notes here", full.notes)
    }

    @Test fun decrypt_throws_VaultLockedException_when_locked() {
        keyHolder.lock()
        assertThrows(VaultLockedException::class.java) {
            decryptor.decrypt(simpleEntity())
        }
    }

    @Test fun decryptOrNull_returns_null_when_locked() {
        keyHolder.lock()
        assertNull(decryptor.decryptOrNull(simpleEntity()))
    }

    @Test fun decryptOrNull_returns_null_for_corrupt_row() {
        val good = buildV2Entity(id = "ok", title = "T")
        val corrupt = good.copy(
            usernameEncrypted = good.usernameEncrypted.copyOf().also {
                it[0] = (it[0].toInt() xor 0xFF).toByte()
            },
        )
        assertNull(decryptor.decryptOrNull(corrupt))
    }

    @Test fun decryptOrNull_returns_credential_for_good_row() {
        val entity = buildV2Entity(id = "ok", title = "T")
        val out = decryptor.decryptOrNull(entity)
        assertNotNull(out)
        assertEquals("T", out!!.title)
    }

    // ── Subkey memoisation ───────────────────────────────────────────────

    @Test fun memoisedFor_field_populated_after_first_lean_call() = runBlocking {
        val entity = buildV2Entity(id = "a", title = "T")
        decryptor.decryptAllLeanWithLockCheck(listOf(entity))
        assertSame(
            "Subkey cache must memoise the vaultKey identity",
            vaultKey,
            readPrivate(decryptor, "memoisedFor"),
        )
        assertNotNull(readPrivate(decryptor, "memoFieldKey"))
        assertNotNull(readPrivate(decryptor, "memoTitleKey"))
    }

    @Test fun memoisation_survives_a_second_call_with_same_key() = runBlocking {
        val entity = buildV2Entity(id = "a", title = "T")
        decryptor.decryptAllLeanWithLockCheck(listOf(entity))
        val firstFieldKey = readPrivate(decryptor, "memoFieldKey")
        val firstTitleKey = readPrivate(decryptor, "memoTitleKey")

        decryptor.decryptAllLeanWithLockCheck(listOf(entity))

        // Same vaultKey -> cache hit -> same SecretKey instances retained.
        assertSame(
            "Subkey cache hits on second call with same vaultKey identity",
            firstFieldKey,
            readPrivate(decryptor, "memoFieldKey"),
        )
        assertSame(firstTitleKey, readPrivate(decryptor, "memoTitleKey"))
    }

    @Test fun memoisation_rederives_when_vaultKey_identity_changes() = runBlocking {
        val entityA = buildV2Entity(id = "a", title = "TA")
        decryptor.decryptAllLeanWithLockCheck(listOf(entityA))
        val firstFieldKey = readPrivate(decryptor, "memoFieldKey")
        val firstTitleKey = readPrivate(decryptor, "memoTitleKey")

        // Simulate vault reset + re-setup with a different key. The cache
        // must NOT reuse subkeys derived from the prior key.
        keyHolder.lock()
        val newVaultKey = SecretKeySpec(ByteArray(32) { (it + 100).toByte() }, "AES")
        keyHolder.setKey(newVaultKey)
        vaultKey = newVaultKey

        val entityB = buildV2Entity(id = "b", title = "TB")
        decryptor.decryptAllLeanWithLockCheck(listOf(entityB))

        val secondFieldKey = readPrivate(decryptor, "memoFieldKey")
        val secondTitleKey = readPrivate(decryptor, "memoTitleKey")
        // New SecretKey instance -> different memoised subkey references.
        assertFalse("New vaultKey identity must invalidate the subkey cache",
            firstFieldKey === secondFieldKey)
        assertFalse(firstTitleKey === secondTitleKey)
        assertSame(newVaultKey, readPrivate(decryptor, "memoisedFor"))
    }

    @Test fun onLock_clears_the_subkey_cache() = runBlocking {
        val entity = buildV2Entity(id = "a", title = "T")
        decryptor.decryptAllLeanWithLockCheck(listOf(entity))
        assertNotNull(readPrivate(decryptor, "memoFieldKey"))

        decryptor.onLock()

        assertNull(readPrivate(decryptor, "memoisedFor"))
        assertNull(readPrivate(decryptor, "memoFieldKey"))
        assertNull(readPrivate(decryptor, "memoTitleKey"))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun simpleEntity(): CredentialEntity = buildV2Entity(id = "x", title = "T")

    /**
     * Builds a synthetic v2 credential entity using the production
     * [CryptoManager.encrypt] path with the matching HKDF subkeys + AADs.
     * Any drift between this helper and the production write path would
     * surface as a GCM-tag failure during the corresponding decrypt - so
     * these tests double as a round-trip check on the AAD shape.
     */
    private fun buildV2Entity(
        id: String,
        title: String,
        username: String = "u@example.com",
        password: String = "secret",
        url: String = "https://x.example",
        notes: String = "",
        totpSecret: String? = null,
    ): CredentialEntity {
        val fieldKey = crypto.deriveSubkey(vaultKey, HKDF_FIELD_KEY_INFO)
        val titleKey = crypto.deriveSubkey(vaultKey, HKDF_TITLE_KEY_INFO)
        val encTitle = crypto.encrypt(title.toByteArray(Charsets.UTF_8), titleKey, titleAad(id))
        val encUsername = crypto.encrypt(username.toByteArray(Charsets.UTF_8), fieldKey, fieldAad(id, "username"))
        val encPassword = crypto.encrypt(password.toByteArray(Charsets.UTF_8), fieldKey, fieldAad(id, "password"))
        val encNotes = crypto.encrypt(notes.toByteArray(Charsets.UTF_8), fieldKey, fieldAad(id, "notes"))
        val encUrl = crypto.encrypt(url.toByteArray(Charsets.UTF_8), fieldKey, fieldAad(id, "url"))
        val encTotp = totpSecret?.let {
            crypto.encrypt(it.toByteArray(Charsets.UTF_8), fieldKey, fieldAad(id, "totp"))
        }
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
            totpSecretEncrypted = encTotp?.ciphertext,
            tags = emptyList(),
            customFields = emptyList(),
            createdAt = 1L,
            updatedAt = 2L,
            accessedAt = 3L,
            ivUsername = encUsername.iv,
            ivPassword = encPassword.iv,
            ivNotes = encNotes.iv,
            ivTotp = encTotp?.iv,
            isFavorite = false,
            type = "LOGIN",
            deletedAt = null,
            otpType = if (totpSecret != null) "TOTP" else "TOTP",
            totpAlgorithm = "SHA1",
            totpDigits = 6,
            totpPeriod = 30L,
            hotpCounter = null,
            cipherVersion = 2,
        )
    }

    private fun readPrivate(target: Any, fieldName: String): Any? {
        val field = target::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(target)
    }
}
