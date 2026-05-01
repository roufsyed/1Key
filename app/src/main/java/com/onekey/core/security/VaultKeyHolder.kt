package com.onekey.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.SecretKey
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
 * On lock or process death the key is zeroed and GC'd.
 */
@Singleton
class VaultKeyHolder @Inject constructor() {

    private var _key: SecretKey? = null
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    fun setKey(key: SecretKey) {
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
        _key = null
    }

    fun isUnlocked(): Boolean = _key != null
}
