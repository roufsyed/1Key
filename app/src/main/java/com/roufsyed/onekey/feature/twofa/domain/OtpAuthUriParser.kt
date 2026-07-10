package com.roufsyed.onekey.feature.twofa.domain

import android.net.Uri
import com.roufsyed.onekey.core.domain.model.OtpAlgorithm
import com.roufsyed.onekey.core.domain.model.OtpParams
import com.roufsyed.onekey.core.domain.model.OtpType

/**
 * Result of parsing an `otpauth://` URI.
 *
 * [params] carries the full generation params (type, algorithm, digits, period, counter,
 * normalised secret). [issuer] and [account] are pulled out as separate fields because
 * the calling UI needs them for the suggested title and the credential's username, even
 * though they aren't part of the cryptographic params.
 */
data class ParsedOtpAuthUri(
    val params: OtpParams,
    val issuer: String,
    val account: String,
)

/**
 * Parses Google Authenticator's standard `otpauth://` URI format used by every issuer
 * that exposes a 2FA QR. Handles both `otpauth://totp/...` and `otpauth://hotp/...`,
 * full RFC 6238 / 4226 params (algorithm, digits, period, counter), and Steam Guard
 * auto-detection by issuer name.
 *
 * URI shape per the spec:
 *
 *     otpauth://TYPE/LABEL?PARAMETERS
 *
 *     TYPE       = "totp" or "hotp"
 *     LABEL      = "Issuer:Account"  (issuer prefix optional; account is required)
 *     PARAMETERS = secret=BASE32 [&issuer=...] [&algorithm=SHA1|SHA256|SHA512]
 *                  [&digits=6|7|8] [&period=30] [&counter=0]
 *
 * Robustness notes:
 *  - Returns `null` (rather than throwing) for any unparseable URI. The QR scanner
 *    treats null as "not a 2FA QR - try another," and the manual-entry sheet uses it
 *    to skip the auto-fill path when the secret field doesn't contain a URI.
 *  - Out-of-range digits / non-positive period collapse onto defaults rather than
 *    failing - pragmatic compatibility with mildly non-conforming issuers.
 *  - Unknown `algorithm` strings collapse to SHA-1 via [OtpAlgorithm.fromNameOrDefault].
 *  - The `secret` query parameter is normalised: trimmed, uppercased, padding stripped.
 *    [OtpParams]'s `init` block enforces the "non-empty" invariant after normalisation;
 *    a URI with an empty secret returns null.
 */
object OtpAuthUriParser {

    fun parse(rawUri: String): ParsedOtpAuthUri? = runCatching {
        val uri = Uri.parse(rawUri)
        if (uri.scheme != SCHEME) return@runCatching null

        val type = when (uri.host) {
            HOST_TOTP -> OtpType.TOTP
            HOST_HOTP -> OtpType.HOTP
            else -> return@runCatching null
        }

        val secret = uri.getQueryParameter("secret")
            ?.let(::normaliseSecret)
            ?.takeIf { it.isNotEmpty() }
            ?: return@runCatching null

        val (issuer, account) = parseLabel(uri)

        val isSteam = type == OtpType.TOTP && issuer.equals(STEAM_ISSUER, ignoreCase = true)
        val resolvedType = if (isSteam) OtpType.STEAM else type

        val digits = uri.getQueryParameter("digits")?.toIntOrNull()
            ?.takeIf { it in OtpParams.MIN_DIGITS..OtpParams.MAX_DIGITS }
            ?: OtpParams.DEFAULT_DIGITS
        val period = uri.getQueryParameter("period")?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: OtpParams.DEFAULT_PERIOD_SECONDS
        val algorithm = OtpAlgorithm.fromNameOrDefault(uri.getQueryParameter("algorithm"))
        // Counter is required by the spec for hotp URIs but a missing or malformed
        // value is common in hand-built test QRs; default to 0 rather than rejecting.
        val counter = if (resolvedType == OtpType.HOTP) {
            uri.getQueryParameter("counter")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        } else {
            0L
        }

        ParsedOtpAuthUri(
            params = OtpParams(
                type = resolvedType,
                secret = secret,
                algorithm = algorithm,
                digits = digits,
                period = period,
                counter = counter,
            ),
            issuer = issuer,
            account = account,
        )
    }.getOrNull()

    /**
     * Strip whitespace, uppercase, and remove `=` padding. The result is the canonical
     * form everything downstream compares and stores. Validators apply the same
     * normalisation so a manually-typed secret matches a QR-scanned one byte-for-byte.
     */
    private fun normaliseSecret(raw: String): String =
        raw.filterNot { it.isWhitespace() }.uppercase().trimEnd('=')

    /**
     * Pull issuer + account from the URI label. The label sits in the path
     * (`/Issuer:Account` or `/Account`) and may be percent-encoded, so we decode first.
     * When the label has no colon, fall back to the `issuer` query parameter so URIs
     * like `otpauth://totp/foo@bar?issuer=GitHub` still produce ("GitHub", "foo@bar").
     */
    private fun parseLabel(uri: Uri): Pair<String, String> {
        val rawLabel = uri.path?.trimStart('/').orEmpty()
        val decoded = Uri.decode(rawLabel)
        return if (':' in decoded) {
            val idx = decoded.indexOf(':')
            decoded.substring(0, idx).trim() to decoded.substring(idx + 1).trim()
        } else {
            val issuerParam = uri.getQueryParameter("issuer")?.trim().orEmpty()
            issuerParam to decoded.trim()
        }
    }

    private const val SCHEME = "otpauth"
    private const val HOST_TOTP = "totp"
    private const val HOST_HOTP = "hotp"
    private const val STEAM_ISSUER = "Steam"
}
