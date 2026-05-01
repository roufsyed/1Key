package com.onekey.feature.twofa.domain

import android.net.Uri
import com.onekey.core.domain.model.OtpAlgorithm
import com.onekey.core.domain.model.OtpParams
import com.onekey.core.domain.model.OtpType

/**
 * Inverse of [OtpAuthUriParser]: produces the standard `otpauth://` URI form
 * for an [OtpParams] + issuer/account triple.
 *
 * Used by the plaintext exporter so non-default params (SHA-256, 8 digits,
 * 60s period, HOTP counter, Steam type) survive a round-trip through CSV / JSON
 * to other authenticator apps (Aegis, 2FAS) and back. Without this, an export
 * silently flattens every entry to SHA-1 / 30s / 6 — corrupting the user's
 * data the moment they hit "Export".
 *
 * Steam entries: per the Steam Guard convention, the URI lists `issuer=Steam`
 * and uses host `totp` (since Steam Guard is TOTP under the hood with a custom
 * output alphabet). The reverse path in [OtpAuthUriParser] re-detects Steam
 * from that issuer string and restores [OtpType.STEAM] on import.
 */
object OtpAuthUriBuilder {

    /**
     * @return a `otpauth://` URI carrying every persisted field of [params].
     * Issuer / account are URL-encoded. Both label and `issuer=` query are emitted
     * for compatibility with apps that look at one or the other.
     */
    fun build(params: OtpParams, issuer: String, account: String): String {
        val host = when (params.type) {
            OtpType.HOTP -> HOST_HOTP
            // Steam is wire-compatible with TOTP — issuer string carries the variant.
            OtpType.TOTP, OtpType.STEAM -> HOST_TOTP
        }
        // Issuer-prefixed label is the canonical form. Falls back to account-only
        // when there's no issuer; account-only is also legal per the Key URI spec.
        val label = when {
            issuer.isNotEmpty() && account.isNotEmpty() ->
                "${Uri.encode(issuer)}:${Uri.encode(account)}"
            issuer.isNotEmpty() -> Uri.encode(issuer)
            else -> Uri.encode(account)
        }
        // We declare Steam as `issuer=Steam` so other apps (and our own re-import path)
        // can route the auto-detection. For non-Steam types the issuer query is the
        // user's typed value, falling back to omission when blank.
        val effectiveIssuer = if (params.type == OtpType.STEAM) STEAM_ISSUER else issuer
        val builder = Uri.Builder()
            .scheme(SCHEME)
            .authority(host)
            .path("/$label")
            .appendQueryParameter("secret", params.secret)
        if (effectiveIssuer.isNotEmpty()) {
            builder.appendQueryParameter("issuer", effectiveIssuer)
        }
        builder.appendQueryParameter("algorithm", params.algorithm.name)
        builder.appendQueryParameter("digits", params.digits.toString())
        when (params.type) {
            OtpType.HOTP -> builder.appendQueryParameter("counter", params.counter.toString())
            OtpType.TOTP, OtpType.STEAM -> builder.appendQueryParameter("period", params.period.toString())
        }
        return builder.build().toString()
    }

    private const val SCHEME = "otpauth"
    private const val HOST_TOTP = "totp"
    private const val HOST_HOTP = "hotp"
    private const val STEAM_ISSUER = "Steam"

    /**
     * Defensive helper used as a stable reference from tests and the exporter:
     * tells whether [OtpAlgorithm] handling will round-trip cleanly. All current
     * variants do; the helper exists to make a future addition explicit.
     */
    fun isAlgorithmKnown(algorithm: OtpAlgorithm): Boolean =
        OtpAlgorithm.entries.contains(algorithm)
}
