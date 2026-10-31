package com.roufsyed.onekey.core.domain.webauthn

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Flag bits of the authenticator data `flags` byte (WebAuthn Level 3 section 6.1). */
object AuthenticatorFlags {
    /** User Present: the user interacted with the device for this ceremony. Always set by 1Key. */
    const val UP = 0x01
    /** User Verified: a fresh biometric, PIN or master-password check succeeded inside the request. */
    const val UV = 0x04
    /** Backup Eligible: the credential can exist on more than one device. Always set (section 7.4 of the plan). */
    const val BE = 0x08
    /** Backup State: a backup containing this credential currently exists. Set only when honest. */
    const val BS = 0x10
    /** Attested credential data follows (registration only). */
    const val AT = 0x40
    /** Extension data follows. */
    const val ED = 0x80
}

/**
 * Builds WebAuthn authenticator data (Level 3 section 6.1):
 *
 * ```
 * rpIdHash (32) || flags (1) || signCount (4, big-endian) || [attestedCredentialData] || [extensions]
 * attestedCredentialData = aaguid (16) || credentialIdLength (2, big-endian) || credentialId || credentialPublicKey (COSE)
 * ```
 *
 * The signature counter is a constant zero. The spec allows authenticators
 * that do not implement a counter to leave it at zero; any non-zero counter
 * that fails to increase after a backup is restored to a second device would
 * read to the relying party as a cloned authenticator, so zero is the only
 * value compatible with 1Key's file-based backup and sync.
 */
object AuthenticatorData {
    const val SIGN_COUNT = 0

    fun rpIdHash(rpId: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray(Charsets.UTF_8))

    /** Authenticator data for a registration (attested credential data present). */
    fun registration(
        rpId: String,
        credentialId: ByteArray,
        cosePublicKey: ByteArray,
        userVerified: Boolean,
        backupState: Boolean,
        aaguid: ByteArray = OneKeyAaguid.BYTES,
        extensions: ByteArray? = null,
    ): ByteArray {
        require(aaguid.size == 16) { "AAGUID must be 16 bytes" }
        require(credentialId.size in 16..1023) { "credential ID must be 16 to 1023 bytes" }
        val attested = ByteArrayOutputStream().apply {
            write(aaguid)
            write(credentialId.size shr 8)
            write(credentialId.size and 0xFF)
            write(credentialId)
            write(cosePublicKey)
        }.toByteArray()
        return build(
            rpIdHash = rpIdHash(rpId),
            flags = flags(userVerified, backupState) or AuthenticatorFlags.AT,
            attested = attested,
            extensions = extensions,
        )
    }

    /** Authenticator data for an assertion (no attested credential data). */
    fun assertion(
        rpId: String,
        userVerified: Boolean,
        backupState: Boolean,
        extensions: ByteArray? = null,
    ): ByteArray = build(
        rpIdHash = rpIdHash(rpId),
        flags = flags(userVerified, backupState),
        attested = null,
        extensions = extensions,
    )

    private fun flags(userVerified: Boolean, backupState: Boolean): Int {
        var f = AuthenticatorFlags.UP or AuthenticatorFlags.BE
        if (userVerified) f = f or AuthenticatorFlags.UV
        if (backupState) f = f or AuthenticatorFlags.BS
        return f
    }

    private fun build(rpIdHash: ByteArray, flags: Int, attested: ByteArray?, extensions: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(rpIdHash)
        out.write(if (extensions != null) flags or AuthenticatorFlags.ED else flags)
        // signCount: 4 bytes big-endian, constant zero.
        out.write(SIGN_COUNT shr 24)
        out.write(SIGN_COUNT shr 16)
        out.write(SIGN_COUNT shr 8)
        out.write(SIGN_COUNT)
        if (attested != null) out.write(attested)
        if (extensions != null) out.write(extensions)
        return out.toByteArray()
    }
}
