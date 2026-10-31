package com.roufsyed.onekey.core.domain.webauthn

import java.util.Base64

/**
 * WebAuthn's byte-string encoding: base64url without padding (RFC 4648
 * section 5). Used for challenges, credential IDs and user handles in
 * client data and in the JSON that Credential Manager exchanges with the
 * relying party. `java.util.Base64` is available from API 26, the app's
 * minSdk, so no Android dependency is needed and this stays testable on the
 * plain JVM.
 */
object Base64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** Accepts both padded and unpadded input; relying parties are inconsistent. */
    fun decode(text: String): ByteArray = decoder.decode(text.trimEnd('='))
}
