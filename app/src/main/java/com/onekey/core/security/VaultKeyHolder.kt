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
 * lock — e.g. a save() coroutine that started while unlocked but completes
 * after — pattern-match this type instead of the [Throwable.message] string so
 * a future copy edit doesn't silently break the routing.
 */
class VaultLockedException : IllegalStateException("Vault is locked")

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
        // Flip the unlocked flag *before* dropping the key reference. Subscribers gated
        // on isUnlocked (the credential observers in CredentialRepositoryImpl) get the
        // false signal first and switch to emptyList(), while any in-flight decryption
        // loop on a worker thread still has a valid key reference until it finishes.
        _isUnlocked.value = false
        _keyBytes?.fill(0)
        _keyBytes = null
        _key = null
    }

    fun isUnlocked(): Boolean = _key != null
}
