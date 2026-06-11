package com.onekey.core.data.repository

import android.content.SharedPreferences
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.onekey.core.di.ApplicationScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.runCatchingResult
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.security.CryptoManager
import com.onekey.core.security.EncryptedData
import com.onekey.core.security.KDF_LEGACY_ARGON2ID
import com.onekey.core.security.KDF_LEGACY_PBKDF2
import com.onekey.core.security.KDF_V3_CUSTOM
import com.onekey.core.security.KDF_V3_HARDENED
import com.onekey.core.security.KDF_V3_MAXIMUM
import com.onekey.core.security.KDF_V3_STANDARD
import com.onekey.core.security.KDF_V3_STANDARD_PLUS
import com.onekey.core.security.KEYSTORE_ALIAS_V1
import com.onekey.core.security.KEYSTORE_ALIAS_V2
import com.onekey.core.security.KdfMigrator
import com.onekey.core.security.KdfParams
import com.onekey.core.security.KdfPreset
import com.onekey.core.security.SP_SECRET_KEY_ENABLED
import com.onekey.core.security.SP_SECRET_KEY_WRAPPED
import com.onekey.core.security.SP_SK_ACTIVE_ALIAS_VERSION
import com.onekey.core.security.SecretKeyHolder
import com.onekey.core.security.SecretKeyKeystoreWrapper
import com.onekey.core.security.VaultKeyHolder
import com.onekey.core.security.VaultVersionTracker
import com.onekey.feature.sync.domain.SyncEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.crypto.BadPaddingException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

// ── EncryptedSharedPreferences key names (must match legacy DataStore names for migration) ──

private const val SP_SETUP_COMPLETE    = "setup_complete"
private const val SP_SALT              = "vault_salt"
private const val SP_WRAPPED_KEY_CT    = "wrapped_key_ct"
private const val SP_WRAPPED_KEY_IV    = "wrapped_key_iv"
private const val SP_PASSWORD_VERIFIER = "password_verifier"
private const val SP_KDF_VERSION       = "kdf_version"
private const val SP_PIN_HASH          = "pin_hash"
private const val SP_PIN_SALT          = "pin_salt"
private const val SP_PIN_KDF_VERSION   = "pin_kdf_version"
private const val SP_KEYSTORE_UPGRADED = "ks_upgraded"
private const val SP_WRAPPED_KEY_CT_V2 = "wrapped_key_ct_v2"
private const val SP_WRAPPED_KEY_IV_V2 = "wrapped_key_iv_v2"

/**
 * SharedPreferences boolean key set when the user explicitly opts out of the
 * Secret Key feature. Read by the Settings > Secret Key screen to render the
 * "you opted out" surface; cleared on every successful Enable.
 *
 * The constant lives here (next to the rest of the auth SP keys) but the
 * Settings VM also references it via its own internal constant; both names
 * MUST resolve to the same on-disk string ("sk_opted_out") or the read/write
 * sides disagree. The SecretKeySettingsViewModel.SP_SK_OPTED_OUT companion
 * pins the canonical name.
 */
private const val SP_SK_OPTED_OUT = "sk_opted_out"

// ── Legacy DataStore keys - read-only, used only during the one-time migration ──

private val DS_SETUP_COMPLETE    = booleanPreferencesKey(SP_SETUP_COMPLETE)
private val DS_SALT              = stringPreferencesKey(SP_SALT)
private val DS_WRAPPED_KEY_CT    = stringPreferencesKey(SP_WRAPPED_KEY_CT)
private val DS_WRAPPED_KEY_IV    = stringPreferencesKey(SP_WRAPPED_KEY_IV)
private val DS_PASSWORD_VERIFIER = stringPreferencesKey(SP_PASSWORD_VERIFIER)
private val DS_KDF_VERSION       = intPreferencesKey(SP_KDF_VERSION)
private val DS_PIN_HASH          = stringPreferencesKey(SP_PIN_HASH)
private val DS_PIN_SALT          = stringPreferencesKey(SP_PIN_SALT)
private val DS_PIN_KDF_VERSION   = intPreferencesKey(SP_PIN_KDF_VERSION)
private val DS_KEYSTORE_UPGRADED = booleanPreferencesKey(SP_KEYSTORE_UPGRADED)
private val DS_WRAPPED_KEY_CT_V2 = stringPreferencesKey(SP_WRAPPED_KEY_CT_V2)
private val DS_WRAPPED_KEY_IV_V2 = stringPreferencesKey(SP_WRAPPED_KEY_IV_V2)

private const val KDF_PBKDF2   = 0
private const val KDF_ARGON2ID = 1

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @Named("auth") private val authPrefs: SharedPreferences,
    private val crypto: CryptoManager,
    private val keyHolder: VaultKeyHolder,
    private val vaultVersionTracker: VaultVersionTracker,
    private val syncEngine: SyncEngine,
    private val kdfMigrator: KdfMigrator,
    // Secret Key wrapper and holder. The wrapper owns the on-disk
    // wrapped-SK blob via the existing `auth` EncryptedSharedPreferences
    // namespace; the holder owns the in-memory plaintext that lives only
    // while the vault is unlocked. Both are no-ops on vaults where the
    // SK feature has never been enabled (the unlock path branches on
    // SP_SECRET_KEY_ENABLED).
    private val secretKeyWrapper: SecretKeyKeystoreWrapper,
    private val secretKeyHolder: SecretKeyHolder,
    @ApplicationScope appScope: CoroutineScope,
) : AuthRepository {

    /**
     * Completed once the one-time DataStore->EncryptedSharedPreferences migration has
     * run (or was skipped because no legacy data existed). All auth reads await this
     * before accessing [authPrefs] so they never see a partially-migrated state.
     */
    private val migrationComplete = CompletableDeferred<Unit>()

    init {
        appScope.launch(Dispatchers.IO) {
            try {
                migrateFromDataStoreIfNeeded()
            } finally {
                migrationComplete.complete(Unit)
            }
        }
        // After the DataStore migration completes, sweep any KDF migration
        // that was interrupted between Phase 1 (staged) and Phase 2 (commit)
        // - e.g. process killed mid-migrate. resumeIfPending() is mutex-guarded
        // inside KdfMigrator and idempotent, so racing a user-initiated
        // migrateTo() is safe. runCatching shields the init from a recovery
        // error so the rest of auth still bootstraps; the user can retry from
        // Settings if recovery fails.
        appScope.launch(Dispatchers.IO) {
            migrationComplete.await()
            runCatching { kdfMigrator.resumeIfPending() }
        }
    }

    /**
     * One-time migration: copies auth keys from the legacy plaintext DataStore into
     * [authPrefs] (EncryptedSharedPreferences), then removes them from DataStore.
     *
     * The idempotency invariant is "no plaintext auth keys remain in DataStore" - i.e.
     * the goal state, not an intermediate state. Older versions of this code keyed the
     * skip on `authPrefs.contains(SP_SETUP_COMPLETE)`, which meant a failure of the
     * cleanup step (after the encrypted-store write succeeded) would leave the legacy
     * blobs in DataStore *forever*: every subsequent run would short-circuit on the
     * encrypted-store check and never retry the cleanup. The plaintext blobs the M4
     * hardening is built to remove would silently persist.
     *
     * The fix: drive the skip off the source state. If any DataStore auth key is
     * present, run the migration. The two phases - copy and clear - are each
     * individually idempotent, so re-running on a partially-migrated state finishes
     * the job. Cleanup failures throw and surface in logcat (rather than being
     * swallowed by `runCatching`); the next launch retries.
     */
    private suspend fun migrateFromDataStoreIfNeeded() {
        val old = dataStore.data.first()
        val hasLegacyKeys = old[DS_SETUP_COMPLETE] != null ||
            old[DS_SALT] != null ||
            old[DS_WRAPPED_KEY_CT] != null ||
            old[DS_WRAPPED_KEY_IV] != null ||
            old[DS_PASSWORD_VERIFIER] != null ||
            old[DS_KDF_VERSION] != null ||
            old[DS_PIN_HASH] != null ||
            old[DS_PIN_SALT] != null ||
            old[DS_PIN_KDF_VERSION] != null ||
            old[DS_KEYSTORE_UPGRADED] != null ||
            old[DS_WRAPPED_KEY_CT_V2] != null ||
            old[DS_WRAPPED_KEY_IV_V2] != null
        if (!hasLegacyKeys) return

        // Phase 1 - copy. Skip when there's nothing valid to copy (no setup-complete
        // marker in DataStore) OR when the encrypted store already has the data.
        // SharedPreferences.commit() is synchronous and atomic at the file-system
        // rename level: either the entire batch lands or none of it does.
        //
        // Important: the half-state "marker missing but other keys present" is a
        // corruption case (e.g. a partial reset). Skipping the copy in that state is
        // correct - there's no valid auth state to migrate - but we MUST NOT skip the
        // cleanup phase below. An earlier version of this fix used `?: return` here,
        // which exited the whole function and left the orphan plaintext keys in
        // DataStore forever (re-audit Finding R1).
        val setupComplete = old[DS_SETUP_COMPLETE]
        val needsCopy = setupComplete != null && !authPrefs.contains(SP_SETUP_COMPLETE)
        if (needsCopy) {
            authPrefs.edit().apply {
                putBoolean(SP_SETUP_COMPLETE, setupComplete!!)
                old[DS_SALT]?.let              { putString(SP_SALT, it) }
                old[DS_WRAPPED_KEY_CT]?.let    { putString(SP_WRAPPED_KEY_CT, it) }
                old[DS_WRAPPED_KEY_IV]?.let    { putString(SP_WRAPPED_KEY_IV, it) }
                old[DS_PASSWORD_VERIFIER]?.let { putString(SP_PASSWORD_VERIFIER, it) }
                old[DS_KDF_VERSION]?.let       { putInt(SP_KDF_VERSION, it) }
                old[DS_PIN_HASH]?.let          { putString(SP_PIN_HASH, it) }
                old[DS_PIN_SALT]?.let          { putString(SP_PIN_SALT, it) }
                old[DS_PIN_KDF_VERSION]?.let   { putInt(SP_PIN_KDF_VERSION, it) }
                old[DS_KEYSTORE_UPGRADED]?.let { putBoolean(SP_KEYSTORE_UPGRADED, it) }
                old[DS_WRAPPED_KEY_CT_V2]?.let { putString(SP_WRAPPED_KEY_CT_V2, it) }
                old[DS_WRAPPED_KEY_IV_V2]?.let { putString(SP_WRAPPED_KEY_IV_V2, it) }
            }.commit()
        }

        // Phase 2 - clear. ALWAYS run when legacy keys exist; do NOT swallow errors.
        // Idempotent: removing a key that's already absent is a no-op, so this is safe
        // to run even when phase 1 was skipped (half-state cleanup) or when phase 1
        // already wrote to the encrypted store on a previous launch (cleanup retry).
        // If this throws (transient I/O, disk full), the failure surfaces in logcat,
        // migrationComplete still completes via the init block's `finally`, and the
        // migration retries on the next launch (when hasLegacyKeys re-evaluates true).
        // This is what makes the goal-state idempotency work: we don't trust an
        // intermediate flag to tell us we're done, we look at the actual data.
        dataStore.edit { p ->
            p.remove(DS_SETUP_COMPLETE)
            p.remove(DS_SALT)
            p.remove(DS_WRAPPED_KEY_CT)
            p.remove(DS_WRAPPED_KEY_IV)
            p.remove(DS_PASSWORD_VERIFIER)
            p.remove(DS_KDF_VERSION)
            p.remove(DS_PIN_HASH)
            p.remove(DS_PIN_SALT)
            p.remove(DS_PIN_KDF_VERSION)
            p.remove(DS_KEYSTORE_UPGRADED)
            p.remove(DS_WRAPPED_KEY_CT_V2)
            p.remove(DS_WRAPPED_KEY_IV_V2)
        }
    }

    override fun isSetupComplete(): Flow<Boolean> = flow {
        migrationComplete.await()
        emitAll(authPrefs.watchBoolean(SP_SETUP_COMPLETE, false))
    }.distinctUntilChanged()

    // ── Setup ─────────────────────────────────────────────────────────────────

    override suspend fun setupMasterPassword(password: CharArray): AppResult<Unit> =
        runCatchingResult {
            migrationComplete.await()

            val vaultSalt = crypto.generateSalt()
            val vaultKey = crypto.deriveKeyFromPassword(password, vaultSalt)
            val keystoreKey = crypto.getOrCreateLegacyKeystoreKey()
            val wrapped = crypto.wrapKey(vaultKey, keystoreKey)

            val verifierSalt = crypto.generateSalt()
            val verifierKey = crypto.deriveKeyFromPasswordArgon2id(password, verifierSalt)
            val verifier = crypto.encryptString("VALID", verifierKey)

            authPrefs.edit().apply {
                putBoolean(SP_SETUP_COMPLETE, true)
                putString(SP_SALT, vaultSalt.encodeBase64())
                putString(SP_WRAPPED_KEY_CT, wrapped.ciphertext.encodeBase64())
                putString(SP_WRAPPED_KEY_IV, wrapped.iv.encodeBase64())
                putString(SP_PASSWORD_VERIFIER, encodeVerifier(verifierSalt, verifier))
                putInt(SP_KDF_VERSION, KDF_ARGON2ID)
            }.commit()

            password.fill(' ')
            keyHolder.setKey(vaultKey)
        }

    override suspend fun setupMasterPasswordWithSecretKey(
        password: CharArray,
        secretKey: ByteArray,
    ): AppResult<Unit> = runCatchingResult {
        migrationComplete.await()
        require(secretKey.size == SecretKeyHolder.SECRET_KEY_RAW_LENGTH) {
            "Secret Key must be ${SecretKeyHolder.SECRET_KEY_RAW_LENGTH} bytes, was ${secretKey.size}"
        }

        // Vault key derivation is unchanged - the SK only mixes into the
        // verifier, not the vault key wrap. The wrap path stays compatible
        // with V4 sync backups and the legacy unwrap path.
        val vaultSalt = crypto.generateSalt()
        val vaultKey = crypto.deriveKeyFromPassword(password, vaultSalt)
        val keystoreKey = crypto.getOrCreateLegacyKeystoreKey()
        val wrapped = crypto.wrapKey(vaultKey, keystoreKey)

        // Verifier IS SK-aware. K_verifier = Argon2id(MP || SK, salt, Standard
        // params). New installs land on Argon2id Standard which uses the
        // KDF_ARGON2ID legacy code in the version int - the SK feature gates
        // off SP_SECRET_KEY_ENABLED rather than off a new KDF version code,
        // so existing recovery and migration paths stay valid (the SK status
        // is a separate dimension from the KDF version dimension).
        val verifierSalt = crypto.generateSalt()
        val verifierKey = crypto.deriveKeyFromPasswordWithSecretKeyArgon2id(
            password = password,
            secretKey = secretKey,
            salt = verifierSalt,
            params = KdfPreset.STANDARD.toKdfParams(),
        )
        val verifier = crypto.encryptString("VALID", verifierKey)

        // Wrap the SK under a fresh Keystore alias (v1, this being the
        // first SK ever installed on this device) BEFORE committing the
        // active config. If the wrap fails (keystore error, OOM, etc.)
        // we abort before any SP write so the user can retry.
        val skAliasVersion = 1
        val wrappedSkBlob = secretKeyWrapper.wrap(secretKey, skAliasVersion)

        // Single edit().commit() so setup-complete, vault key wrap, verifier,
        // and SK enabled state all land atomically. A crash between
        // individual writes would leave the vault in an inconsistent state
        // where (e.g.) SP_SETUP_COMPLETE is true but the verifier blob is
        // missing - bricking the next unlock attempt.
        authPrefs.edit().apply {
            putBoolean(SP_SETUP_COMPLETE, true)
            putString(SP_SALT, vaultSalt.encodeBase64())
            putString(SP_WRAPPED_KEY_CT, wrapped.ciphertext.encodeBase64())
            putString(SP_WRAPPED_KEY_IV, wrapped.iv.encodeBase64())
            putString(SP_PASSWORD_VERIFIER, encodeVerifier(verifierSalt, verifier))
            putInt(SP_KDF_VERSION, KDF_ARGON2ID)
            putBoolean(SP_SECRET_KEY_ENABLED, true)
            putString(SP_SECRET_KEY_WRAPPED, wrappedSkBlob)
            putInt(SP_SK_ACTIVE_ALIAS_VERSION, skAliasVersion)
        }.commit()

        password.fill(' ')
        keyHolder.setKey(vaultKey)
        // Install the SK into the in-memory holder so subsequent reads
        // (Emergency Kit PDF render, V5 backup writes) can call
        // `holder.withBytes { sk -> ... }` for the duration of this
        // unlocked session. The holder copies its input, so the caller's
        // array is independent.
        secretKeyHolder.setBytes(secretKey)
    }

    override suspend fun setupMasterPasswordOptingOutOfSecretKey(
        password: CharArray,
    ): AppResult<Unit> {
        // Delegate to the regular MP-only setup. setupMasterPassword writes
        // SETUP_COMPLETE, salt, wrapped vault key, and verifier in one commit;
        // a separate commit stamps the opt-out flag.
        val result = setupMasterPassword(password)
        if (result is AppResult.Success) {
            // We do NOT touch SP_SECRET_KEY_ENABLED (left at its default false).
            // sk_opted_out is metadata-only - it controls the Settings screen
            // "you opted out" surface and nothing else. A future Enable from
            // Settings clears this flag (SecretKeySettingsViewModel.enable
            // already does the remove() in its post-success commit).
            authPrefs.edit().putBoolean(SP_SK_OPTED_OUT, true).commit()
        }
        return result
    }

    override suspend fun setupWithSecretKeyFromBackup(
        password: CharArray,
        secretKey: ByteArray,
    ): AppResult<Unit> = runCatchingResult {
        migrationComplete.await()
        require(secretKey.size == SecretKeyHolder.SECRET_KEY_RAW_LENGTH) {
            "Secret Key must be ${SecretKeyHolder.SECRET_KEY_RAW_LENGTH} bytes, was ${secretKey.size}"
        }

        // Vault key derivation. The vault key wrap is identical to
        // setupMasterPasswordWithSecretKey - SK does not mix into the wrap
        // path, only the verifier. We MUST take the wrap step here (not via
        // setupMasterPassword) so the salt, wrapped key, and verifier are
        // all committed in a single edit() - the regular setupMasterPassword
        // would write its own MP-only verifier that the SK-aware unlock path
        // would then fail to validate against.
        val vaultSalt = crypto.generateSalt()
        val vaultKey = crypto.deriveKeyFromPassword(password, vaultSalt)
        val keystoreKey = crypto.getOrCreateLegacyKeystoreKey()
        val wrapped = crypto.wrapKey(vaultKey, keystoreKey)

        // SK-aware verifier derivation under Argon2id Standard, matching the
        // shape produced by setupMasterPasswordWithSecretKey. The backup's
        // own KDF params have already been used to DECRYPT the backup file;
        // those params do not need to match this device's verifier params -
        // the verifier is local to this device.
        val verifierSalt = crypto.generateSalt()
        val verifierKey = crypto.deriveKeyFromPasswordWithSecretKeyArgon2id(
            password = password,
            secretKey = secretKey,
            salt = verifierSalt,
            params = KdfPreset.STANDARD.toKdfParams(),
        )
        val verifier = crypto.encryptString("VALID", verifierKey)

        // Wrap the SK under a fresh local Keystore alias (v1, this being
        // the first SK ever installed on THIS device for THIS vault;
        // the source kit's original alias version is irrelevant - what
        // matters is the local generation counter). Mirrors the
        // setupMasterPasswordWithSecretKey contract: a wrap failure
        // aborts BEFORE any SP write so a partial state cannot survive.
        val skAliasVersion = 1
        val wrappedSkBlob = secretKeyWrapper.wrap(secretKey, skAliasVersion)

        // Single edit().commit() so SETUP_COMPLETE, vault key wrap,
        // verifier, KDF version, SK flag, and wrapped SK blob all land
        // atomically. Process death between writes would otherwise brick
        // the next unlock.
        authPrefs.edit().apply {
            putBoolean(SP_SETUP_COMPLETE, true)
            putString(SP_SALT, vaultSalt.encodeBase64())
            putString(SP_WRAPPED_KEY_CT, wrapped.ciphertext.encodeBase64())
            putString(SP_WRAPPED_KEY_IV, wrapped.iv.encodeBase64())
            putString(SP_PASSWORD_VERIFIER, encodeVerifier(verifierSalt, verifier))
            putInt(SP_KDF_VERSION, KDF_ARGON2ID)
            putBoolean(SP_SECRET_KEY_ENABLED, true)
            putString(SP_SECRET_KEY_WRAPPED, wrappedSkBlob)
            putInt(SP_SK_ACTIVE_ALIAS_VERSION, skAliasVersion)
            // The user just demonstrated they have the SK from the kit; a
            // previously-set opt-out flag (from a different vault on the
            // same device) would now be stale.
            remove(SP_SK_OPTED_OUT)
        }.commit()

        password.fill(' ')
        keyHolder.setKey(vaultKey)
        // Install the SK into the in-memory holder so the unlocked session
        // can write fresh V5 backups and render the Emergency Kit without a
        // re-unlock.
        secretKeyHolder.setBytes(secretKey)
    }

    // ── Unlock ────────────────────────────────────────────────────────────────

    override suspend fun unlockWithPassword(password: CharArray): AppResult<Unit> =
        runCatchingResult {
            migrationComplete.await()

            val kdfVersion = authPrefs.getInt(SP_KDF_VERSION, KDF_PBKDF2)
            val passwordForMigration = if (kdfVersion == KDF_PBKDF2) password.copyOf() else null

            // Snapshot the password BEFORE verifyMasterPassword zeros it. This copy
            // belongs to the SyncEngine - it consumes (zeros) the array inside
            // `maybeTriggerSync`, regardless of whether sync is actually enabled.
            // Taking the copy here (not after verifyMasterPassword) is the difference
            // between "sync gets the real password" and "sync gets all-space ASCII".
            val passwordForSync = password.copyOf()

            verifyMasterPassword(password, kdfVersion)

            val vaultKey = unwrapStoredKey()
            keyHolder.setKey(vaultKey)

            // Install the Secret Key into the in-memory holder when the
            // feature is enabled, so the rest of the unlocked session can
            // read it via `secretKeyHolder.withBytes { ... }` for V5
            // backup writes / Emergency Kit renders. We do this AFTER
            // verifyMasterPassword has already succeeded so the wrong-
            // password path never installs the SK.
            loadSecretKeyIntoHolderIfEnabled()

            if (kdfVersion == KDF_PBKDF2 && passwordForMigration != null) {
                try {
                    migrateVerifierToArgon2id(passwordForMigration)
                } catch (_: Exception) {
                } finally {
                    passwordForMigration.fill(' ')
                }
            }

            val keystoreUpgraded = authPrefs.getBoolean(SP_KEYSTORE_UPGRADED, false)
            if (!keystoreUpgraded && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    migrateKeystoreKey(vaultKey)
                } catch (_: Exception) {
                }
            }

            // Fork the sync coroutine LAST so the user sees the unlock succeed
            // immediately and the sync runs in the background. The engine reads
            // sync preferences itself - if disabled or no location set, the
            // passwordForSync array is zeroed and the engine returns without
            // launching work. Master-password material never crosses this
            // boundary as raw bytes; the engine derives a one-shot backup key
            // internally.
            //
            // When Secret Key is enabled on this device, also forward a fresh
            // defensive copy of the unwrapped SK bytes alongside the password.
            // The engine consumes (zeros) both buffers in its own finally; this
            // path does NOT zero the copy here. The wrapper.unwrap() above
            // returns a fresh array each call, so passing it through directly
            // would mean the engine owns OUR only copy - safe but slightly
            // surprising. The .copyOf() makes the ownership boundary explicit:
            // the engine gets its own array to zero, and any future caller
            // that wants to re-read the SK has to go back to the wrapper.
            val skForSync: ByteArray? = if (authPrefs.getBoolean(SP_SECRET_KEY_ENABLED, false)) {
                secretKeyWrapper.unwrap()
            } else {
                null
            }
            syncEngine.maybeTriggerSync(passwordForSync, skForSync)
        }

    override suspend fun unlockWithBiometric(): AppResult<Unit> = runCatchingResult {
        migrationComplete.await()
        keyHolder.setKey(unwrapStoredKey())
        loadSecretKeyIntoHolderIfEnabled()
    }

    /**
     * Reads the active Secret Key from the wrapper and installs it into the
     * in-memory holder. No-op when the SK feature is disabled on this
     * device. Called after a successful biometric or PIN unlock so the
     * unlocked session can render the Emergency Kit / write V5 backups
     * without the user having retyped their master password.
     *
     * The unwrapped bytes are zeroed in finally after the holder has copied
     * them. The wrapper returns null when SP_SECRET_KEY_WRAPPED is absent
     * (which is also the case when SP_SECRET_KEY_ENABLED is false), so the
     * sk_enabled check below is the source of truth - we don't trust an
     * orphan wrapped blob without the enable flag.
     */
    private fun loadSecretKeyIntoHolderIfEnabled() {
        if (!authPrefs.getBoolean(SP_SECRET_KEY_ENABLED, false)) return
        val skBytes = secretKeyWrapper.unwrap() ?: return
        try {
            secretKeyHolder.setBytes(skBytes)
        } finally {
            skBytes.fill(0)
        }
    }

    // ── Change password ───────────────────────────────────────────────────────

    override suspend fun changePassword(oldPassword: CharArray, newPassword: CharArray): AppResult<Unit> =
        runCatchingResult {
            migrationComplete.await()

            // Refuse a no-op change before doing any KDF work. The UI also blocks
            // submitting when current and new match, but defense in depth keeps any
            // future entry point that bypasses the UI honest. Zero both arrays before
            // throwing so the password material has the shortest possible lifetime.
            if (oldPassword.contentEquals(newPassword)) {
                oldPassword.fill(' ')
                newPassword.fill(' ')
                error("Your new password must be different from your current password.")
            }

            val kdfVersion = authPrefs.getInt(SP_KDF_VERSION, KDF_PBKDF2)
            // The unwrapped Secret Key (if enabled) is held only for the
            // duration of this method and zeroed in the finally block,
            // including the wrong-old-password path inside
            // verifyMasterPassword, the Argon2id throw, and the SP commit
            // throw. Without this an SK-enabled user whose verify path
            // mixes SK into the Argon2id input would have changePassword
            // write a verifier derived from MP alone, and the next
            // unlock (which DOES read SK from Keystore and mixes it in)
            // would fail to match - permanent lockout.
            var skBytes: ByteArray? = null
            try {
                verifyMasterPassword(oldPassword, kdfVersion)

                // Preserve the user's active KDF preset across password
                // change. The pre-SK implementation downgraded every user
                // to the legacy KDF_ARGON2ID version int (Standard params)
                // regardless of their preset; that silently weakened
                // Hardened / Maximum users. Now we keep their version int
                // and the matching params. The only path that upgrades the
                // version int is the legacy PBKDF2 -> Argon2id silent
                // migration, kept here for parity with pre-SK behaviour.
                val newKdfVersion = if (kdfVersion == KDF_PBKDF2) KDF_ARGON2ID else kdfVersion

                val skEnabled = authPrefs.getBoolean(SP_SECRET_KEY_ENABLED, false)
                skBytes = if (skEnabled) secretKeyWrapper.unwrap() else null

                val newVerifierSalt = crypto.generateSalt()
                val newVerifierKey = deriveVerifierKey(
                    password = newPassword,
                    salt = newVerifierSalt,
                    kdfVersion = newKdfVersion,
                    secretKey = skBytes,
                )
                val newVerifier = crypto.encryptString("VALID", newVerifierKey)

                authPrefs.edit().apply {
                    putString(SP_PASSWORD_VERIFIER, encodeVerifier(newVerifierSalt, newVerifier))
                    putInt(SP_KDF_VERSION, newKdfVersion)
                }.commit()

                vaultVersionTracker.increment()
            } finally {
                skBytes?.fill(0)
                oldPassword.fill(' ')
                newPassword.fill(' ')
            }
        }

    // ── PIN ───────────────────────────────────────────────────────────────────

    override suspend fun setupPin(pin: CharArray): AppResult<Unit> = runCatchingResult {
        migrationComplete.await()

        require(pin.size == 6) { "PIN must be 6 digits" }
        val salt = crypto.generateSalt()
        val pinKey = crypto.deriveKeyFromPasswordArgon2id(pin, salt)
        pin.fill(' ')
        val pinHash = crypto.encryptString("PIN_VALID", pinKey)

        authPrefs.edit().apply {
            putString(SP_PIN_SALT, salt.encodeBase64())
            putString(SP_PIN_HASH, buildString {
                append(pinHash.ciphertext.encodeBase64())
                append(':')
                append(pinHash.iv.encodeBase64())
            })
            putInt(SP_PIN_KDF_VERSION, KDF_ARGON2ID)
        }.commit()
    }

    override suspend fun unlockWithPin(pin: CharArray): AppResult<Unit> = runCatchingResult {
        migrationComplete.await()

        val pinCopy = pin.copyOf()
        // CRITICAL: must call the THROWING verifyPinInternal here, NOT the public
        // verifyPin which returns AppResult<Unit>. The public overload exists for
        // the in-vault Settings -> Change PIN flow that legitimately consumes the
        // result. Calling it here discards the result, and runCatchingResult only
        // catches exceptions - so any wrong PIN would silently flow through to
        // unwrapStoredKey() and unlock the vault. Regression introduced by M4 (1aeb4cb)
        // and fixed in b? - keep this comment to prevent the same swap recurring.
        verifyPinInternal(pin)
        // pin is zeroed inside verifyPinInternal.

        keyHolder.setKey(unwrapStoredKey())
        loadSecretKeyIntoHolderIfEnabled()

        val pinKdfVersion = authPrefs.getInt(SP_PIN_KDF_VERSION, KDF_PBKDF2)
        if (pinKdfVersion == KDF_PBKDF2) {
            try {
                migratePinVerifierToArgon2id(pinCopy)
            } catch (_: Exception) {
            } finally {
                pinCopy.fill(' ')
            }
        } else {
            pinCopy.fill(' ')
        }
    }

    override suspend fun verifyPin(pin: CharArray): AppResult<Unit> = runCatchingResult {
        migrationComplete.await()
        verifyPinInternal(pin)
    }

    /**
     * Public reauth check used by Settings flows that already hold an
     * unlocked vault and just need to confirm the user still knows the
     * master password. Defensively copies [password] so the implementation
     * can zero its own array without disturbing the caller's copy
     * (mirrors [verifyPin]'s contract). The caller SHOULD still zero its
     * own array on return.
     *
     * Reads the stored KDF version itself; callers don't need to know
     * the encoding. Recognises legacy codes (0, 1) AND the v3 preset
     * codes (30-34) so this method works regardless of whether the user
     * has migrated to a non-Standard preset.
     */
    override suspend fun verifyMasterPassword(password: CharArray): AppResult<Unit> =
        runCatchingResult {
            migrationComplete.await()
            val kdfVersion = authPrefs.getInt(SP_KDF_VERSION, KDF_PBKDF2)
            // Hand verifyMasterPassword a copy so the caller's array survives.
            val copy = password.copyOf()
            try {
                verifyMasterPassword(copy, kdfVersion)
            } finally {
                copy.fill(' ')
            }
        }

    // ── Core verification helpers ─────────────────────────────────────────────

    private fun verifyMasterPassword(password: CharArray, kdfVersion: Int) {
        val verifierStr = authPrefs.getString(SP_PASSWORD_VERIFIER, null)
            ?: error("No verifier stored")
        val parts = verifierStr.split(":")
        require(parts.size == 3) { "Corrupted verifier data" }
        val verSalt = parts[0].decodeBase64()
        val verCt   = parts[1].decodeBase64()
        val verIv   = parts[2].decodeBase64()

        // Resolve the active Secret Key ONCE. When SP_SECRET_KEY_ENABLED is
        // true the unwrapped 16 bytes mix into Argon2id below; the raw bytes
        // are zeroed in a finally block immediately after the verifier key
        // has been derived. When the SK feature is not enabled the
        // derivation path is byte-identical to the pre-SK behaviour.
        val skEnabled = authPrefs.getBoolean(SP_SECRET_KEY_ENABLED, false)
        val skBytes: ByteArray? = if (skEnabled) secretKeyWrapper.unwrap() else null
        val verifierKey = try {
            deriveVerifierKey(password, verSalt, kdfVersion, skBytes)
        } finally {
            skBytes?.fill(0)
        }
        password.fill(' ')

        val plainVerifier = try {
            crypto.decryptString(EncryptedData(verCt, verIv), verifierKey)
        } catch (e: BadPaddingException) {
            throw IllegalStateException("Incorrect master password. Please try again.", e)
        }
        check(plainVerifier == "VALID") { "Incorrect master password. Please try again." }
    }

    /**
     * Derives the verifier key for the stored [kdfVersion] code, optionally
     * mixing in the active Secret Key. Recognises both the legacy two-value
     * encoding (0=PBKDF2, 1=Argon2id Standard) and the v3 explicit-preset
     * encoding (30..34). For [KDF_V3_CUSTOM] the (m, t) parameters are read
     * from the SP_KDF_CUSTOM_M/T side-table; if those are absent we fall
     * back to Standard params and log - this matches the silent-migration
     * ethos of the rest of this repository.
     *
     * When [secretKey] is non-null it is mixed into the Argon2id input via
     * [CryptoManager.deriveKeyFromPasswordWithSecretKeyArgon2id]. The
     * caller owns the array lifetime and zeros it after this returns.
     * Passing a non-null [secretKey] with [KDF_LEGACY_PBKDF2] is rejected
     * because the SK feature only exists on Argon2id-era verifiers.
     */
    private fun deriveVerifierKey(
        password: CharArray,
        salt: ByteArray,
        kdfVersion: Int,
        secretKey: ByteArray? = null,
    ): javax.crypto.SecretKey {
        if (kdfVersion == KDF_LEGACY_PBKDF2) {
            check(secretKey == null) {
                "Secret Key cannot mix into PBKDF2 verifier; complete the silent verifier " +
                    "migration before enabling Secret Key."
            }
            return crypto.deriveKeyFromPassword(password, salt)
        }
        val params = paramsForKdfVersion(kdfVersion)
        return if (secretKey != null) {
            crypto.deriveKeyFromPasswordWithSecretKeyArgon2id(
                password = password,
                secretKey = secretKey,
                salt = salt,
                params = params,
            )
        } else {
            crypto.deriveKeyFromPasswordArgon2id(password, salt, params)
        }
    }

    private fun paramsForKdfVersion(kdfVersion: Int): KdfParams = when (kdfVersion) {
        KDF_LEGACY_ARGON2ID, KDF_V3_STANDARD -> KdfPreset.STANDARD.toKdfParams()
        KDF_V3_STANDARD_PLUS -> KdfPreset.STANDARD_PLUS.toKdfParams()
        KDF_V3_HARDENED -> KdfPreset.HARDENED.toKdfParams()
        KDF_V3_MAXIMUM -> KdfPreset.MAXIMUM.toKdfParams()
        KDF_V3_CUSTOM -> {
            val mMiB = authPrefs.getInt("kdf_custom_m", 64)
            val tCost = authPrefs.getInt("kdf_custom_t", 3)
            KdfParams(mCostKiB = mMiB * 1024, tCost = tCost, parallelism = 1)
        }
        else -> error("Unknown KDF version: $kdfVersion")
    }

    private fun verifyPinInternal(pin: CharArray) {
        val saltB64  = authPrefs.getString(SP_PIN_SALT, null)  ?: error("PIN not set up")
        val hashStr  = authPrefs.getString(SP_PIN_HASH, null)  ?: error("PIN hash missing")
        val salt     = saltB64.decodeBase64()

        val parts = hashStr.split(":")
        require(parts.size == 2) { "Corrupted PIN hash data" }
        val ct = parts[0].decodeBase64()
        val iv = parts[1].decodeBase64()

        val pinKdfVersion = authPrefs.getInt(SP_PIN_KDF_VERSION, KDF_PBKDF2)
        val pinKey = when (pinKdfVersion) {
            KDF_ARGON2ID -> crypto.deriveKeyFromPasswordArgon2id(pin, salt)
            else         -> crypto.deriveKeyFromPassword(pin, salt)
        }
        pin.fill(' ')

        val result = try {
            crypto.decryptString(EncryptedData(ct, iv), pinKey)
        } catch (e: BadPaddingException) {
            throw IllegalStateException("Incorrect PIN. Please try again.", e)
        }
        check(result == "PIN_VALID") { "Incorrect PIN. Please try again." }
    }

    // ── Silent KDF migrations ─────────────────────────────────────────────────

    private suspend fun migrateVerifierToArgon2id(password: CharArray) {
        val newVerifierSalt = crypto.generateSalt()
        val newVerifierKey  = crypto.deriveKeyFromPasswordArgon2id(password, newVerifierSalt)
        val newVerifier     = crypto.encryptString("VALID", newVerifierKey)
        authPrefs.edit().apply {
            putString(SP_PASSWORD_VERIFIER, encodeVerifier(newVerifierSalt, newVerifier))
            putInt(SP_KDF_VERSION, KDF_ARGON2ID)
        }.commit()
    }

    private suspend fun migratePinVerifierToArgon2id(pin: CharArray) {
        val newSalt   = crypto.generateSalt()
        val newPinKey = crypto.deriveKeyFromPasswordArgon2id(pin, newSalt)
        val newHash   = crypto.encryptString("PIN_VALID", newPinKey)
        authPrefs.edit().apply {
            putString(SP_PIN_SALT, newSalt.encodeBase64())
            putString(SP_PIN_HASH, buildString {
                append(newHash.ciphertext.encodeBase64())
                append(':')
                append(newHash.iv.encodeBase64())
            })
            putInt(SP_PIN_KDF_VERSION, KDF_ARGON2ID)
        }.commit()
    }

    // ── Silent Keystore migration (API >= 28) ─────────────────────────────────

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private suspend fun migrateKeystoreKey(vaultKey: javax.crypto.SecretKey) {
        val v2Key      = crypto.createUpgradedKeystoreKey()
        val newWrapped = crypto.wrapKey(vaultKey, v2Key)

        authPrefs.edit().apply {
            putString(SP_WRAPPED_KEY_CT_V2, newWrapped.ciphertext.encodeBase64())
            putString(SP_WRAPPED_KEY_IV_V2, newWrapped.iv.encodeBase64())
            putBoolean(SP_KEYSTORE_UPGRADED, true)
        }.commit()

        runCatching { crypto.deleteKeystoreKey(KEYSTORE_ALIAS_V1) }
    }

    // ── Wrapped key loading ───────────────────────────────────────────────────

    private fun unwrapStoredKey(): javax.crypto.SecretKey {
        val keystoreUpgraded = authPrefs.getBoolean(SP_KEYSTORE_UPGRADED, false)
        return if (keystoreUpgraded) {
            val ct   = authPrefs.getString(SP_WRAPPED_KEY_CT_V2, null)?.decodeBase64()
                ?: error("V2 vault key not found")
            val iv   = authPrefs.getString(SP_WRAPPED_KEY_IV_V2, null)?.decodeBase64()
                ?: error("V2 vault key IV not found")
            val v2Key = crypto.loadKeystoreKey(KEYSTORE_ALIAS_V2)
                ?: error("V2 Keystore key not found - vault may need repair")
            crypto.unwrapKey(EncryptedData(ct, iv), v2Key)
        } else {
            val ct   = authPrefs.getString(SP_WRAPPED_KEY_CT, null)?.decodeBase64()
                ?: error("Vault key not found")
            val iv   = authPrefs.getString(SP_WRAPPED_KEY_IV, null)?.decodeBase64()
                ?: error("Vault key IV not found")
            val v1Key = crypto.getOrCreateLegacyKeystoreKey()
            crypto.unwrapKey(EncryptedData(ct, iv), v1Key)
        }
    }

    // ── Vault management ──────────────────────────────────────────────────────

    override suspend fun resetPin(): AppResult<Unit> = runCatchingResult {
        migrationComplete.await()
        authPrefs.edit().apply {
            remove(SP_PIN_HASH)
            remove(SP_PIN_SALT)
            remove(SP_PIN_KDF_VERSION)
        }.commit()
    }

    override suspend fun resetVault(): AppResult<Unit> = runCatchingResult {
        // Secret Key cleanup BEFORE the catch-all auth-prefs clear so the
        // wrapper's clearAll() can attempt the Keystore alias delete with
        // the SP entries still visible (the deletion path is the same
        // either way; this ordering just makes the runCatching no-op
        // for the SP delete inside clearAll). `crypto.deleteAllVaultKeys`
        // now also drops the SK Keystore alias as a safety net, so a
        // failure in `secretKeyWrapper.clearAll()` still leaves no SK
        // material behind.
        runCatching { secretKeyWrapper.clearAll() }
        secretKeyHolder.clear()

        crypto.deleteAllVaultKeys()
        authPrefs.edit().clear().commit()
        dataStore.edit { it.clear() }
        keyHolder.lock()
    }

    override suspend fun lock(): AppResult<Unit> = runCatchingResult {
        // Drop the in-memory SK alongside the vault key. The on-disk wrapped
        // blob persists - the next unlock will re-read and re-install it.
        secretKeyHolder.clear()
        keyHolder.lock()
    }

    override fun isUnlocked(): Flow<Boolean> = keyHolder.isUnlocked

    override fun isPinSetup(): Flow<Boolean> = flow {
        migrationComplete.await()
        emitAll(authPrefs.watchString(SP_PIN_HASH).map { it != null })
    }.distinctUntilChanged()

    /**
     * Resolves the integer KDF version code to a [KdfPreset]. Legacy codes
     * (0, 1) are folded into [KdfPreset.STANDARD] since that is the semantic
     * config they represent. Unknown codes also fall back to STANDARD - this
     * is the same defensive default the rest of the file uses on the read
     * side; the picker will then offer the user a chance to apply a real
     * preset which will overwrite the unknown code on commit.
     */
    private fun kdfVersionIntToPreset(code: Int): KdfPreset = when (code) {
        KDF_LEGACY_PBKDF2, KDF_LEGACY_ARGON2ID, KDF_V3_STANDARD -> KdfPreset.STANDARD
        KDF_V3_STANDARD_PLUS -> KdfPreset.STANDARD_PLUS
        KDF_V3_HARDENED -> KdfPreset.HARDENED
        KDF_V3_MAXIMUM -> KdfPreset.MAXIMUM
        KDF_V3_CUSTOM -> KdfPreset.CUSTOM
        else -> KdfPreset.STANDARD
    }

    override fun observeActiveKdfPreset(): Flow<KdfPreset> = flow {
        migrationComplete.await()
        // We piggy-back on watchInt for the version key; whenever SP_KDF_VERSION
        // is written - by changePassword, the silent legacy migration, or
        // KdfMigrator's Phase 2 commit - the SharedPreferences listener fires
        // and the flow re-emits the resolved preset.
        emitAll(
            authPrefs.watchInt(SP_KDF_VERSION, KDF_PBKDF2).map { kdfVersionIntToPreset(it) }
        )
    }.distinctUntilChanged()

    override fun observeActiveKdfCustomParams(): Flow<Pair<Int, Int>?> = flow {
        migrationComplete.await()
        // Compose the custom-params view from three keys: the version int (to
        // know whether custom is even active), and the two side-table entries
        // SP_KDF_CUSTOM_M / SP_KDF_CUSTOM_T. A change to any of the three
        // recomputes the emitted pair. Using kotlinx.coroutines.flow.combine
        // here would pull in another flow operator import set; the explicit
        // map { } over watchInt(version) covers the common case (version
        // transitions are what flip the value on/off), and a separate
        // edge-trigger on m or t alone without a version change is impossible
        // because KdfMigrator's Phase 2 commit writes all three keys atomically
        // and the silent migrations never touch the custom side table.
        emitAll(
            authPrefs.watchInt(SP_KDF_VERSION, KDF_PBKDF2).map { code ->
                if (code == KDF_V3_CUSTOM) {
                    val mMiB = authPrefs.getInt("kdf_custom_m", 64)
                    val tCost = authPrefs.getInt("kdf_custom_t", 3)
                    mMiB to tCost
                } else {
                    null
                }
            }
        )
    }.distinctUntilChanged()

    override suspend fun activeKdfParams(): KdfParams {
        migrationComplete.await()
        val kdfVersion = authPrefs.getInt(SP_KDF_VERSION, KDF_PBKDF2)
        // PBKDF2 has no Argon2id parameter shape - surface Standard so the
        // V5 export branch always has well-formed (m, t, p). The actual
        // unlock path stays on PBKDF2 read until the silent migration fires.
        if (kdfVersion == KDF_LEGACY_PBKDF2) return KdfPreset.STANDARD.toKdfParams()
        return paramsForKdfVersion(kdfVersion)
    }

    override suspend fun isSecretKeyEnabled(): Boolean {
        migrationComplete.await()
        return authPrefs.getBoolean(SP_SECRET_KEY_ENABLED, false)
    }

    override fun observeIsSecretKeyEnabled(): Flow<Boolean> = flow {
        // Mirror observeActiveKdfPreset: gate cold-start emission on the
        // one-time DataStore -> EncryptedSharedPreferences migration so the
        // very first emit reflects the post-migrated truth rather than the
        // pre-migrated default. The SharedPreferences listener fires for
        // every Enable / Disable / Rotate transition because those flows
        // commit the new value via the same authPrefs handle.
        migrationComplete.await()
        emitAll(authPrefs.watchBoolean(SP_SECRET_KEY_ENABLED, false))
    }.distinctUntilChanged()

    override fun observeSecretKeyOptedOut(): Flow<Boolean> = flow {
        // Symmetric with observeIsSecretKeyEnabled. Drives the Settings row
        // subtitle ("Off. Opted out at vault creation - tap to enable.")
        // when the user picked opt-out during onboarding or disabled SK
        // later. The setting is cleared by every successful Enable so
        // observers naturally re-render on the next state transition.
        migrationComplete.await()
        emitAll(authPrefs.watchBoolean(SP_SK_OPTED_OUT, false))
    }.distinctUntilChanged()

    override suspend fun clearAll(): AppResult<Unit> = runCatchingResult {
        // Mirror resetVault: drop in-memory SK and wipe the wrapper's
        // on-disk + Keystore state before clearing authPrefs. The SP-level
        // `clear()` below would already drop the wrapped-blob row, but
        // calling the wrapper's helper also targets the Keystore alias
        // which the SP wipe cannot reach.
        runCatching { secretKeyWrapper.clearAll() }
        secretKeyHolder.clear()

        authPrefs.edit().clear().commit()
        dataStore.edit { it.clear() }
        keyHolder.lock()
    }

    // ── Flow helpers ──────────────────────────────────────────────────────────

    private fun SharedPreferences.watchBoolean(key: String, default: Boolean): Flow<Boolean> =
        callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
                if (k == key) trySend(getBoolean(key, default))
            }
            registerOnSharedPreferenceChangeListener(listener)
            trySend(getBoolean(key, default))
            awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
        }

    private fun SharedPreferences.watchString(key: String): Flow<String?> =
        callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
                if (k == key) trySend(getString(key, null))
            }
            registerOnSharedPreferenceChangeListener(listener)
            trySend(getString(key, null))
            awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
        }

    private fun SharedPreferences.watchInt(key: String, default: Int): Flow<Int> =
        callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
                if (k == key) trySend(getInt(key, default))
            }
            registerOnSharedPreferenceChangeListener(listener)
            trySend(getInt(key, default))
            awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
        }

    // ── Encoding helpers ──────────────────────────────────────────────────────

    private fun ByteArray.encodeBase64(): String =
        android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

    private fun String.decodeBase64(): ByteArray =
        android.util.Base64.decode(this, android.util.Base64.NO_WRAP)

    private fun encodeVerifier(salt: ByteArray, verifier: EncryptedData): String =
        "${salt.encodeBase64()}:${verifier.ciphertext.encodeBase64()}:${verifier.iv.encodeBase64()}"
}
