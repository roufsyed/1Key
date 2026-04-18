package com.onekey.feature.twofa.domain

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * RFC 6238 TOTP implementation.
 * Uses HMAC-SHA1 (most authenticator apps) with 30-second windows.
 */
@Singleton
class TotpGenerator @Inject constructor() {

    companion object {
        private const val DIGITS = 6
        private const val PERIOD = 30L
        private const val ALGORITHM = "HmacSHA1"
    }

    data class TotpCode(
        val code: String,
        val remainingSeconds: Int,
        val progress: Float,          // 0f..1f for CountdownIndicator
    )

    fun generate(base32Secret: String, timeMillis: Long = System.currentTimeMillis()): TotpCode {
        val key = base32Decode(base32Secret)
        val counter = timeMillis / 1000 / PERIOD
        val elapsed = (timeMillis / 1000) % PERIOD
        val remaining = (PERIOD - elapsed).toInt()

        val code = hotp(key, counter)
        return TotpCode(
            code = code.toString().padStart(DIGITS, '0'),
            remainingSeconds = remaining,
            progress = remaining / PERIOD.toFloat(),
        )
    }

    fun getRemainingMillis(timeMillis: Long = System.currentTimeMillis()): Long {
        val elapsed = (timeMillis / 1000) % PERIOD
        return (PERIOD - elapsed) * 1000
    }

    private fun hotp(key: ByteArray, counter: Long): Int {
        val msg = ByteArray(8) { i -> (counter shr ((7 - i) * 8) and 0xFF).toByte() }
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        val hash = mac.doFinal(msg)

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)

        return binary % 10.0.pow(DIGITS).toInt()
    }

    private fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = input.uppercase().trimEnd('=').filter { it in alphabet }
        var bits = 0
        var buffer = 0
        val output = mutableListOf<Byte>()
        for (ch in clean) {
            buffer = (buffer shl 5) or alphabet.indexOf(ch)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                output.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return output.toByteArray()
    }
}
