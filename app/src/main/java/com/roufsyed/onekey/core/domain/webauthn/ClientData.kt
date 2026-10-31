package com.roufsyed.onekey.core.domain.webauthn

import java.security.MessageDigest

/**
 * Collected client data (WebAuthn Level 3 section 5.8.1) as the provider must
 * build it for a native-app caller. A privileged browser supplies its own
 * client data hash and the provider never sees or builds the JSON; a native
 * app has no client, so Android's provider contract makes the provider the
 * client and the origin becomes `android:apk-key-hash:<base64url SHA-256 of
 * the caller's signing certificate>`.
 *
 * The JSON is produced by hand so the output is deterministic and has no
 * dependency on an Android JSON library (keeps this testable on the JVM).
 */
object ClientData {
    const val TYPE_CREATE = "webauthn.create"
    const val TYPE_GET = "webauthn.get"
    const val ANDROID_APK_KEY_HASH_PREFIX = "android:apk-key-hash:"

    /** `android:apk-key-hash:` origin for a caller whose signing certificate has the given SHA-256. */
    fun apkKeyHashOrigin(certificateSha256: ByteArray): String =
        ANDROID_APK_KEY_HASH_PREFIX + Base64Url.encode(certificateSha256)

    /**
     * @param challengeBase64Url the challenge exactly as the relying party sent it (already base64url).
     * @param androidPackageName the calling package, included as `androidPackageName` per the
     *   Credential Manager provider guide; null for callers where it does not apply.
     */
    fun json(
        type: String,
        challengeBase64Url: String,
        origin: String,
        androidPackageName: String? = null,
    ): String {
        require(type == TYPE_CREATE || type == TYPE_GET) { "unknown client data type" }
        val sb = StringBuilder(128)
        sb.append("{\"type\":").append(quote(type))
        sb.append(",\"challenge\":").append(quote(challengeBase64Url))
        sb.append(",\"origin\":").append(quote(origin))
        if (androidPackageName != null) sb.append(",\"androidPackageName\":").append(quote(androidPackageName))
        sb.append('}')
        return sb.toString()
    }

    fun hash(clientDataJson: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(clientDataJson.toByteArray(Charsets.UTF_8))

    private fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2).append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
