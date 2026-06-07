package com.onekey.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thrown by [VaultKeyHolder.requireKey] when the in-memory key has been cleared
 * (auto-lock fired, manual lock, vault reset). Callers that race against the
 * lock - e.g. a save() coroutine that started while unlocked but completes
 * after - pattern-match this type instead of the [Throwable.message] string so
 * a future copy edit doesn't silently break the routing.
 */
class VaultLockedException : IllegalStateException("Vault is locked")

/**
 * Synchronous callback into a snapshot/cache that holds decrypted bytes
 * derived from the vault key. [VaultKeyHolder.lock] invokes [onLockBeforeKeyZero]
 * **before** flipping the unlocked flag and **before** zeroing key material,
 * giving the snapshot a deterministic, dispatcher-independent moment to drop
 * its plaintext list reference.
 *
 * Implementation requirements:
 *  - MUST run synchronously on the lock() caller's thread. No suspending
 *    calls. No blocking I/O. No coroutine launches that the caller might
 *    expect to complete before lock() returns.
 *  - MUST be idempotent - `lock()` may be called multiple times (e.g.
 *    `ResetVaultUseCase` then auto-lock), and the hook fires every time.
 *  - SHOULD complete in microseconds - assigning a `MutableStateFlow.value`,
 *    cancelling a `Job`, nulling a reference. Anything heavier defeats the
 *    purpose of the synchronous contract.
 */
fun interface VaultLockHook {
    fun onLockBeforeKeyZero()
}

/**
 * Holds the in-memory vault key after unlock. Never persisted to disk.
 *
 * On lock, we zero our explicit copy of the raw key bytes as a best-effort
 * mitigation against cold-memory attacks. The JVM cannot guarantee that the
 * [SecretKeySpec]'s internal array is zeroed before GC (Android's
 * [SecretKeySpec.destroy] is a no-op), so this explicit copy is the only key
 * material we can reliably clear.
 */
@Singleton
class VaultKeyHolder @Inject constructor() {

    private var _key: SecretKey? = null
    private var _keyBytes: ByteArray? = null
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    /**
     * Optional synchronous hook invoked at the very top of [lock] - before
     * the `isUnlocked` flag flip and before key bytes are zeroed. Installed
     * by [com.onekey.core.data.snapshot.VaultSnapshotStore] during its DI
     * construction so the snapshot's plaintext list reference is dropped
     * on the lock() caller's thread, NOT asynchronously when the
     * `isUnlocked` StateFlow propagates to a downstream Default-thread
     * collector. See `VaultSnapshotStore`'s lock-clear semantics doc.
     *
     * `@Volatile` for write-visibility across the Hilt construction thread
     * and the eventual `lock()` caller thread (typically Main.immediate
     * via `AutoLockManager` / `AuthRepositoryImpl.lock`).
     *
     * `internal` so only the `:app` module can install it - discourages
     * accidental wide-scope hooks. Nullability is intentional: hook is
     * absent on cold-start before the snapshot store is wired, and lock()
     * must remain safe to call in that window.
     */
    @Volatile internal var snapshotHook: VaultLockHook? = null

    fun setKey(key: SecretKey) {
        // Zero any previously held copy before overwriting.
        _keyBytes?.fill(0)

        // SecretKeySpec.encoded() returns a defensive copy; we hold our own copy
        // so we can zero it on lock. We zero encoded()'s return value immediately
        // after copying since we only need _keyBytes going forward.
        val encoded = (key as? SecretKeySpec)?.encoded
        if (encoded != null) {
            _keyBytes = encoded.copyOf()
            encoded.fill(0)
        }

        _key = key
        _isUnlocked.value = true
    }

    fun requireKey(): SecretKey = _key ?: throw VaultLockedException()

    fun lock() {
        // STEP 1 - Synchronous snapshot hook on the lock() caller's thread.
        // Runs BEFORE the flag flip and BEFORE key bytes are zeroed. Used by
        // VaultSnapshotStore to drop its plaintext list reference and cancel
        // its upstream decryption job dispatcher-independently. See the
        // [VaultLockHook] KDoc - hook implementations MUST NOT suspend or
        // block; this happens on Main.immediate in the typical path.
        snapshotHook?.onLockBeforeKeyZero()

        // STEP 2 - Flip the unlocked flag. Subscribers gated on isUnlocked
        // (the credential observers in CredentialRepositoryImpl) get the
        // false signal asynchronously on their own dispatcher and switch to
        // emptyList(). The snapshot store does NOT rely on this StateFlow
        // emission for its lock semantics - that's what STEP 1 is for.
        _isUnlocked.value = false

        // STEP 3 - Zero key bytes. Any in-flight decryption loop on a worker
        // thread that captured a SecretKey reference before STEP 2 still
        // holds a usable reference (defensive copy via SecretKeySpec); it
        // either completes its current row safely or hits the next
        // keyHolder.isUnlocked.value check inside the decrypt loop and
        // bails via VaultLockedException.
        _keyBytes?.fill(0)
        _keyBytes = null
        _key = null
    }

    fun isUnlocked(): Boolean = _key != null
}
