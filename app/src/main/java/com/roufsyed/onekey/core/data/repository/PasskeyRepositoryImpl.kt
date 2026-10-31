package com.roufsyed.onekey.core.data.repository

import com.roufsyed.onekey.core.data.local.dao.PasskeyDao
import com.roufsyed.onekey.core.data.local.entity.PasskeyEntity
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Passkey
import com.roufsyed.onekey.core.domain.model.PasskeyOrigin
import com.roufsyed.onekey.core.domain.model.PasskeySummary
import com.roufsyed.onekey.core.domain.model.runCatchingResult
import com.roufsyed.onekey.core.domain.repository.PasskeyRepository
import com.roufsyed.onekey.core.security.CryptoManager
import com.roufsyed.onekey.core.security.EncryptedData
import com.roufsyed.onekey.core.security.HKDF_FIELD_KEY_INFO
import com.roufsyed.onekey.core.security.VaultKeyHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts and decrypts [PasskeyEntity] rows under the HKDF field subkey with
 * the `pk:` AAD namespace (see [passkeyAad]). Same shape as
 * [CredentialHistoryRepositoryImpl]: subkeys derived per call from the live
 * vault key, `VaultLockedException` surfaced as `AppResult.Error` through
 * [runCatchingResult], observers gated on the unlocked flag and shifted to
 * Default.
 *
 * No decrypted cache is kept anywhere. The table is small, the lean decrypt
 * per request is cheap, and [VaultKeyHolder] has no free lock-hook slot for a
 * cache to be cleared from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PasskeyRepositoryImpl @Inject constructor(
    private val dao: PasskeyDao,
    private val crypto: CryptoManager,
    private val keyHolder: VaultKeyHolder,
) : PasskeyRepository {

    override suspend fun save(passkey: Passkey): AppResult<Passkey> = runCatchingResult {
        val stored = if (passkey.id.isBlank()) passkey.withId(UUID.randomUUID().toString()) else passkey
        dao.upsert(stored.toEntity(fieldKey()))
        stored
    }

    override suspend fun getById(id: String): AppResult<Passkey> = runCatchingResult {
        val entity = dao.getById(id) ?: throw NoSuchElementException("Passkey $id not found")
        entity.toDomain(fieldKey())
    }

    override suspend fun listActiveForRpId(rpId: String): AppResult<List<PasskeySummary>> = runCatchingResult {
        val key = fieldKey()
        dao.getAllActive().mapNotNull { entity ->
            // A row that fails to decrypt drops out, like corrupt credential rows do in the snapshot.
            runCatching { entity.toSummary(key) }.getOrNull()
        }.filter { it.rpId == rpId }
    }

    override fun observeForCredential(credentialId: String): Flow<List<PasskeySummary>> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(emptyList())
            else dao.observeForCredential(credentialId).map { list ->
                val key = fieldKey()
                list.mapNotNull { entity -> runCatching { entity.toSummary(key) }.getOrNull() }
            }
        }.flowOn(Dispatchers.Default)

    override suspend fun allForExport(): AppResult<PasskeyRepository.ExportBatch> = runCatchingResult {
        val key = fieldKey()
        var skipped = 0
        val out = ArrayList<Passkey>()
        for (entity in dao.getAll()) {
            val decrypted = runCatching { entity.toDomain(key) }.getOrNull()
            if (decrypted == null) skipped++ else out += decrypted
        }
        PasskeyRepository.ExportBatch(out, skipped)
    }

    override suspend fun deleteById(id: String): AppResult<Unit> = runCatchingResult { dao.deleteById(id) }

    override suspend fun deleteForCredential(credentialId: String): AppResult<Unit> =
        runCatchingResult { dao.deleteForCredential(credentialId) }

    override suspend fun reassignCredential(fromCredentialId: String, toCredentialId: String): AppResult<Unit> =
        runCatchingResult { dao.reassignCredential(fromCredentialId, toCredentialId) }

    override suspend fun deleteAll(): AppResult<Unit> = runCatchingResult { dao.deleteAll() }

    override suspend fun touchLastUsed(id: String, now: Long): AppResult<Unit> =
        runCatchingResult { dao.touchLastUsed(id, now) }

    override suspend fun markBackedUp(ids: List<String>, now: Long): AppResult<Unit> = runCatchingResult {
        if (ids.isNotEmpty()) dao.markBackedUp(ids, now)
    }

    // ── crypto plumbing ──────────────────────────────────────────────────────

    /** Throws VaultLockedException when locked; runCatchingResult turns that into AppResult.Error. */
    private fun fieldKey(): SecretKey = crypto.deriveSubkey(keyHolder.requireKey(), HKDF_FIELD_KEY_INFO)

    private fun Passkey.toEntity(fieldKey: SecretKey): PasskeyEntity {
        fun enc(bytes: ByteArray, field: String): EncryptedData = crypto.encrypt(bytes, fieldKey, passkeyAad(id, field))
        fun encText(text: String, field: String): EncryptedData = enc(text.toByteArray(Charsets.UTF_8), field)

        val rp = encText(rpId, FIELD_RP_ID)
        val rpN = encText(rpName, FIELD_RP_NAME)
        val un = encText(userName, FIELD_USER_NAME)
        val udn = encText(userDisplayName, FIELD_USER_DISPLAY_NAME)
        val uh = enc(userHandle, FIELD_USER_HANDLE)
        val wid = enc(webauthnCredentialId, FIELD_WEBAUTHN_ID)
        val pk = enc(privateKeyPkcs8, FIELD_PRIVATE_KEY)
        val prf = prfSecret?.let { enc(it, FIELD_PRF_SECRET) }
        val ob = encText(encodeOrigin(origin), FIELD_ORIGIN)

        return PasskeyEntity(
            id = id,
            credentialId = credentialId,
            rpIdEncrypted = rp.ciphertext, ivRpId = rp.iv,
            rpNameEncrypted = rpN.ciphertext, ivRpName = rpN.iv,
            userNameEncrypted = un.ciphertext, ivUserName = un.iv,
            userDisplayNameEncrypted = udn.ciphertext, ivUserDisplayName = udn.iv,
            userHandleEncrypted = uh.ciphertext, ivUserHandle = uh.iv,
            webauthnIdEncrypted = wid.ciphertext, ivWebauthnId = wid.iv,
            privateKeyEncrypted = pk.ciphertext, ivPrivateKey = pk.iv,
            prfSecretEncrypted = prf?.ciphertext, ivPrfSecret = prf?.iv,
            originBindingEncrypted = ob.ciphertext, ivOriginBinding = ob.iv,
            createdAt = createdAt,
            lastUsedAt = lastUsedAt,
            backedUpAt = backedUpAt,
            cipherVersion = CIPHER_VERSION,
        )
    }

    private fun PasskeyEntity.dec(ct: ByteArray, iv: ByteArray, field: String, fieldKey: SecretKey): ByteArray {
        check(cipherVersion == CIPHER_VERSION) { "Unknown passkey cipher version $cipherVersion" }
        return crypto.decrypt(EncryptedData(ct, iv), fieldKey, passkeyAad(id, field))
    }

    private fun PasskeyEntity.decText(ct: ByteArray, iv: ByteArray, field: String, fieldKey: SecretKey): String =
        dec(ct, iv, field, fieldKey).toString(Charsets.UTF_8)

    /** Lean decrypt: no private key, no user handle, no PRF seed. */
    private fun PasskeyEntity.toSummary(fieldKey: SecretKey): PasskeySummary = PasskeySummary(
        id = id,
        credentialId = credentialId,
        rpId = decText(rpIdEncrypted, ivRpId, FIELD_RP_ID, fieldKey),
        rpName = decText(rpNameEncrypted, ivRpName, FIELD_RP_NAME, fieldKey),
        userName = decText(userNameEncrypted, ivUserName, FIELD_USER_NAME, fieldKey),
        userDisplayName = decText(userDisplayNameEncrypted, ivUserDisplayName, FIELD_USER_DISPLAY_NAME, fieldKey),
        webauthnCredentialId = dec(webauthnIdEncrypted, ivWebauthnId, FIELD_WEBAUTHN_ID, fieldKey),
        origin = decodeOrigin(decText(originBindingEncrypted, ivOriginBinding, FIELD_ORIGIN, fieldKey)),
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        backedUpAt = backedUpAt,
    )

    private fun PasskeyEntity.toDomain(fieldKey: SecretKey): Passkey {
        val summary = toSummary(fieldKey)
        return Passkey(
            id = summary.id,
            credentialId = summary.credentialId,
            rpId = summary.rpId,
            rpName = summary.rpName,
            userName = summary.userName,
            userDisplayName = summary.userDisplayName,
            userHandle = dec(userHandleEncrypted, ivUserHandle, FIELD_USER_HANDLE, fieldKey),
            webauthnCredentialId = summary.webauthnCredentialId,
            privateKeyPkcs8 = dec(privateKeyEncrypted, ivPrivateKey, FIELD_PRIVATE_KEY, fieldKey),
            prfSecret = if (prfSecretEncrypted != null && ivPrfSecret != null) {
                dec(prfSecretEncrypted, ivPrfSecret, FIELD_PRF_SECRET, fieldKey)
            } else null,
            origin = summary.origin,
            createdAt = createdAt,
            lastUsedAt = lastUsedAt,
            backedUpAt = backedUpAt,
        )
    }

    private fun Passkey.withId(newId: String): Passkey = Passkey(
        id = newId,
        credentialId = credentialId,
        rpId = rpId,
        rpName = rpName,
        userName = userName,
        userDisplayName = userDisplayName,
        userHandle = userHandle,
        webauthnCredentialId = webauthnCredentialId,
        privateKeyPkcs8 = privateKeyPkcs8,
        prfSecret = prfSecret,
        origin = origin,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        backedUpAt = backedUpAt,
    )

    internal companion object {
        const val CIPHER_VERSION = 2
        const val FIELD_RP_ID = "rp_id"
        const val FIELD_RP_NAME = "rp_name"
        const val FIELD_USER_NAME = "user_name"
        const val FIELD_USER_DISPLAY_NAME = "user_display_name"
        const val FIELD_USER_HANDLE = "user_handle"
        const val FIELD_WEBAUTHN_ID = "webauthn_id"
        const val FIELD_PRIVATE_KEY = "private_key"
        const val FIELD_PRF_SECRET = "prf_secret"
        const val FIELD_ORIGIN = "origin"

        /**
         * AAD for every passkey field: `"1k:v1|pk:<row id>|<field>"`. The `pk:`
         * namespace follows the history table's `h:` so a passkey row and a
         * credential row with the same UUID could never have ciphertext swapped
         * between them without failing the GCM tag.
         */
        fun passkeyAad(passkeyId: String, field: String): ByteArray =
            "1k:v1|pk:$passkeyId|$field".toByteArray(Charsets.UTF_8)

        private const val ORIGIN_BROWSER = "b"
        private const val ORIGIN_APP = "a"
        private const val SEP = '|'

        /** `b|<origin>` or `a|<package>|<certSha256>`. Neither origins nor package names can contain `|`. */
        fun encodeOrigin(origin: PasskeyOrigin): String = when (origin) {
            is PasskeyOrigin.Browser -> "$ORIGIN_BROWSER$SEP${origin.origin}"
            is PasskeyOrigin.App -> "$ORIGIN_APP$SEP${origin.packageName}$SEP${origin.certSha256}"
        }

        fun decodeOrigin(encoded: String): PasskeyOrigin {
            val parts = encoded.split(SEP)
            return when {
                parts.size == 2 && parts[0] == ORIGIN_BROWSER -> PasskeyOrigin.Browser(parts[1])
                parts.size == 3 && parts[0] == ORIGIN_APP -> PasskeyOrigin.App(parts[1], parts[2])
                else -> throw IllegalStateException("Malformed passkey origin binding")
            }
        }
    }
}
