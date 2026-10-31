package com.roufsyed.onekey.core.domain.passkey

import com.roufsyed.onekey.core.domain.model.PasskeyOrigin
import java.security.MessageDigest

/**
 * What the platform proved about the app that sent a passkey request, plus the
 * result of the privileged-browser allowlist lookup. Built by the Android
 * adapter (`CallerVerifier`) and judged here, with no Android types, so the
 * policy is unit-testable branch by branch.
 *
 * @param currentCertSha256 lowercase hex SHA-256 of the signing certificate in
 *   use right now (the newest entry of the signing lineage).
 * @param lineageSha256 every certificate the package has proven it owned,
 *   lowercase hex; key rotation is matched against this set.
 * @param claimedOrigin true when the caller set a web origin at all.
 * @param privilegedOrigin the origin the allowlist vouched for, or null when
 *   the caller set none or is not on the list.
 */
class CallerIdentity(
    val packageName: String,
    val currentCertSha256: String,
    val lineageSha256: Set<String>,
    val multipleSigners: Boolean,
    val claimedOrigin: Boolean,
    val privilegedOrigin: String?,
)

sealed class CallerKind {
    /** An allowlisted browser speaking for [origin]. It enforces RP ID rules itself. */
    data class Browser(val origin: String) : CallerKind()

    /** A native app identified by its package and signing lineage. */
    data class App(val packageName: String, val certSha256: String, val lineageSha256: Set<String>) : CallerKind()

    data class Refused(val reason: Reason) : CallerKind()

    enum class Reason {
        /** The caller claimed a web origin but is not on any privileged-browser list. */
        UNKNOWN_BROWSER,
        /** APK signed by several keys at once; no single identity to pin. */
        MULTIPLE_SIGNERS,
    }
}

/**
 * The offline caller-verification rule from the passkeys plan (section 5):
 *
 * - A caller that set an origin is served only if the allowlist vouched for it.
 *   An unknown browser is refused outright, never downgraded to a native app:
 *   pinning it would give it a passkey whose `apk-key-hash` origin no site
 *   accepts, and the confusing failure would land on the user.
 * - A native app may create passkeys freely (a fresh key pair is useless to an
 *   impostor) and may later use only passkeys bound to its own package and
 *   signing lineage. Browser-created passkeys are never released to apps.
 * - No step ever asks the user to extend trust.
 */
object CallerPolicy {

    fun resolve(identity: CallerIdentity): CallerKind {
        if (identity.claimedOrigin) {
            val origin = identity.privilegedOrigin ?: return CallerKind.Refused(CallerKind.Reason.UNKNOWN_BROWSER)
            return CallerKind.Browser(origin)
        }
        if (identity.multipleSigners) return CallerKind.Refused(CallerKind.Reason.MULTIPLE_SIGNERS)
        return CallerKind.App(
            packageName = identity.packageName,
            certSha256 = identity.currentCertSha256.lowercase(),
            lineageSha256 = identity.lineageSha256.map { it.lowercase() }.toSet(),
        )
    }

    /** May [caller] exercise a passkey whose creation binding is [binding]? */
    fun mayUse(binding: PasskeyOrigin, caller: CallerKind): Boolean = when (caller) {
        is CallerKind.Browser -> true
        is CallerKind.App -> binding is PasskeyOrigin.App &&
            binding.packageName == caller.packageName &&
            binding.certSha256.lowercase() in caller.lineageSha256
        is CallerKind.Refused -> false
    }

    /** The binding to store on a passkey [caller] creates, or null when creation is refused. */
    fun bindingFor(caller: CallerKind): PasskeyOrigin? = when (caller) {
        is CallerKind.Browser -> PasskeyOrigin.Browser(caller.origin)
        is CallerKind.App -> PasskeyOrigin.App(caller.packageName, caller.certSha256)
        is CallerKind.Refused -> null
    }

    /** Lowercase hex SHA-256 of a DER-encoded certificate, the form every binding stores. */
    fun certificateSha256Hex(certificateDer: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificateDer).joinToString("") { "%02x".format(it) }

    /** Raw digest bytes for the `android:apk-key-hash:` origin of a native caller. */
    fun certificateSha256(certificateDer: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(certificateDer)

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(":", "").lowercase()
        require(clean.length == 64 && clean.all { it in '0'..'9' || it in 'a'..'f' }) { "not a SHA-256 hex string" }
        return ByteArray(32) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
