package com.roufsyed.onekey.core.domain.webauthn

import java.util.Base64

/**
 * PKCS#8 PEM, 64-column base64 body, as KeePassXC writes
 * `KPEX_PASSKEY_PRIVATE_KEY_PEM`. The armour is the generic "PRIVATE KEY" kind
 * for every algorithm; the algorithm lives inside the DER.
 */
object Pkcs8Pem {
    private const val HEADER = "-----BEGIN PRIVATE KEY-----"
    private const val FOOTER = "-----END PRIVATE KEY-----"

    fun encode(pkcs8: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs8)
        return "$HEADER\n$body\n$FOOTER\n"
    }

    /** Accepts the PEM form with or without the surrounding armour and any line breaks. */
    fun decode(pem: String): ByteArray {
        val body = pem.replace(HEADER, "").replace(FOOTER, "").filterNot { it.isWhitespace() }
        require(body.isNotEmpty()) { "empty PEM body" }
        return Base64.getDecoder().decode(body)
    }
}
