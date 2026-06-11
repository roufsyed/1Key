package com.onekey.core.security

import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val AES_GCM = "AES/GCM/NoPadding"
private const val GCM_TAG_LENGTH = 128
private const val GCM_IV_LENGTH = 12
private const val TAG = "SecretKeyKeystoreWrapper"

/**
 * Prefix for the per-generation Android Keystore aliases that wrap the
 * Secret Key plaintext on disk. The full alias for generation N is
 * `"${KEYSTORE_ALIAS_SECRET_KEY_PREFIX}${N}"`, e.g. `1key_secret_key_v1`
 * for the first SK ever installed, `1key_secret_key_v2` for the next
 * one after a Rotate, and so on. Distinct from the vault-key alias so
 * the SK and vault key live in independent Keystore slots.
 *
 * # Why a counter, not a fixed alias
 *
 * Rotate / Enable / Disable all use a two-phase commit (see [KdfMigrator]).
 * If [wrap] destructively replaced a single fixed alias before Phase 2
 * landed, a crash or power loss between Phase 1e (wrap) and Phase 2
 * (atomic SP commit) would leave the active SK ciphertext on disk
 * encrypted under a wrapping key that no longer exists, and the user
 * would be permanently locked out. Versioning the alias name fixes
 * that: [wrap] for generation N+1 NEVER touches the alias for
 * generation N. Phase 2 atomically updates [SP_SK_ACTIVE_ALIAS_VERSION]
 * to point at the new alias; only after the SP commit succeeds does
 * the post-commit GC delete the old alias.
 *
 * # First-time and disabled state
 *
 * Version 0 means "no Secret Key is installed". When [activeVersion]
 * returns 0, no `1key_secret_key_v0` alias is ever created (the value
 * is a sentinel). The first Enable goes from 0 -> 1.
 */
internal const val KEYSTORE_ALIAS_SECRET_KEY_PREFIX = "1key_secret_key_v"

/**
 * Legacy fixed-alias name retained for migration: builds prior to the
 * versioned-alias change minted exactly one alias under this name.
 * [migrateLegacyFixedAlias] sweeps any leftover entry of this name into
 * the v1 slot if it is the only one present, so existing users whose
 * SK was set up before this change can still unlock.
 *
 * Effectively equivalent to `"${KEYSTORE_ALIAS_SECRET_KEY_PREFIX}1"`, so
 * existing v1 users need no migration at all - the alias name happens to
 * line up. This constant stays in the file to keep the historical
 * intent visible.
 */
internal const val KEYSTORE_ALIAS_SECRET_KEY_V1 = "1key_secret_key_v1"

/**
 * SharedPreferences key for the currently-active wrapped Secret Key blob.
 * Stored as a base64 string of `IV (12 bytes) || ciphertext (16 bytes) ||
 * GCM auth tag (16 bytes)`. Lives in the existing `onekey_auth`
 * EncryptedSharedPreferences namespace, so the underlying bytes on disk
 * are encrypted-at-rest by the Jetpack security library before they ever
 * reach the filesystem.
 */
internal const val SP_SECRET_KEY_WRAPPED = "secret_key_wrapped"

/**
 * SharedPreferences key for the pending wrapped Secret Key blob written
 * during Phase 1 of a Secret Key transition (enable / disable / rotate).
 * Phase 2 copies the pending blob into [SP_SECRET_KEY_WRAPPED] in the same
 * synchronous `edit().commit()` that swaps the verifier, so a crash
 * between the two phases leaves the active SK pointing at the OLD value.
 */
internal const val SP_SK_PENDING_WRAPPED = "sk_pending_wrapped"

/**
 * SharedPreferences boolean key marking whether the Secret Key feature is
 * currently active on this device. `true` means [SP_SECRET_KEY_WRAPPED] is
 * populated and the unlock path mixes the unwrapped SK into the Argon2id
 * input; `false` means the device is running the MP-only verifier and the
 * SK Keystore alias should be absent.
 *
 * Lives in the existing `onekey_auth` EncryptedSharedPreferences namespace
 * so the flag, the wrapped SK blob, the verifier, and the wrapped vault
 * key are all in the same atomic-on-disk file. Phase 2 of an SK transition
 * flips this in the same `edit().commit()` that swaps the verifier and the
 * SK blob, so observers never see a half-state.
 */
internal const val SP_SECRET_KEY_ENABLED = "secret_key_enabled"

/**
 * SharedPreferences int key for the generation number of the alias under
 * which [SP_SECRET_KEY_WRAPPED] is encrypted. 0 means "no SK installed";
 * `>=1` means the active alias is
 * `"${KEYSTORE_ALIAS_SECRET_KEY_PREFIX}${value}"`. Phase 2 of a Secret
 * Key transition copies [SP_SK_PENDING_ALIAS_VERSION] into this key in
 * the same `edit().commit()` that swaps the wrapped blob, so observers
 * never see a blob/version mismatch.
 *
 * The counter is monotonically increasing across the lifetime of the
 * install. Disable resets it to 0 (deleting the active alias as part of
 * the post-commit GC); a subsequent Enable starts at 1 again. Rotate
 * goes N -> N+1.
 */
internal const val SP_SK_ACTIVE_ALIAS_VERSION = "sk_active_alias_version"

/**
 * SharedPreferences int key for the generation number of the alias that
 * Phase 1 of an in-flight SK transition just minted. 0 means "no
 * transition pending". Written by [persistPending] alongside the new
 * wrapped blob; copied into [SP_SK_ACTIVE_ALIAS_VERSION] by Phase 2;
 * cleared by [clearPending] (rollback) or Phase 2 commit (success).
 */
internal const val SP_SK_PENDING_ALIAS_VERSION = "sk_pending_alias_version"

/**
 * Wraps the 16-byte plaintext Secret Key with an AES-256-GCM key minted in
 * the Android Keystore under [KEYSTORE_ALIAS_SECRET_KEY_V1]. The wrapping
 * key prefers StrongBox isolation and silently falls back to TEE-backed
 * storage on devices without a discrete tamper-resistant element. Pattern
 * mirrors [CryptoManager]'s vault-key wrapping (commit 1def89f).
 *
 * # Why a separate wrapper class (and not just methods on CryptoManager)
 *
 * Persistence concerns - the on-disk blob's base64 envelope, the
 * EncryptedSharedPreferences key constants, the active/pending two-phase
 * commit semantics - are SK-specific and would clutter [CryptoManager]'s
 * surface. Pulling them into a dedicated `@Singleton` keeps `CryptoManager`
 * focused on raw crypto primitives and lets the SK use cases interact
 * with persistence through one named DI dependency.
 *
 * # Two-phase commit API
 *
 * The wrapper exposes BOTH staging slots (active and pending) so the SK
 * use cases - which run inside [KdfMigrator.runSecretKeyTransition] under
 * the shared mutex - can stage a fresh blob in Phase 1, verify it
 * round-trips through SharedPreferences, then atomically copy it into
 * the active slot in Phase 2. The shape mirrors the verifier two-phase
 * pattern in [KdfMigrator] and makes the failure-recovery contract
 * trivial: a crash between Phase 1 and Phase 2 leaves
 * [SP_SECRET_KEY_WRAPPED] pointing at the OLD blob, and recovery wipes
 * [SP_SK_PENDING_WRAPPED] without touching the active slot.
 *
 * # API split (NOT `wrapAndPersist`)
 *
 * [wrap] returns the base64 blob string and touches the Keystore (creates
 * the wrapping alias on first use) but NEVER writes to SharedPreferences.
 * Persistence is opted into explicitly via [persistActive] (for the
 * final committed slot) or [persistPending] (for the Phase 1 staging
 * slot). This separation is required by the challenger response Issue 3:
 * a single `wrapAndPersist` cannot represent the two-phase pattern
 * without surprising side-effects on rollback.
 *
 * On rotate or re-enable, [wrap] mints a NEW versioned alias
 * (`v${activeVersion+1}`) and leaves the active alias untouched. The
 * "drop old SK on rotate" guarantee is preserved by Phase 2 of the SK
 * transition (in [KdfMigrator]): once the new wrapped blob and new
 * active version are atomically committed to SharedPreferences, a
 * post-commit garbage-collect step deletes the previous alias. If the
 * Phase 2 commit fails or the process dies before it lands, the prior
 * alias is still on disk and the prior wrapped blob still decrypts -
 * which is exactly the recoverable state we want.
 *
 * # Memory hygiene
 *
 * - [wrap] zeros its `skRaw` input copy in `finally` after encryption.
 *   The caller's array is NOT zeroed - the contract is symmetric with
 *   [SecretKeyHolder.setBytes] which also copies its input.
 * - [unwrap] / [unwrapBlob] return a fresh 16-byte ByteArray that the
 *   caller MUST zero after use (typically by handing it straight to
 *   [SecretKeyHolder.setBytes], which copies, then calling
 *   [ByteArray.fill] on the returned array).
 *
 * @param authPrefs the `@Named("auth")` EncryptedSharedPreferences instance
 *   the rest of the auth stack already writes to. Injected (not
 *   constructed) so the SK blob lives next to the verifier, the wrapped
 *   vault key, and the KDF version int - one atomic-on-disk file.
 */
@Singleton
open class SecretKeyKeystoreWrapper @Inject constructor(
    @Named("auth") private val authPrefs: SharedPreferences,
) {

    /**
     * Wraps [skRaw] under a freshly-minted AES-256-GCM Keystore key bound
     * to alias `v${targetVersion}` and returns the base64-encoded
     * `IV || ciphertext || tag` blob. The blob is suitable for direct
     * persistence via [persistPending] (Phase 1) and eventually
     * [persistActive] (Phase 2). NO other alias is touched.
     *
     * [targetVersion] MUST be >= 1 and SHOULD be `activeVersion() + 1`
     * (Phase 1 of an SK transition reads [activeVersion] and increments).
     * Other versions are accepted to support test fixtures.
     *
     * If the target alias already exists (e.g., a prior in-flight
     * transition crashed after wrap but before [clearPending]'s alias
     * delete), the existing wrapping key is reused. This is safe
     * because cold-start recovery in [KdfMigrator.resumePendingInternal]
     * deletes any orphan pending alias before normal use can resume;
     * within a single launch a wrap retry of the same generation under
     * the same wrapping key produces a fresh blob that decrypts cleanly
     * (the wrapping key is the same; only the GCM IV differs).
     *
     * @throws IllegalArgumentException if [skRaw] is not exactly 16
     *   bytes, or if [targetVersion] is < 1.
     */
    fun wrap(skRaw: ByteArray, targetVersion: Int): String {
        require(skRaw.size == SecretKeyHolder.SECRET_KEY_RAW_LENGTH) {
            "Secret Key must be ${SecretKeyHolder.SECRET_KEY_RAW_LENGTH} bytes, was ${skRaw.size}"
        }
        require(targetVersion >= 1) {
            "SK alias version must be >= 1, was $targetVersion"
        }
        val alias = aliasFor(targetVersion)
        val wrappingKey = getOrCreateWrappingKey(alias)
        val skCopy = skRaw.copyOf()
        return try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val ciphertext = cipher.doFinal(skCopy)
            val iv = cipher.iv
            // GCM tag is appended to ciphertext by AES/GCM/NoPadding; total
            // length is plaintext.size + GCM_TAG_LENGTH / 8 = 16 + 16 = 32.
            val buf = ByteBuffer.allocate(iv.size + ciphertext.size)
            buf.put(iv)
            buf.put(ciphertext)
            Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        } finally {
            skCopy.fill(0)
        }
    }

    /**
     * Computes the Keystore alias name for an SK generation. Version 1
     * yields the historical fixed alias name; version 2 and above are
     * monotonically named.
     */
    private fun aliasFor(version: Int): String {
        require(version >= 1) { "SK alias version must be >= 1, was $version" }
        return "$KEYSTORE_ALIAS_SECRET_KEY_PREFIX$version"
    }

    /**
     * Persists [blob] into [SP_SECRET_KEY_WRAPPED] alongside its alias
     * generation [version] into [SP_SK_ACTIVE_ALIAS_VERSION], in a
     * single synchronous `edit().commit()`. Used by direct enablement
     * paths that do not run through the migrator's two-phase commit
     * (e.g., initial vault setup, where there is no prior verifier to
     * swap and the active SP keys can be written in one shot).
     */
    fun persistActive(blob: String, version: Int) {
        require(version >= 1) { "SK alias version must be >= 1, was $version" }
        authPrefs.edit().apply {
            putString(SP_SECRET_KEY_WRAPPED, blob)
            putInt(SP_SK_ACTIVE_ALIAS_VERSION, version)
        }.commit()
    }

    /**
     * Persists [blob] into [SP_SK_PENDING_WRAPPED] alongside its alias
     * generation [version] into [SP_SK_PENDING_ALIAS_VERSION], in a
     * single synchronous `edit().commit()`. Used in Phase 1 of an SK
     * transition; Phase 2 copies the pending pair into the active pair
     * in the same edit() that swaps the verifier.
     */
    fun persistPending(blob: String, version: Int) {
        require(version >= 1) { "SK alias version must be >= 1, was $version" }
        authPrefs.edit().apply {
            putString(SP_SK_PENDING_WRAPPED, blob)
            putInt(SP_SK_PENDING_ALIAS_VERSION, version)
        }.commit()
    }

    /** Returns the active wrapped blob or `null` when no SK is installed. */
    fun activeBlob(): String? = authPrefs.getString(SP_SECRET_KEY_WRAPPED, null)

    /** Returns the pending wrapped blob or `null` when no transition is staged. */
    fun pendingBlob(): String? = authPrefs.getString(SP_SK_PENDING_WRAPPED, null)

    /**
     * Returns the active alias generation, or 0 if no SK is installed.
     * Always read alongside [activeBlob] (they are kept consistent by
     * [persistActive] / [clearActive] / [KdfMigrator]'s Phase 2 commit).
     */
    fun activeVersion(): Int = authPrefs.getInt(SP_SK_ACTIVE_ALIAS_VERSION, 0)

    /**
     * Returns the pending alias generation, or 0 if no SK transition is
     * in flight. Used by Phase 2 commit and by rollback paths.
     */
    fun pendingVersion(): Int = authPrefs.getInt(SP_SK_PENDING_ALIAS_VERSION, 0)

    /**
     * Decodes [blob] and decrypts it with the Keystore wrapping key,
     * returning the raw 16 SK bytes. The caller owns the returned array
     * and MUST [ByteArray.fill] it after use - typically by handing it
     * straight to [SecretKeyHolder.setBytes] (which copies) and then
     * zeroing this array.
     *
     * Reads the wrapping key from the Keystore but does NOT create it:
     * if the alias is missing (caller passed a blob but the device's
     * Keystore was wiped) the call fails with `IllegalStateException`
     * rather than silently minting a new key that could not decrypt the
     * blob anyway.
     */
    fun unwrapBlob(blob: String, version: Int): ByteArray {
        require(version >= 1) { "SK alias version must be >= 1, was $version" }
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        require(raw.size > GCM_IV_LENGTH) {
            "Wrapped SK blob too short: ${raw.size}"
        }
        val alias = aliasFor(version)
        val iv = raw.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = raw.copyOfRange(GCM_IV_LENGTH, raw.size)
        val wrappingKey = loadWrappingKey(alias)
            ?: throw IllegalStateException(
                "Secret Key wrapping alias '$alias' is missing",
            )
        val cipher = Cipher.getInstance(AES_GCM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, spec)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Convenience read of the currently-active wrapped blob. Returns the
     * decrypted 16 SK bytes, or `null` when no active blob exists (SK is
     * not enabled on this install) OR when [SP_SK_ACTIVE_ALIAS_VERSION]
     * is 0. See [unwrapBlob] for the memory hygiene contract on the
     * returned array.
     */
    fun unwrap(): ByteArray? {
        val version = activeVersion()
        if (version == 0) return null
        val blob = activeBlob() ?: return null
        return unwrapBlob(blob, version)
    }

    /**
     * Removes [SP_SECRET_KEY_WRAPPED] AND [SP_SK_ACTIVE_ALIAS_VERSION]
     * without touching the pending slot or any Keystore alias.
     */
    fun clearActive() {
        authPrefs.edit().apply {
            remove(SP_SECRET_KEY_WRAPPED)
            remove(SP_SK_ACTIVE_ALIAS_VERSION)
        }.commit()
    }

    /**
     * Removes [SP_SK_PENDING_WRAPPED] AND [SP_SK_PENDING_ALIAS_VERSION]
     * without touching the active slot or any Keystore alias.
     */
    fun clearPending() {
        authPrefs.edit().apply {
            remove(SP_SK_PENDING_WRAPPED)
            remove(SP_SK_PENDING_ALIAS_VERSION)
        }.commit()
    }

    /**
     * Removes all SK SP entries AND deletes every `1key_secret_key_v*`
     * alias in the Keystore. Called from the vault reset flow so a
     * wiped vault leaves no SK artefacts behind.
     */
    fun clearAll() {
        authPrefs.edit().apply {
            remove(SP_SECRET_KEY_WRAPPED)
            remove(SP_SK_ACTIVE_ALIAS_VERSION)
            remove(SP_SK_PENDING_WRAPPED)
            remove(SP_SK_PENDING_ALIAS_VERSION)
        }.commit()
        deleteAllSkAliases()
    }

    /**
     * Deletes the Keystore alias for a specific SK generation without
     * touching SharedPreferences. Phase 2 commit calls this on the
     * previous active version after the SP swap succeeds (post-commit
     * GC); rollback paths call it on the pending version when Phase 1
     * minted an alias that the failure should remove.
     *
     * No-op when [version] is < 1 (sentinel for "no SK installed") or
     * when the alias does not exist on disk.
     */
    open fun deleteAliasVersion(version: Int) {
        if (version < 1) return
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val alias = aliasFor(version)
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    /**
     * Deletes every `1key_secret_key_v*` alias the Keystore currently
     * holds. Used by [clearAll]. Failure to delete an individual alias
     * does not abort the sweep - we make best effort and continue.
     */
    open fun deleteAllSkAliases() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val toDelete = keyStore.aliases().toList()
            .filter { it.startsWith(KEYSTORE_ALIAS_SECRET_KEY_PREFIX) }
        for (alias in toDelete) {
            runCatching { keyStore.deleteEntry(alias) }
        }
    }

    /**
     * Cold-start orphan sweep: deletes every `1key_secret_key_v*` alias
     * EXCEPT those listed in [keepVersions]. Used by
     * [KdfMigrator.resumePendingInternal] to clean up leftover aliases
     * from a post-commit GC that failed to run, or from a Phase 1e
     * that crashed before the wrapper could roll back.
     *
     * Pass a set containing the current active version (and, if a
     * legitimate pending transition is in flight that the caller is
     * about to resume rather than roll back, the pending version too).
     * Versions less than 1 are ignored.
     */
    open fun sweepOrphanAliases(keepVersions: Set<Int>) {
        val keepNames = keepVersions
            .filter { it >= 1 }
            .map { aliasFor(it) }
            .toSet()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val toDelete = keyStore.aliases().toList()
            .filter { it.startsWith(KEYSTORE_ALIAS_SECRET_KEY_PREFIX) && it !in keepNames }
        for (alias in toDelete) {
            runCatching { keyStore.deleteEntry(alias) }
        }
    }

    // -- Keystore plumbing --------------------------------------------------
    //
    // Replicates the StrongBox-prefer-with-TEE-fallback pattern used by
    // `CryptoManager.getOrCreateKeystoreKey` (commit 1def89f). Inlined here
    // so the wrapper does not need access to `CryptoManager`'s private
    // helper, and so the SK alias gets its own independent lifecycle
    // (rotation via deleteAlias() must not impact the vault key).

    private fun getOrCreateWrappingKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(alias, null)?.let { return it as SecretKey }

        fun buildSpec(useStrongBox: Boolean): KeyGenParameterSpec =
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setUnlockedDeviceRequired(true)
                    }
                    if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setIsStrongBoxBacked(true)
                    }
                }
                .build()

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                keyGenerator.init(buildSpec(useStrongBox = true))
                val key = keyGenerator.generateKey()
                Log.i(TAG, "SK wrapping key generated in StrongBox for $alias")
                return key
            } catch (e: android.security.keystore.StrongBoxUnavailableException) {
                // Expected on devices without a discrete StrongBox chip
                // (most non-Pixel hardware). Log a single info line
                // without the stack trace - the exception itself carries
                // no useful diagnostics beyond "no StrongBox here", and
                // every wrap on a non-StrongBox device would otherwise
                // emit an 11-line trace into the production log.
                Log.i(TAG, "StrongBox unavailable for $alias; falling back to TEE")
            } catch (e: java.security.ProviderException) {
                // Field reports show some Pixel firmware updates surface
                // ProviderException for StrongBox under load. Treat the
                // same as unavailable - TEE-backed is still hardware
                // isolated and full-strength. Keep the stack trace here
                // because the ProviderException CAN come from many
                // root causes and the trace helps diagnose them.
                Log.w(
                    TAG,
                    "StrongBox generateKey threw ProviderException for $alias; falling back to TEE",
                    e,
                )
            }
        }

        keyGenerator.init(buildSpec(useStrongBox = false))
        return keyGenerator.generateKey()
    }

    private fun loadWrappingKey(alias: String): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(alias, null) as? SecretKey
    }
}
