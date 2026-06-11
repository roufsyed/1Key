package com.onekey.core.security

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the in-memory 16-byte Secret Key (SK) plaintext while the vault is
 * unlocked. The SK is mixed into the Argon2id KDF input alongside the master
 * password (`K = Argon2id(MP || SK, salt, params)`) and is required to derive
 * the verifier and to encrypt/decrypt V5 backup envelopes that carry the
 * `requires_secret_key` FLAGS bit.
 *
 * Lifecycle:
 *  - On vault setup or restore, the SK is generated/imported by the relevant
 *    use case and handed to [setBytes]. The holder keeps a defensive copy.
 *  - While the vault is unlocked, callers reach the SK via [withBytes]. The
 *    lambda receives the raw 16 bytes; the holder rebuilds a fresh defensive
 *    copy each time so different call sites cannot interfere with each
 *    other's lambda state.
 *  - [clear] zeroes the held bytes and drops the reference. Wired in at the
 *    same time as [VaultKeyHolder.lock] so a locked vault has no SK in
 *    memory, mirroring how the vault key bytes are wiped.
 *
 * The SK is never persisted by this class - persistence (Keystore-wrapped
 * blob in EncryptedSharedPreferences) is owned by
 * [SecretKeyKeystoreWrapper]. Separating in-memory and on-disk concerns
 * matches the project's "two named hook slots beat a multi-listener
 * registry" auditability stance: a grep for `holder.withBytes` finds every
 * SK reader in seconds, no hidden indirection.
 *
 * Memory hygiene contract:
 *  - The block passed to [withBytes] MUST NOT escape the parameter
 *    reference. The array is overwritten with zeros before the lambda
 *    returns (success OR throw). Any retained reference becomes a
 *    zero-filled array. Production call sites pass the bytes directly to
 *    [CryptoManager.deriveKeyFromPasswordWithSecretKeyArgon2id] or to the
 *    Emergency Kit PDF renderer; neither retains the reference.
 *  - [setBytes] copies its argument; the caller may safely zero its own
 *    array immediately after the call returns.
 *  - [clear] zeroes the holder's copy in place. The JVM may have made other
 *    copies during GC compaction, but the explicit zero is the only
 *    cleanup we can reliably do from Kotlin.
 *
 * Threading: backed by [AtomicReference] for write-visibility across the
 * Hilt construction thread, the auth coroutine that calls [setBytes] /
 * [clear], and any UI coroutine that calls [withBytes]. The atomic
 * reference swap on [setBytes] guarantees the new bytes are visible to all
 * subsequent readers without a lock.
 */
@Singleton
class SecretKeyHolder @Inject constructor() {

    /**
     * Defensive copy of the raw 16 SK bytes, or `null` when no SK has been
     * loaded into the holder (cold-start, or after [clear]). Stored as an
     * atomic reference so the swap on [setBytes] is visible across threads
     * without requiring callers to hold a lock.
     */
    private val skBytes = AtomicReference<ByteArray?>(null)

    /**
     * Installs [sk] as the in-memory Secret Key. Copies the input - the
     * caller may safely [ByteArray.fill] its own array after this returns.
     *
     * If a prior SK was held, its bytes are zeroed in place before the new
     * reference takes over. This matters during a rotate operation: the
     * old SK should not linger in heap after the new one is staged.
     *
     * @throws IllegalArgumentException if [sk] is not exactly 16 bytes. The
     *   128-bit length is fundamental to the 2SKD design (matches
     *   1Password's published SK size and the KDF input shape locked in
     *   the design doc); silently accepting other sizes would let a
     *   future bug propagate the wrong byte count into Argon2id.
     */
    fun setBytes(sk: ByteArray) {
        require(sk.size == SECRET_KEY_RAW_LENGTH) {
            "Secret Key must be $SECRET_KEY_RAW_LENGTH bytes, was ${sk.size}"
        }
        val copy = sk.copyOf()
        val previous = skBytes.getAndSet(copy)
        previous?.fill(0)
    }

    /**
     * Runs [block] with a fresh defensive copy of the SK bytes. The copy is
     * zeroed in `finally` before this method returns, regardless of whether
     * the block completed normally or threw.
     *
     * The generic return type `<R>` lets callers thread arbitrary results
     * out of the lambda without smuggling the bytes themselves out. The
     * common shapes are:
     *  - `holder.withBytes { sk -> formatHumanString(sk) }` returns a
     *    String.
     *  - `holder.withBytes { sk -> crypto.deriveKeyFromPasswordWithSecretKeyArgon2id(...) }`
     *    returns a `SecretKey` that downstream code holds onto - the SK
     *    bytes themselves are zeroed before that key is observed.
     *
     * @throws IllegalStateException if no SK has been installed via
     *   [setBytes] (the vault is locked, or this is a fresh install where
     *   SK was opted out). Callers that may legitimately have no SK
     *   available should check [isPresent] first.
     */
    fun <R> withBytes(block: (ByteArray) -> R): R {
        val held = skBytes.get()
            ?: throw IllegalStateException("Secret Key is not loaded")
        val copy = held.copyOf()
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    /**
     * Returns `true` when an SK has been installed via [setBytes] and is
     * still held (not [clear]ed). Used by callers that may legitimately
     * run with or without an SK loaded (the legacy V4 backup path, for
     * example) and need to branch on availability before calling
     * [withBytes].
     */
    fun isPresent(): Boolean = skBytes.get() != null

    /**
     * Zeroes the held SK bytes in place and drops the reference. Idempotent
     * - safe to call multiple times. After [clear], [isPresent] returns
     * `false` and [withBytes] throws.
     *
     * Invoked from the same surfaces as [VaultKeyHolder.lock]: auto-lock
     * fires, manual lock, vault reset. The wiring is done in Stage 7; this
     * class only owns the zero-and-drop behaviour itself.
     */
    fun clear() {
        val previous = skBytes.getAndSet(null)
        previous?.fill(0)
    }

    companion object {
        /**
         * Raw 16-byte (128-bit) length of the Secret Key plaintext. Locked
         * by the design doc and the 1Password 2SKD model this feature
         * mirrors. Public so test fixtures and the QR parser can share the
         * single definition.
         */
        const val SECRET_KEY_RAW_LENGTH: Int = 16
    }
}
