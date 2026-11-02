package com.roufsyed.onekey.feature.importexport.domain.keepass

import java.util.Base64

/**
 * Decodes identifiers written by other password managers, which do not agree
 * on an alphabet: WebAuthn says base64url without padding, some tools write
 * standard base64 with padding. Tries base64url first, then standard; returns
 * null rather than guessing when the text is neither.
 */
internal object LenientBase64 {
    fun decode(text: String?): ByteArray? {
        val body = text?.trim()?.trimEnd('=') ?: return null
        if (body.isEmpty()) return null
        runCatching { Base64.getUrlDecoder().decode(body) }.getOrNull()?.let { return it }
        return runCatching { Base64.getDecoder().decode(body) }.getOrNull()
    }
}
