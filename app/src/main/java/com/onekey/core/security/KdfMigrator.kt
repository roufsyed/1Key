package com.onekey.core.security

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.onekey.core.domain.model.AppResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.crypto.BadPaddingException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private const val TAG_MIGRATOR = "KdfMigrator"

// ── Active config keys in authPrefs (mirror AuthRepositoryImpl's private constants) ──
//
// These names MUST match the strings declared as `private const val SP_*` in
// AuthRepositoryImpl. Keeping a duplicate set here (instead of widening
// AuthRepositoryImpl's visibility) limits the impl-detail surface of the auth
// repository; this file only reads/writes the verifier + KDF version + custom
// params - it does NOT touch the wrapped-key columns, salt, PIN, or anything
// else. A single grep for any name catches both files.

private const val SP_PASSWORD_VERIFIER = "password_verifier"
private const val SP_KDF_VERSION       = "kdf_version"
private const val SP_KDF_CUSTOM_M      = "kdf_custom_m"
private const val SP_KDF_CUSTOM_T      = "kdf_custom_t"

// ── Pending (staging) keys ────────────────────────────────────────────────
//
// Phase 1 writes the proposed new verifier + new KDF identity into these
// pending keys. Phase 2 atomically copies them into the active keys and
// removes the pending ones in a single SharedPreferences.commit().
//
// On any failure (Phase 1 throws, app dies between Phase 1 and Phase 2, etc.)
// the active keys are still pointing at the OLD config; the pending keys are
// dormant data the recovery path (`resumeIfPending`) discards on next launch.
//
// `_started_at` is a debug breadcrumb only. The crash window is sub-second in
// practice (Phase 2 is one synchronous SharedPreferences write), but the
// timestamp lets logcat tell us "this pending state has been hanging around
// for 6 hours" if a real user lands in that state.
//
// `_digest` is an integrity sanity-check on the staged verifier blob. The
// verifier ciphertext is already GCM-authenticated under the new verifier key,
// so corrupting it would just cause Phase 2 to fail verification. The digest
// is belt-and-braces: if a non-atomic disk failure clipped the staged blob
// across an EncryptedSharedPreferences boundary (the underlying file is one
// XML doc; we don't expect a half-written value to survive Android's atomic
// rename, but defence in depth costs little). Recovery uses this digest to
// detect staging-corruption and force a rollback rather than trying to
// salvage half-baked staging data.

private const val SP_KDF_PENDING_VERSION    = "kdf_pending_version"
private const val SP_KDF_PENDING_CUSTOM_M   = "kdf_pending_custom_m"
private const val SP_KDF_PENDING_CUSTOM_T   = "kdf_pending_custom_t"
private const val SP_KDF_PENDING_VERIFIER   = "kdf_pending_verifier"
private const val SP_KDF_PENDING_STARTED_AT = "kdf_pending_started_at"
private const val SP_KDF_PENDING_DIGEST     = "kdf_pending_digest"

// ── Secret Key transition (staging) keys ───────────────────────────────────
//
// The Secret Key (SK) transitions (Enable / Disable / Rotate) reuse the same
// two-phase commit pattern as KDF preset migration but with a disjoint set of
// pending keys. Sharing keys would let a concurrent KDF migration and SK
// transition stomp on each other's staging state - the mutex serialises them,
// but the SP entries are also independent so a half-committed SK transition
// recovery cannot accidentally clear a half-committed KDF migration's data.
//
// `_transition_type` is an Int code (0=Enable, 1=Disable, 2=Rotate). Used by
// the recovery path (`resumePendingInternal`) to log loudly and by the Phase
// 2 commit to decide whether to copy the pending SK blob into the active
// slot or clear the active slot (Disable). Persisted as Int rather than a
// string so a non-malicious bit flip cannot map to an unintended transition
// (the only valid values are 0, 1, 2).

private const val SP_SK_PENDING_VERIFIER        = "sk_pending_verifier"
private const val SP_SK_PENDING_DIGEST          = "sk_pending_digest"
private const val SP_SK_PENDING_STARTED_AT      = "sk_pending_started_at"
private const val SP_SK_PENDING_TRANSITION_TYPE = "sk_pending_transition_type"
private const val SP_SK_PENDING_ENABLED         = "sk_pending_enabled"

// Transition type codes persisted to SP_SK_PENDING_TRANSITION_TYPE.
private const val SK_TRANSITION_NONE    = -1
private const val SK_TRANSITION_ENABLE  = 0
private const val SK_TRANSITION_DISABLE = 1
private const val SK_TRANSITION_ROTATE  = 2

// ── KDF version integer codes ─────────────────────────────────────────────
//
// Stored as an `Int` in SP_KDF_VERSION. Legacy values 0 (PBKDF2) and 1
// (Argon2id with Standard params) remain readable so this code never breaks
// existing installs. New writes always use the explicit preset codes 30+.
//
// The mapping is documented in detail in the design's `kdf_version_storage`
// note. Custom (34) requires a side-table read of SP_KDF_CUSTOM_M /
// SP_KDF_CUSTOM_T because (m, t) aren't encoded in the version int.

internal const val KDF_LEGACY_PBKDF2   = 0
internal const val KDF_LEGACY_ARGON2ID = 1
internal const val KDF_V3_STANDARD      = 30
internal const val KDF_V3_STANDARD_PLUS = 31
internal const val KDF_V3_HARDENED      = 32
internal const val KDF_V3_MAXIMUM       = 33
internal const val KDF_V3_CUSTOM        = 34

/**
 * Stale-staging cutoff. Recovery treats any pending blob older than this as
 * "almost certainly aborted" and rolls back without further analysis. Below
 * the cutoff we still roll back - rolling forward requires the master
 * password which we don't have during process start - but we log at a louder
 * level so post-mortems can spot recent crashes.
 */
private const val STALE_PENDING_AGE_MS = 24L * 60L * 60L * 1000L  // 24 hours

/**
 * Sealed enumeration of the three Secret Key state transitions the migrator
 * can run on behalf of the SK use cases.
 *
 *  - [Enable] - SK was off, becomes on. `newSk` is the freshly-generated
 *    16-byte plaintext to wrap, stage, and commit.
 *  - [Disable] - SK was on, becomes off. The active SK blob is wiped, the
 *    verifier is re-derived from MP alone, and the Keystore alias is removed.
 *  - [Rotate] - SK was on, stays on with a different SK. `newSk` is the
 *    freshly-generated replacement. The OLD SK is dropped (the wrapper's
 *    `wrap()` deletes the previous alias before creating the new one) and
 *    the verifier is re-derived under the new SK.
 *
 * The use cases own SK plaintext generation and call
 * [KdfMigrator.runSecretKeyTransition] which performs the wrap, the two-phase
 * commit, and the SP swap atomically. Keeping SK generation in the use case
 * (not the migrator) lets the use case validate inputs and update the
 * [SecretKeyHolder] post-success without exposing the plaintext bytes to a
 * generic crypto layer.
 */
sealed class SecretKeyTransition {
    object Disable : SecretKeyTransition()

    /** The 16-byte freshly-generated Secret Key plaintext to install. */
    data class Enable(val newSk: ByteArray) : SecretKeyTransition() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Enable && newSk.contentEquals(other.newSk))
        override fun hashCode(): Int = newSk.contentHashCode()
    }

    /** The 16-byte freshly-generated Secret Key plaintext that replaces the active SK. */
    data class Rotate(val newSk: ByteArray) : SecretKeyTransition() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Rotate && newSk.contentEquals(other.newSk))
        override fun hashCode(): Int = newSk.contentHashCode()
    }
}

/**
 * KDF preset migrator: re-derives the master-password verifier under new
 * Argon2id parameters using a two-phase commit pattern so a crash, OOM, or
 * power loss at any point leaves the vault in a recoverable state.
 *
 * # Why two-phase commit (rather than a single overwrite)
 *
 * Naive "overwrite the verifier and KDF version in one edit" would be
 * superficially atomic (SharedPreferences.commit() renames a temp file over
 * the live one, so the rename is OS-atomic). It is NOT, however,
 * **derivationally** atomic: deriving the new verifier from the new params
 * takes 1-4 seconds of Argon2id work, during which the app could be killed
 * (OOM, user backgrounded then OS reclaim, device reboot). If we crash mid-
 * derivation we'd lose the in-flight new verifier - fine - but if we crash
 * mid-write between "verifier overwrite" and "KDF version overwrite"
 * we'd have an irrecoverable mismatch even without the rename guarantee, and
 * a subtler bug class with it (recovery code can't tell whether to roll the
 * mismatched version forward or back).
 *
 * The two-phase pattern moves both writes into a single Phase 2 edit() that
 * either lands as one or not at all. Phase 1 does the slow work (the
 * Argon2id derivation) entirely on staging keys; Phase 2 is a microsecond
 * file rename. The crash surface is the union of:
 *  - "Phase 1 didn't finish" -> staging is dormant or absent; recovery wipes.
 *  - "Phase 2 didn't land" -> staging is fully populated, active is OLD;
 *    recovery wipes (same path).
 *  - "Phase 2 landed" -> active is NEW, staging is gone; done.
 *
 * There is no intermediate state on disk after recovery completes. The
 * "rolled back" outcome is indistinguishable from "user never attempted the
 * migration" - which is the strongest form of safety because nothing
 * downstream (sync, backup, key wrapping) has to know recovery ran.
 *
 * # Rollback strategy
 *
 * Recovery (see [resumeIfPending]) ALWAYS rolls backward. It would be
 * tempting to roll forward when the staged verifier is well-formed and
 * recent, but rolling forward needs to re-derive a verification key from
 * the user's master password - which we do not have during process start.
 * (We would have to either prompt the user during cold boot, which is a UX
 * boundary violation, or persist the new key in cleartext somewhere, which
 * defeats the whole point of the KDF.)
 *
 * Rolling backward is always safe because the active config never moved.
 * The user simply re-attempts the migration from Settings if they want it,
 * and a one-shot UI snackbar surfaces the rollback so it's not silent.
 *
 * # Intermediate-key persistence
 *
 * Cleartext key material is NEVER persisted on disk during migration. The
 * derived `newVerifierKey` only exists in JVM heap, used to encrypt the
 * `"VALID"` plaintext into a fresh GCM ciphertext, then discarded. The
 * staging blob written to SharedPreferences is just the verifier salt + the
 * encrypted-VALID ciphertext + the GCM IV - the same shape and same secrecy
 * level as the active verifier blob.
 *
 * In particular this migrator does NOT persist:
 *  - The new vault key (none is derived; the vault key is Keystore-wrapped
 *    and unchanged by KDF migration - see the design's `migration_semantics`
 *    section).
 *  - The new verifier key (only the verifier ciphertext, which is what GCM
 *    decrypt re-derives from the password on the unlock path).
 *  - The user's master password (zeroed in the use case's `finally` block;
 *    never stored).
 *
 * Compare with a key-rotation design like LUKS or Bitlocker: those persist
 * a re-encrypted unlock blob, but it is itself a ciphertext under the new
 * KEK and so is not "cleartext key material on disk". We hold to the same
 * line: every staged byte on disk is either encrypted under a key derived
 * from the user's password, or it is a non-secret (salt, IV, version int,
 * timestamp).
 *
 * # Integrity check on the staged blob
 *
 * Phase 1 also writes a SHA-256 digest of the encoded staging verifier blob
 * (salt:ct:iv string) into `SP_KDF_PENDING_DIGEST`. Phase 2 (and recovery)
 * re-computes the digest and aborts the commit (or forces rollback) if it
 * doesn't match. This is defence in depth against:
 *  - Disk corruption that survives the EncryptedSharedPreferences atomic
 *    rename (extremely rare; the underlying file is one XML doc).
 *  - A future code change that accidentally writes an inconsistent set of
 *    pending keys (e.g. updates the version but not the verifier).
 *
 * The verifier ciphertext is already GCM-authenticated, so the digest is
 * redundant from a confidentiality/authenticity standpoint. Its job is
 * structural: "are the pending keys the ones Phase 1 wrote, or has
 * something written over a subset of them?"
 *
 * # Vault key handling
 *
 * 1Key wraps the vault key with the **Keystore-resident** wrapping key
 * (SP_WRAPPED_KEY_CT[_V2]), NOT with a password-derived key. So changing
 * the KDF preset does NOT require re-wrapping the vault key, decrypting
 * any credential rows, or re-encrypting any vault contents. The only
 * cryptographic artefact this migrator touches is the verifier - a small
 * ciphertext of the literal string "VALID" used to confirm the user typed
 * the right password before we unwrap the Keystore-wrapped vault key.
 *
 * This is a structural simplification specific to this codebase. A
 * password-wrapped design would require migration-time decryption of the
 * vault key, staging the new wrap, and atomic swap of the wrapped-key
 * columns - all the same two-phase pattern, just on a wider blob. The
 * pattern in this file would generalise to that without modification; we
 * just don't have to do it here.
 */
@Singleton
open class KdfMigrator @Inject constructor(
    @Named("auth") private val authPrefs: SharedPreferences,
    private val crypto: CryptoManager,
    private val deviceCapacityDetector: DeviceCapacityDetector,
    // Secret Key wrapper is injected so [runSecretKeyTransition] can wrap and
    // stage the new SK under the SAME mutex that guards KDF preset migration.
    // The wrapper's `wrap()` deletes the prior Keystore alias before creating
    // the new one, matching the "drop old SK on rotate" property locked by
    // the design.
    private val secretKeyKeystoreWrapper: SecretKeyKeystoreWrapper,
) {

    /**
     * Serialises concurrent migrations and concurrent recovery calls. A
     * second `migrateTo` call queued behind a first one re-reads `authPrefs`
     * so it sees the updated active config (and validates against the new
     * baseline). Recovery on `init` and a user-driven migration cannot race
     * because both take the mutex.
     */
    private val mutex = Mutex()

    /**
     * Re-derives the master-password verifier under [newParams] (which may
     * encode any of the four fixed presets or a custom (m, t) pair) and
     * atomically swaps the active verifier + KDF version on success.
     *
     * Two-phase commit:
     *  1. Verify the supplied [masterPassword] against the CURRENT (old)
     *     verifier under the CURRENT KDF params. Refuses with a clear error
     *     on wrong password without touching any staging state.
     *  2. Derive the new verifier key from [masterPassword] + a fresh salt
     *     under [newParams], encrypt the "VALID" sentinel, and stage the
     *     result + the new version int + (if [newParams] is custom) the
     *     custom (m, t) into the SP_KDF_PENDING_* keys.
     *  3. Re-read the staged blob, recompute its digest, confirm it matches
     *     what we wrote, and decrypt the staged ciphertext under the same
     *     derived key to make sure the round trip works (defence against a
     *     pathological SharedPreferences corruption).
     *  4. Phase 2 commit: in ONE SharedPreferences.edit().commit() copy the
     *     pending values into the active keys, clear the pending keys, and
     *     return success.
     *
     * @param newParams Argon2id (m, t, p, hashLen) tuple for the new verifier
     *                  derivation. Use `KdfPreset.toKdfParams()` for fixed
     *                  presets or construct a `KdfParams` directly for custom.
     * @param newPreset Identity of the preset being applied. Determines the
     *                  KDF version int we write and whether to persist the
     *                  custom (m, t) side-table entries. Pass `KdfPreset.CUSTOM`
     *                  iff [newParams] reflects user-chosen custom values.
     * @param masterPassword The user-typed master password. Caller owns the
     *                       array but the migrator zeroes it (`fill(' ')`)
     *                       before returning, in success and failure paths.
     *
     * @return [AppResult.Success] only when the active config now reflects
     *         [newParams]. On any failure returns [AppResult.Error] with a
     *         user-actionable message and leaves the active config unchanged.
     *
     * Threading: the Argon2id derivation runs on [Dispatchers.Default]. The
     * SharedPreferences writes use the platform's built-in I/O.
     */
    suspend fun migrateTo(
        newParams: KdfParams,
        newPreset: KdfPreset,
        masterPassword: CharArray,
    ): AppResult<Unit> = withContext(Dispatchers.Default) {
        mutex.withLock {
            try {
                // First sweep any leftover pending state from a prior aborted
                // migration. We refuse to start a new migration on top of a
                // half-committed one: the recovery path strictly rolls back,
                // so the cleanup we do here is the only safe option.
                resumePendingInternal()

                // Defence-in-depth: refuse a disabled preset even if the UI
                // somehow tried to send one (e.g. a restored config from a
                // more powerful device). CUSTOM is always considered allowed
                // at this layer; its `m` is gated by the dialog UI.
                if (newPreset != KdfPreset.CUSTOM &&
                    newPreset !in deviceCapacityDetector.snapshot().enabledPresets
                ) {
                    return@withLock failure(
                        IllegalStateException(
                            "Preset ${newPreset.displayName} is not enabled on this device.",
                        ),
                        "Encryption strength '${newPreset.displayName}' requires more " +
                            "RAM than this device has.",
                    )
                }

                // Phase 1a: verify the existing password against the active
                // verifier under the active params. This is the cheapest way
                // to refuse a wrong-password attempt before we burn an
                // Argon2id derivation on the new params.
                val verifyResult = verifyAgainstActive(masterPassword)
                if (verifyResult is AppResult.Error) return@withLock verifyResult

                // Phase 1b: derive the new verifier under newParams and the
                // CURRENT SK setting (KDF preset migration leaves SK status
                // unchanged). If SK is currently enabled we unwrap it,
                // derive with MP||SK, and zero the unwrapped bytes; if not,
                // we derive with MP alone (pre-SK behaviour preserved).
                val skEnabledNow = authPrefs.getBoolean(SP_SECRET_KEY_ENABLED, false)
                val skBytes: ByteArray? = if (skEnabledNow) {
                    try {
                        secretKeyKeystoreWrapper.unwrap()
                    } catch (t: Throwable) {
                        Log.w(TAG_MIGRATOR, "Failed to unwrap SK for new verifier derivation", t)
                        return@withLock failure(
                            t,
                            "Could not read Secret Key from this device's secure storage.",
                        )
                    }
                } else null
                val newVerifierSalt = crypto.generateSalt()
                val newVerifierKey = try {
                    if (skBytes != null) {
                        crypto.deriveKeyFromPasswordWithSecretKeyArgon2id(
                            password = masterPassword,
                            secretKey = skBytes,
                            salt = newVerifierSalt,
                            params = newParams,
                        )
                    } else {
                        crypto.deriveKeyFromPasswordArgon2id(
                            password = masterPassword,
                            salt = newVerifierSalt,
                            params = newParams,
                        )
                    }
                } finally {
                    skBytes?.fill(0)
                }
                val newVerifierBlob = crypto.encryptString("VALID", newVerifierKey)
                val encodedStaging = encodeVerifier(newVerifierSalt, newVerifierBlob)
                val digest = sha256(encodedStaging)

                authPrefs.edit().apply {
                    putInt(SP_KDF_PENDING_VERSION, presetToKdfVersionInt(newPreset))
                    if (newPreset == KdfPreset.CUSTOM) {
                        // mCostKiB -> MiB for storage parity with the slider
                        // (which exposes MiB) - on read we multiply back.
                        putInt(SP_KDF_PENDING_CUSTOM_M, newParams.mCostKiB / 1024)
                        putInt(SP_KDF_PENDING_CUSTOM_T, newParams.tCost)
                    } else {
                        // Defensive: clear any stale custom pending values
                        // from a prior aborted custom -> non-custom attempt.
                        remove(SP_KDF_PENDING_CUSTOM_M)
                        remove(SP_KDF_PENDING_CUSTOM_T)
                    }
                    putString(SP_KDF_PENDING_VERIFIER, encodedStaging)
                    putString(SP_KDF_PENDING_DIGEST, digest)
                    putLong(SP_KDF_PENDING_STARTED_AT, System.currentTimeMillis())
                }.commit().let { ok ->
                    if (!ok) {
                        // commit() returning false is rare (out-of-disk-ish)
                        // but the contract: if Phase 1 didn't actually land
                        // we cannot move to Phase 2. Recovery on next launch
                        // will see incomplete (or no) pending state and
                        // wipe whatever is there; here we just bail.
                        return@withLock failure(
                            IllegalStateException("Phase 1 commit failed"),
                            "Could not stage the new encryption settings. Try again.",
                        )
                    }
                }

                // Phase 1c: integrity round-trip.  Re-read the staged blob
                // through authPrefs, recompute its digest, and verify the
                // ciphertext decrypts under the same derived key. If the OS
                // gave us back a different blob than we wrote, we'd rather
                // abort here than poison the active state in Phase 2.
                val (reReadStaging, reReadDigest) = reReadStagingForVerify()
                    ?: return@withLock rollback(
                        IllegalStateException("Staging round-trip read returned null"),
                        "Could not verify staged encryption settings. Try again.",
                    )
                if (reReadStaging != encodedStaging || reReadDigest != digest) {
                    return@withLock rollback(
                        IllegalStateException("Staging round-trip mismatch"),
                        "Staged encryption settings did not match what was written.",
                    )
                }
                if (!decryptStagingRoundTrip(reReadStaging, newVerifierKey)) {
                    return@withLock rollback(
                        IllegalStateException("Staging ciphertext failed round-trip"),
                        "Could not verify the new encryption settings. Try again.",
                    )
                }

                // Phase 2: atomic swap. ONE edit().commit() does:
                //  - copy pending verifier into active verifier
                //  - copy pending version into active version
                //  - copy pending custom (m,t) into active custom (or clear)
                //  - remove all pending keys
                //
                // SharedPreferences.commit() is synchronous and uses an
                // atomic temp-file rename, so the user sees either the OLD
                // config (active untouched + pending dormant) or the NEW
                // config (active replaced + pending gone). There is no
                // intermediate observable state.
                val phase2Ok = authPrefs.edit().apply {
                    putString(SP_PASSWORD_VERIFIER, encodedStaging)
                    putInt(SP_KDF_VERSION, presetToKdfVersionInt(newPreset))
                    if (newPreset == KdfPreset.CUSTOM) {
                        putInt(SP_KDF_CUSTOM_M, newParams.mCostKiB / 1024)
                        putInt(SP_KDF_CUSTOM_T, newParams.tCost)
                    } else {
                        remove(SP_KDF_CUSTOM_M)
                        remove(SP_KDF_CUSTOM_T)
                    }
                    remove(SP_KDF_PENDING_VERSION)
                    remove(SP_KDF_PENDING_CUSTOM_M)
                    remove(SP_KDF_PENDING_CUSTOM_T)
                    remove(SP_KDF_PENDING_VERIFIER)
                    remove(SP_KDF_PENDING_DIGEST)
                    remove(SP_KDF_PENDING_STARTED_AT)
                }.commit()

                if (!phase2Ok) {
                    // The pending state survives, recovery on next launch
                    // will wipe it, the active config never moved.
                    return@withLock failure(
                        IllegalStateException("Phase 2 commit failed"),
                        "Could not apply the new encryption settings. Try again.",
                    )
                }

                AppResult.Success(Unit)
            } catch (ce: CancellationException) {
                // Coroutine cancellation is structurally distinct from a
                // migration failure - propagate so the calling scope's
                // cancellation semantics are preserved. Best-effort
                // rollback of any in-flight pending state runs first so
                // we don't leave the next launch a mess to clean up.
                runCatching { clearPendingKeys() }
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG_MIGRATOR, "Migration failed", t)
                runCatching { clearPendingKeys() }
                failure(t, t.message ?: "Could not change encryption strength.")
            } finally {
                // Always zero the master password before returning to the
                // caller, regardless of success/failure. The use case in the
                // ViewModel also zeroes its own snapshot defensively, so
                // this is the second zero-pass.
                masterPassword.fill(' ')
            }
        }
    }

    /**
     * Runs an enable / disable / rotate of the Secret Key feature under the
     * SAME mutex that serialises KDF preset migrations (challenger Issue 10).
     *
     * Mutex sharing matters because both transitions write to
     * SP_PASSWORD_VERIFIER. A naive parallel call could let one commit win
     * the SP rename while the other had a stale view of the active SK
     * setting, producing a verifier the user could not unlock with. Holding
     * the same mutex guarantees that whichever transition runs second sees
     * the first's Phase-2 commit.
     *
     * Two-phase commit pattern mirrors [migrateTo]:
     *  1. Verify the master password under the CURRENT verifier (with the
     *     CURRENT SK setting if SP_SECRET_KEY_ENABLED).
     *  2. Derive the new verifier under the FUTURE SK setting (with or
     *     without SK depending on [transition]). Wrap the new SK (Enable /
     *     Rotate) via the wrapper, which deletes the prior Keystore alias
     *     and creates a fresh one (Issue 17). Stage everything via the
     *     SP_SK_PENDING_* keys.
     *  3. Round-trip-verify the staged blob by re-reading it through SP and
     *     decrypting under the derived key.
     *  4. Phase 2: ONE edit().commit() copies pending -> active and removes
     *     all SP_SK_PENDING_* keys. The active SK blob and the active
     *     verifier flip atomically.
     *
     * On any failure the pending keys are cleaned up (including the wrapper's
     * pending blob) and, if the wrap step ran but Phase 2 never landed, the
     * Keystore alias is removed. Active state is untouched.
     *
     * @param masterPassword Caller-owned CharArray. Zeroed in the finally
     *                       block (filled with ' '), success and failure
     *                       alike. Caller should still zero its own copy
     *                       defensively after this returns.
     * @param transition Which transition to perform. [SecretKeyTransition.Enable]
     *                   and [SecretKeyTransition.Rotate] carry the new SK
     *                   bytes; [SecretKeyTransition.Disable] does not.
     */
    open suspend fun runSecretKeyTransition(
        masterPassword: CharArray,
        transition: SecretKeyTransition,
    ): AppResult<Unit> = withContext(Dispatchers.Default) {
        mutex.withLock {
            try {
                // Phase 0: sweep any leftover pending state from a prior
                // aborted transition (or migration). Starting on top of a
                // half-committed pending set would either confuse Phase 2's
                // copy or leak data from the abandoned attempt into this one.
                resumePendingInternal()

                // Phase 1a: refuse a transition that contradicts the current
                // SK state. Catches a UI bug that would re-Enable an already
                // enabled SK or Disable an already disabled one. The use
                // cases SHOULD have validated this; defence in depth here.
                val currentlyEnabled = authPrefs.getBoolean(SP_SECRET_KEY_ENABLED, false)
                val precondition = checkTransitionPrecondition(transition, currentlyEnabled)
                if (precondition != null) return@withLock precondition

                // Phase 1b: validate the new SK bytes BEFORE doing any
                // crypto work. The wrapper checks length again on wrap(),
                // but failing fast here keeps the error path short.
                when (transition) {
                    is SecretKeyTransition.Enable -> requireSk(transition.newSk)
                    is SecretKeyTransition.Rotate -> requireSk(transition.newSk)
                    SecretKeyTransition.Disable -> Unit
                }

                // Phase 1c: verify master password under the CURRENT SK
                // setting. verifyAgainstActive reads SP_SECRET_KEY_ENABLED
                // and unwraps the active SK when needed.
                val verifyResult = verifyAgainstActive(masterPassword)
                if (verifyResult is AppResult.Error) return@withLock verifyResult

                // Phase 1d: derive new verifier under the FUTURE SK setting.
                val futureSk: ByteArray? = when (transition) {
                    is SecretKeyTransition.Enable -> transition.newSk
                    is SecretKeyTransition.Rotate -> transition.newSk
                    SecretKeyTransition.Disable -> null
                }
                val newVerifierSalt = crypto.generateSalt()
                val newVerifierKey = try {
                    deriveActiveVerifierKey(masterPassword, newVerifierSalt, futureSk)
                } catch (badVersion: UnknownKdfVersionException) {
                    return@withLock failure(
                        badVersion,
                        "Stored encryption settings are not recognised by this build.",
                    )
                }
                val newVerifierBlob = crypto.encryptString("VALID", newVerifierKey)
                val encodedStaging = encodeVerifier(newVerifierSalt, newVerifierBlob)
                val digest = sha256(encodedStaging)

                // Phase 1e: wrap the new SK (Enable / Rotate) under a
                // NEW versioned Keystore alias (activeVersion + 1). The
                // active alias is left untouched - critical so the active
                // SP_SECRET_KEY_WRAPPED blob remains decryptable if Phase
                // 2 never lands. The old alias is GC'd after Phase 2
                // commits successfully (post-commit block below).
                val oldActiveVersion = secretKeyKeystoreWrapper.activeVersion()
                val pendingVersion = oldActiveVersion + 1
                val wrappedSkBlob: String? = if (futureSk != null) {
                    try {
                        secretKeyKeystoreWrapper.wrap(futureSk, pendingVersion)
                    } catch (t: Throwable) {
                        Log.w(TAG_MIGRATOR, "Wrap of new Secret Key failed", t)
                        return@withLock failure(
                            t,
                            "Could not store the new Secret Key in this device's secure storage.",
                        )
                    }
                } else null

                // Phase 1f: stage. SP_SK_PENDING_WRAPPED and
                // SP_SK_PENDING_ALIAS_VERSION are written via the wrapper
                // helper in one edit(); the rest of the pending keys are
                // migrator-owned.
                if (wrappedSkBlob != null) {
                    secretKeyKeystoreWrapper.persistPending(wrappedSkBlob, pendingVersion)
                }
                val futureEnabled = transition !is SecretKeyTransition.Disable
                val transitionCode = transitionToCode(transition)
                val phase1Ok = authPrefs.edit().apply {
                    putString(SP_SK_PENDING_VERIFIER, encodedStaging)
                    putString(SP_SK_PENDING_DIGEST, digest)
                    putLong(SP_SK_PENDING_STARTED_AT, System.currentTimeMillis())
                    putInt(SP_SK_PENDING_TRANSITION_TYPE, transitionCode)
                    putBoolean(SP_SK_PENDING_ENABLED, futureEnabled)
                }.commit()
                if (!phase1Ok) {
                    return@withLock rollbackSk(
                        IllegalStateException("SK Phase 1 commit failed"),
                        "Could not stage the new Secret Key settings. Try again.",
                        pendingVersionToCleanUp = if (wrappedSkBlob != null) pendingVersion else 0,
                    )
                }

                // Phase 1g: integrity round-trip on the staged verifier blob.
                val reReadStaging = authPrefs.getString(SP_SK_PENDING_VERIFIER, null)
                val reReadDigest = authPrefs.getString(SP_SK_PENDING_DIGEST, null)
                if (reReadStaging == null || reReadDigest == null ||
                    reReadStaging != encodedStaging || reReadDigest != digest) {
                    return@withLock rollbackSk(
                        IllegalStateException("SK staging round-trip mismatch"),
                        "Could not verify staged Secret Key settings. Try again.",
                        pendingVersionToCleanUp = if (wrappedSkBlob != null) pendingVersion else 0,
                    )
                }
                if (!decryptStagingRoundTrip(reReadStaging, newVerifierKey)) {
                    return@withLock rollbackSk(
                        IllegalStateException("SK staging ciphertext round-trip failed"),
                        "Could not verify the new Secret Key settings. Try again.",
                        pendingVersionToCleanUp = if (wrappedSkBlob != null) pendingVersion else 0,
                    )
                }

                // Phase 2: ONE edit().commit() that swaps verifier + SK
                // active blob + SK enabled bool, and removes all pending
                // keys. SharedPreferences' atomic rename guarantees the user
                // sees either the OLD config (active untouched + pending
                // dormant) or the NEW config (active replaced + pending
                // gone); never an intermediate state.
                val phase2Ok = authPrefs.edit().apply {
                    putString(SP_PASSWORD_VERIFIER, encodedStaging)
                    putBoolean(SP_SECRET_KEY_ENABLED, futureEnabled)
                    if (wrappedSkBlob != null) {
                        putString(SP_SECRET_KEY_WRAPPED, wrappedSkBlob)
                        putInt(SP_SK_ACTIVE_ALIAS_VERSION, pendingVersion)
                    } else {
                        remove(SP_SECRET_KEY_WRAPPED)
                        remove(SP_SK_ACTIVE_ALIAS_VERSION)
                    }
                    remove(SP_SK_PENDING_VERIFIER)
                    remove(SP_SK_PENDING_DIGEST)
                    remove(SP_SK_PENDING_STARTED_AT)
                    remove(SP_SK_PENDING_TRANSITION_TYPE)
                    remove(SP_SK_PENDING_ENABLED)
                    remove(SP_SK_PENDING_WRAPPED)
                    remove(SP_SK_PENDING_ALIAS_VERSION)
                }.commit()
                if (!phase2Ok) {
                    // Phase 2 commit failed: the active SP keys are
                    // either fully old (if the apply atomically rolled
                    // back) or partially updated (rare on SP - the
                    // file write is atomic via rename, so apply() is
                    // all-or-nothing on disk). Either way, the pending
                    // alias minted in Phase 1e is now orphaned: the
                    // OLD wrapped blob in SP still decrypts under the
                    // OLD active alias, and the new pending alias is
                    // never going to be referenced from SP. Drop it so
                    // a retry starts clean.
                    return@withLock rollbackSk(
                        IllegalStateException("SK Phase 2 commit failed"),
                        "Could not apply the new Secret Key settings. Try again.",
                        pendingVersionToCleanUp = if (wrappedSkBlob != null) pendingVersion else 0,
                    )
                }

                // Phase 2 commit succeeded: SP_SK_ACTIVE_ALIAS_VERSION
                // now points at the new alias (or 0 for Disable). The
                // OLD active alias is no longer referenced by any SP
                // value and is safe to garbage-collect. Failure here
                // is non-fatal because the active state is already
                // correct; an orphaned old alias only wastes one
                // Keystore slot until [resumePendingInternal] sweeps it.
                if (oldActiveVersion >= 1) {
                    runCatching { secretKeyKeystoreWrapper.deleteAliasVersion(oldActiveVersion) }
                }

                AppResult.Success(Unit)
            } catch (ce: CancellationException) {
                // Cancellation interrupted a Phase 1 step. Clean up
                // pending state and the freshly-minted pending alias
                // (if Phase 1e ran) so the next attempt starts clean.
                // The active state is intact.
                runCatching { clearSkPendingKeys() }
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG_MIGRATOR, "Secret Key transition failed", t)
                runCatching { clearSkPendingKeys() }
                // If a Phase 1e wrap landed before the failure, drop
                // the dormant pending alias so the next attempt starts
                // clean. The active alias is never touched here.
                val pendingV = secretKeyKeystoreWrapper.pendingVersion()
                if (pendingV >= 1) {
                    runCatching { secretKeyKeystoreWrapper.deleteAliasVersion(pendingV) }
                }
                failure(t, t.message ?: "Could not update Secret Key.")
            } finally {
                masterPassword.fill(' ')
            }
        }
    }

    private fun checkTransitionPrecondition(
        transition: SecretKeyTransition,
        currentlyEnabled: Boolean,
    ): AppResult<Unit>? = when (transition) {
        is SecretKeyTransition.Enable -> if (currentlyEnabled) failure(
            IllegalStateException("Secret Key is already enabled"),
            "Secret Key is already enabled on this device.",
        ) else null
        SecretKeyTransition.Disable -> if (!currentlyEnabled) failure(
            IllegalStateException("Secret Key is already disabled"),
            "Secret Key is already disabled on this device.",
        ) else null
        is SecretKeyTransition.Rotate -> if (!currentlyEnabled) failure(
            IllegalStateException("Secret Key must be enabled to rotate"),
            "Secret Key must be enabled before it can be rotated.",
        ) else null
    }

    private fun requireSk(sk: ByteArray) {
        require(sk.size == SecretKeyHolder.SECRET_KEY_RAW_LENGTH) {
            "Secret Key must be ${SecretKeyHolder.SECRET_KEY_RAW_LENGTH} bytes, was ${sk.size}"
        }
    }

    private fun transitionToCode(transition: SecretKeyTransition): Int = when (transition) {
        is SecretKeyTransition.Enable -> SK_TRANSITION_ENABLE
        SecretKeyTransition.Disable -> SK_TRANSITION_DISABLE
        is SecretKeyTransition.Rotate -> SK_TRANSITION_ROTATE
    }

    /**
     * Failure path for an in-flight SK transition. Clears the pending SK
     * keys and (when [pendingVersionToCleanUp] >= 1) deletes the Keystore
     * alias that the wrap step minted under that version. Active state
     * is never touched - by design, the active alias and the active
     * SP_SECRET_KEY_WRAPPED blob continue to decrypt as before.
     *
     * Pass 0 (the version sentinel for "no pending alias") when Phase 1
     * failed BEFORE the wrap step, so no Keystore work is wasted.
     */
    private fun rollbackSk(
        cause: Throwable,
        message: String,
        pendingVersionToCleanUp: Int,
    ): AppResult<Unit> {
        runCatching { clearSkPendingKeys() }
        if (pendingVersionToCleanUp >= 1) {
            runCatching { secretKeyKeystoreWrapper.deleteAliasVersion(pendingVersionToCleanUp) }
        }
        return failure(cause, message)
    }

    /**
     * Recovery entry point invoked from `AuthRepositoryImpl.init` (alongside
     * the existing legacy-DataStore migration). If a half-committed
     * migration is on disk, this clears the pending state and leaves the
     * active config untouched.
     *
     * Always safe to call: when no pending state exists, this is a no-op
     * single read. When pending state exists, it is unconditionally rolled
     * back - we cannot roll forward without the master password.
     *
     * Returns `true` iff pending state existed and was rolled back (so the
     * caller can surface a one-shot snackbar / SettingsEvent). Returns
     * `false` for the no-op path.
     */
    suspend fun resumeIfPending(): Boolean = mutex.withLock {
        resumePendingInternal()
    }

    /**
     * Internal recovery helper, callable inside the mutex without re-acquiring.
     *
     * Sweeps BOTH the KDF preset migration staging keys and the Secret Key
     * transition staging keys. The two transitions share a mutex but use
     * disjoint SP keys; recovery wipes whichever is on disk and leaves the
     * active state byte-identical. Returns `true` iff either set had data
     * that was cleared.
     *
     * SK orphan-alias sweep: a Phase-1 SK transition may have called
     * `wrapper.wrap(skRaw, pendingVersion)` (which mints a fresh
     * Keystore alias for that generation) without ever landing Phase 2
     * (which atomically commits the new active version pointer). Any
     * `1key_secret_key_v*` alias not matching the current active
     * version is orphaned and must be deleted so the user does not
     * accumulate dormant wrapping keys across rotate-then-rollback
     * cycles. We use [SecretKeyKeystoreWrapper.sweepOrphanAliases]
     * with the active version as the keep-set; SP keys stay untouched.
     */
    private fun resumePendingInternal(): Boolean {
        val pendingVersion = authPrefs.getInt(SP_KDF_PENDING_VERSION, 0)
        val pendingSkTransition = authPrefs.getInt(SP_SK_PENDING_TRANSITION_TYPE, SK_TRANSITION_NONE)
        // Capture the pending SK alias version BEFORE clearSkPendingKeys
        // wipes it, so we know which Keystore alias to delete.
        val pendingSkAliasVersion = secretKeyKeystoreWrapper.pendingVersion()
        val anyResumeWork = pendingVersion != 0 ||
            pendingSkTransition != SK_TRANSITION_NONE ||
            pendingSkAliasVersion > 0

        if (pendingVersion != 0) {
            val startedAt = authPrefs.getLong(SP_KDF_PENDING_STARTED_AT, 0L)
            val ageMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            if (ageMs > STALE_PENDING_AGE_MS) {
                Log.w(
                    TAG_MIGRATOR,
                    "Rolling back stale pending KDF migration (age=${ageMs}ms, version=$pendingVersion)",
                )
            } else {
                Log.i(
                    TAG_MIGRATOR,
                    "Rolling back interrupted KDF migration (age=${ageMs}ms, version=$pendingVersion)",
                )
            }
            clearPendingKeys()
        }

        if (pendingSkTransition != SK_TRANSITION_NONE) {
            val startedAt = authPrefs.getLong(SP_SK_PENDING_STARTED_AT, 0L)
            val ageMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            if (ageMs > STALE_PENDING_AGE_MS) {
                Log.w(
                    TAG_MIGRATOR,
                    "Rolling back stale pending SK transition " +
                        "(age=${ageMs}ms, type=$pendingSkTransition)",
                )
            } else {
                Log.i(
                    TAG_MIGRATOR,
                    "Rolling back interrupted SK transition " +
                        "(age=${ageMs}ms, type=$pendingSkTransition)",
                )
            }
            clearSkPendingKeys()
        }

        // Orphan-alias sweep. Keeps the active alias intact; removes
        // every other 1key_secret_key_v* alias. This handles both the
        // "Phase 1e wrapped but never committed" case (the pending
        // alias is now orphaned) and the "Phase 2 committed but
        // post-commit GC of the old alias did not run" case. Cheap:
        // one Keystore.aliases() enumeration plus zero or one
        // deleteEntry calls in steady state.
        val activeVersion = secretKeyKeystoreWrapper.activeVersion()
        runCatching {
            secretKeyKeystoreWrapper.sweepOrphanAliases(keepVersions = setOf(activeVersion))
        }

        return anyResumeWork
    }

    // ── Verification against active verifier ──────────────────────────────

    /**
     * Decrypts the active verifier blob using `masterPassword` under the
     * currently-active KDF params and (when enabled) the currently-active
     * Secret Key. Returns Success only when the decrypted plaintext equals
     * the literal "VALID".
     *
     * The KDF version int is read here so the migrator does not need a
     * cross-module dependency on AuthRepositoryImpl's private constants.
     *
     * SK awareness: when [SP_SECRET_KEY_ENABLED] is true, the current SK is
     * unwrapped from the Keystore and mixed into the Argon2id input via
     * [CryptoManager.deriveKeyFromPasswordWithSecretKeyArgon2id]. The
     * unwrapped SK bytes are zeroed in a finally block immediately after
     * the verifier key has been derived. When SK is not enabled, the
     * derivation path is unchanged from the pre-SK behaviour.
     */
    private fun verifyAgainstActive(masterPassword: CharArray): AppResult<Unit> {
        val verifierStr = authPrefs.getString(SP_PASSWORD_VERIFIER, null)
            ?: return failure(
                IllegalStateException("No verifier stored"),
                "No master password is set up.",
            )
        val parts = verifierStr.split(":")
        if (parts.size != 3) {
            return failure(
                IllegalStateException("Corrupted verifier"),
                "Stored verifier data is corrupted. Try resetting the vault.",
            )
        }
        val verSalt = decodeBase64(parts[0])
        val verCt   = decodeBase64(parts[1])
        val verIv   = decodeBase64(parts[2])

        // Resolve the active SK ONCE, before the verify loop, so we can zero
        // its bytes in finally regardless of which derivation branch ran.
        // The wrapper returns null when SK is not enabled on this device.
        val skEnabled = authPrefs.getBoolean(SP_SECRET_KEY_ENABLED, false)
        val activeSk: ByteArray? = if (skEnabled) {
            try {
                secretKeyKeystoreWrapper.unwrap()
            } catch (t: Throwable) {
                // A missing alias or a tamper-rejected unwrap means the
                // device cannot satisfy the SK-aware derivation. Surface as
                // an actionable error - the user can re-import their kit
                // to re-stage the SK on this device.
                Log.w(TAG_MIGRATOR, "Failed to unwrap active Secret Key during verify", t)
                return failure(t, "Could not read Secret Key from this device's secure storage.")
            }
        } else null

        // We must NOT consume the caller's password here: migrateTo still
        // needs it for the Phase 1b new-verifier derivation. So we pass a
        // copy. The CryptoManager.deriveKey* APIs all explicitly do not
        // zero their input (caller-owns convention), so a copy is enough.
        val passwordCopy = masterPassword.copyOf()
        val verifierKey = try {
            deriveActiveVerifierKey(passwordCopy, verSalt, activeSk)
        } catch (badVersion: UnknownKdfVersionException) {
            return failure(
                badVersion,
                "Stored encryption settings are not recognised by this build.",
            )
        } finally {
            passwordCopy.fill(' ')
            activeSk?.fill(0)
        }

        return try {
            val plain = crypto.decryptString(EncryptedData(verCt, verIv), verifierKey)
            if (plain == "VALID") {
                AppResult.Success(Unit)
            } else {
                failure(
                    IllegalStateException("Verifier mismatch"),
                    "Incorrect master password. Please try again.",
                )
            }
        } catch (e: BadPaddingException) {
            failure(e, "Incorrect master password. Please try again.")
        }
    }

    /**
     * Derives the verifier key under the currently-active KDF version,
     * optionally mixing in [secretKey]. Pulled out of [verifyAgainstActive]
     * so the SK transition path can share the same derivation table while
     * passing its own SK bytes (or null for Disable).
     *
     * Throws [UnknownKdfVersionException] when SP_KDF_VERSION holds a value
     * outside the known set; callers surface this as an [AppResult.Error]
     * with a user-facing message.
     */
    private fun deriveActiveVerifierKey(
        password: CharArray,
        salt: ByteArray,
        secretKey: ByteArray?,
    ): javax.crypto.SecretKey {
        val version = authPrefs.getInt(SP_KDF_VERSION, KDF_LEGACY_PBKDF2)
        // PBKDF2 (legacy verifier code 0) cannot be combined with SK because
        // the SK feature only exists on Argon2id-era verifiers. A device
        // that somehow has SK_ENABLED=true but is still on PBKDF2 is in an
        // inconsistent state - we refuse the derivation rather than silently
        // dropping the SK and producing a wrong verifier key.
        if (version == KDF_LEGACY_PBKDF2) {
            if (secretKey != null) {
                throw IllegalStateException(
                    "Secret Key is enabled but the active verifier is still on PBKDF2; " +
                        "complete the silent verifier migration first.",
                )
            }
            return crypto.deriveKeyFromPassword(password, salt)
        }
        val params = paramsForVersion(version)
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

    /**
     * Resolves the stored KDF version int to a concrete [KdfParams] tuple
     * for use with the Argon2id derivation helpers. Throws
     * [UnknownKdfVersionException] for unrecognised codes; callers surface
     * the exception as an [AppResult.Error].
     */
    private fun paramsForVersion(version: Int): KdfParams = when (version) {
        KDF_LEGACY_ARGON2ID, KDF_V3_STANDARD -> KdfPreset.STANDARD.toKdfParams()
        KDF_V3_STANDARD_PLUS -> KdfPreset.STANDARD_PLUS.toKdfParams()
        KDF_V3_HARDENED -> KdfPreset.HARDENED.toKdfParams()
        KDF_V3_MAXIMUM -> KdfPreset.MAXIMUM.toKdfParams()
        KDF_V3_CUSTOM -> {
            val mMiB = authPrefs.getInt(SP_KDF_CUSTOM_M, 64)
            val tCost = authPrefs.getInt(SP_KDF_CUSTOM_T, 3)
            KdfParams(mCostKiB = mMiB * 1024, tCost = tCost, parallelism = 1)
        }
        else -> throw UnknownKdfVersionException(version)
    }

    /** Thrown by [paramsForVersion] when SP_KDF_VERSION holds an unrecognised code. */
    private class UnknownKdfVersionException(version: Int) :
        IllegalStateException("Unknown KDF version: $version")

    // ── Staging round-trip integrity helpers ──────────────────────────────

    private fun reReadStagingForVerify(): Pair<String, String>? {
        val staging = authPrefs.getString(SP_KDF_PENDING_VERIFIER, null) ?: return null
        val digest = authPrefs.getString(SP_KDF_PENDING_DIGEST, null) ?: return null
        return staging to digest
    }

    /**
     * Decrypts the staged verifier ciphertext under the just-derived verifier
     * key. Returns `true` iff the round trip succeeds AND the decrypted
     * plaintext matches the sentinel string "VALID".
     *
     * This is a paranoid check - the same derivation produced both the
     * key and the staging ciphertext seconds earlier, so a mismatch
     * indicates a bug in our staging code (or a `SharedPreferences`
     * corruption between write and re-read), not an actual security event.
     */
    private fun decryptStagingRoundTrip(
        encodedStaging: String,
        verifierKey: javax.crypto.SecretKey,
    ): Boolean {
        val parts = encodedStaging.split(":")
        if (parts.size != 3) return false
        return try {
            val ct = decodeBase64(parts[1])
            val iv = decodeBase64(parts[2])
            crypto.decryptString(EncryptedData(ct, iv), verifierKey) == "VALID"
        } catch (t: Throwable) {
            Log.w(TAG_MIGRATOR, "Staging decrypt round-trip failed", t)
            false
        }
    }

    // ── Pending-state lifecycle helpers ───────────────────────────────────

    private fun clearPendingKeys() {
        authPrefs.edit().apply {
            remove(SP_KDF_PENDING_VERSION)
            remove(SP_KDF_PENDING_CUSTOM_M)
            remove(SP_KDF_PENDING_CUSTOM_T)
            remove(SP_KDF_PENDING_VERIFIER)
            remove(SP_KDF_PENDING_DIGEST)
            remove(SP_KDF_PENDING_STARTED_AT)
        }.commit()
    }

    /**
     * Removes every Secret Key transition staging key from `authPrefs`. Also
     * calls the wrapper's `clearPending` so the pending wrapped SK blob is
     * wiped in the same recovery sweep. Does NOT touch the Keystore alias -
     * the orphan-alias sweep in [resumePendingInternal] handles that with a
     * separate heuristic.
     */
    private fun clearSkPendingKeys() {
        authPrefs.edit().apply {
            remove(SP_SK_PENDING_VERIFIER)
            remove(SP_SK_PENDING_DIGEST)
            remove(SP_SK_PENDING_STARTED_AT)
            remove(SP_SK_PENDING_TRANSITION_TYPE)
            remove(SP_SK_PENDING_ENABLED)
        }.commit()
        // The pending wrapped blob lives under a wrapper-owned key. Clear it
        // via the wrapper's helper so the SP key string stays a single source
        // of truth inside the wrapper.
        secretKeyKeystoreWrapper.clearPending()
    }

    private fun rollback(cause: Throwable, message: String): AppResult<Unit> {
        runCatching { clearPendingKeys() }
        return failure(cause, message)
    }

    private fun failure(cause: Throwable, message: String): AppResult<Unit> =
        AppResult.Error(cause, message)

    // ── Encoding & hashing helpers ────────────────────────────────────────

    private fun encodeVerifier(salt: ByteArray, ed: EncryptedData): String =
        "${encodeBase64(salt)}:${encodeBase64(ed.ciphertext)}:${encodeBase64(ed.iv)}"

    private fun encodeBase64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decodeBase64(s: String): ByteArray =
        Base64.decode(s, Base64.NO_WRAP)

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val out = md.digest(s.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    companion object {
        /**
         * Maps a [KdfPreset] to the integer code stored in `SP_KDF_VERSION`.
         * Custom is encoded as [KDF_V3_CUSTOM]; the live (m, t) are recorded
         * separately in `SP_KDF_CUSTOM_M` and `SP_KDF_CUSTOM_T`.
         *
         * Exposed so the use case / tests can encode without re-implementing
         * the table, and so a future reader sees one place to change if the
         * codes ever shift.
         */
        fun presetToKdfVersionInt(preset: KdfPreset): Int = when (preset) {
            KdfPreset.STANDARD       -> KDF_V3_STANDARD
            KdfPreset.STANDARD_PLUS  -> KDF_V3_STANDARD_PLUS
            KdfPreset.HARDENED       -> KDF_V3_HARDENED
            KdfPreset.MAXIMUM        -> KDF_V3_MAXIMUM
            KdfPreset.CUSTOM         -> KDF_V3_CUSTOM
        }
    }
}
