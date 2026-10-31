package com.roufsyed.onekey.core.domain.model

/**
 * Who created a passkey, and therefore who may use it when no trusted browser
 * vouches for the request (passkeys plan, section 5.3).
 */
sealed class PasskeyOrigin {
    /** Created through an allowlisted browser for the given web origin, e.g. `https://github.com`. */
    data class Browser(val origin: String) : PasskeyOrigin()

    /**
     * Created by a native app. [certSha256] is the lowercase hex SHA-256 of the
     * app's current signing certificate at creation time; later requests from
     * the same package are served only when that certificate is in the
     * caller's proven signing lineage.
     */
    data class App(val packageName: String, val certSha256: String) : PasskeyOrigin()
}

/**
 * A WebAuthn discoverable credential owned by a vault [Credential]. The only
 * secret is [privateKeyPkcs8]; [userHandle] and [prfSecret] are sensitive
 * because they identify the account or derive site keys. Everything here is
 * stored encrypted (see `PasskeyEntity`).
 *
 * Immutable after creation apart from [lastUsedAt] and [backedUpAt], which is
 * what makes backup merges trivial: two copies of the same passkey are the
 * same passkey, and the later timestamps win.
 */
class Passkey(
    val id: String,
    val credentialId: String,
    val rpId: String,
    val rpName: String,
    val userName: String,
    val userDisplayName: String,
    val userHandle: ByteArray,
    val webauthnCredentialId: ByteArray,
    val privateKeyPkcs8: ByteArray,
    val prfSecret: ByteArray?,
    val origin: PasskeyOrigin,
    val createdAt: Long,
    val lastUsedAt: Long?,
    val backedUpAt: Long?,
) {
    /** BS flag input: a backup containing this passkey is known to exist. */
    val isBackedUp: Boolean get() = backedUpAt != null

    fun toSummary(): PasskeySummary = PasskeySummary(
        id = id,
        credentialId = credentialId,
        rpId = rpId,
        rpName = rpName,
        userName = userName,
        userDisplayName = userDisplayName,
        webauthnCredentialId = webauthnCredentialId,
        origin = origin,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        backedUpAt = backedUpAt,
    )

    override fun equals(other: Any?): Boolean = this === other || (other is Passkey && other.id == id)
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "Passkey(id=$id, credentialId=$credentialId, rpId=$rpId)"
}

/**
 * The lean projection the provider and the UI work with: everything except the
 * private key, the user handle and the PRF seed. Safe to hold in a list state.
 */
class PasskeySummary(
    val id: String,
    val credentialId: String,
    val rpId: String,
    val rpName: String,
    val userName: String,
    val userDisplayName: String,
    val webauthnCredentialId: ByteArray,
    val origin: PasskeyOrigin,
    val createdAt: Long,
    val lastUsedAt: Long?,
    val backedUpAt: Long?,
) {
    val isBackedUp: Boolean get() = backedUpAt != null

    override fun equals(other: Any?): Boolean = this === other || (other is PasskeySummary && other.id == id && other.lastUsedAt == lastUsedAt && other.backedUpAt == backedUpAt)
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "PasskeySummary(id=$id, rpId=$rpId, userName=$userName)"
}
