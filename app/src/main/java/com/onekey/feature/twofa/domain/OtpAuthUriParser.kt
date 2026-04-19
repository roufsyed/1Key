package com.onekey.feature.twofa.domain

import android.net.Uri

data class OtpAuthParams(
    val secret: String,
    val issuer: String,
    val account: String,
)

object OtpAuthUriParser {

    fun parse(rawUri: String): OtpAuthParams? = runCatching {
        val uri = Uri.parse(rawUri)
        if (uri.scheme != "otpauth" || uri.host != "totp") return null

        val secret = uri.getQueryParameter("secret")
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() } ?: return null

        // Label sits in the path: /ISSUER:ACCOUNT or /ACCOUNT
        val rawLabel = uri.path?.trimStart('/').orEmpty()
        val decoded = Uri.decode(rawLabel)

        val (issuer, account) = if (':' in decoded) {
            val idx = decoded.indexOf(':')
            decoded.substring(0, idx).trim() to decoded.substring(idx + 1).trim()
        } else {
            // Fall back to the issuer query param when the label has no colon.
            val issuerParam = uri.getQueryParameter("issuer")?.trim().orEmpty()
            issuerParam to decoded.trim()
        }

        OtpAuthParams(secret = secret, issuer = issuer, account = account)
    }.getOrNull()
}
