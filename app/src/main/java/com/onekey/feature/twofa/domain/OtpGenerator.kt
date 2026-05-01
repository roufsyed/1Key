package com.onekey.feature.twofa.domain

import com.onekey.core.domain.model.OtpAlgorithm
import com.onekey.core.domain.model.OtpParams
import com.onekey.core.domain.model.OtpType
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * Derives one-time-password codes per RFC 4226 (HOTP), RFC 6238 (TOTP), and Steam's
 * proprietary alphabet variant. The single entry point [generate] dispatches on
 * [OtpParams.type], so callers stay agnostic to whether an entry is time-based,
 * counter-based, or Steam-shaped.
 *
 * Stateless: every call decodes the secret fresh and allocates a [Mac] instance.
 * That's deliberate — a cache keyed by secret would have to invalidate on user-driven
 * secret changes and survive multi-thread access; the per-generate cost (microseconds)
 * isn't worth that complexity. If profiling ever shows otherwise, a per-secret cache
 * fits cleanly here without changing the public surface.
 *
 * Inputs are trusted: [OtpParams]'s `init` block enforces digits ∈ 6..8 and period > 0,
 * and the [OtpAlgorithm] enum bounds the algorithm string to one Android guarantees.
 * The only crash surface left is a malformed base32 secret, which the validators run
 * at parse boundaries (URI parser, manual-entry validator) catch before construction.
 */
@Singleton
class OtpGenerator @Inject constructor() {

    /**
     * @param timeMillis system time used for TOTP/STEAM counter derivation. Ignored
     * for HOTP. Caller-supplied for testability — production calls take the default.
     */
    fun generate(params: OtpParams, timeMillis: Long = System.currentTimeMillis()): OtpCode {
        val counter = when (params.type) {
            OtpType.HOTP -> params.counter
            OtpType.TOTP, OtpType.STEAM -> timeMillis / MILLIS_PER_SECOND / params.period
        }
        val key = base32Decode(params.secret)
        val truncated = hotpTruncate(key, counter, params.algorithm)
        val code = when (params.type) {
            OtpType.STEAM -> SteamAlphabet.encode(truncated)
            OtpType.TOTP, OtpType.HOTP -> formatDigits(truncated, params.digits)
        }
        val (remainingSeconds, progress) = when (params.type) {
            OtpType.HOTP -> null to null
            OtpType.TOTP, OtpType.STEAM -> {
                val elapsed = (timeMillis / MILLIS_PER_SECOND) % params.period
                val remaining = (params.period - elapsed).toInt()
                remaining to (remaining / params.period.toFloat())
            }
        }
        return OtpCode(code = code, remainingSeconds = remainingSeconds, progress = progress)
    }

    /**
     * Milliseconds until the current TOTP/STEAM rotation window ends. Used by UI
     * timers that align their tick to the rollover. Undefined for HOTP — caller
     * shouldn't ask.
     */
    fun getRemainingMillis(periodSeconds: Long, timeMillis: Long = System.currentTimeMillis()): Long {
        require(periodSeconds > 0L) { "periodSeconds must be > 0, was $periodSeconds" }
        val elapsed = (timeMillis / MILLIS_PER_SECOND) % periodSeconds
        return (periodSeconds - elapsed) * MILLIS_PER_SECOND
    }

    /** RFC 4226 §5.3 dynamic truncation, parameterised on the HMAC choice. */
    private fun hotpTruncate(key: ByteArray, counter: Long, algorithm: OtpAlgorithm): Int {
        val msg = ByteArray(8) { i -> (counter shr ((7 - i) * 8) and 0xFF).toByte() }
        val mac = Mac.getInstance(algorithm.javaName)
        mac.init(SecretKeySpec(key, algorithm.javaName))
        val hash = mac.doFinal(msg)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        return ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)
    }

    private fun formatDigits(truncated: Int, digits: Int): String {
        val mod = 10.0.pow(digits).toInt()
        return (truncated % mod).toString().padStart(digits, '0')
    }

    private fun base32Decode(input: String): ByteArray {
        val clean = input.uppercase().trimEnd(BASE32_PADDING).filter { it in BASE32_ALPHABET }
        var bits = 0
        var buffer = 0
        val output = mutableListOf<Byte>()
        for (ch in clean) {
            buffer = (buffer shl 5) or BASE32_ALPHABET.indexOf(ch)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                output.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return output.toByteArray()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        const val BASE32_PADDING = '='
    }
}

/**
 * The generator's output. [remainingSeconds] and [progress] are non-null for
 * time-based variants (TOTP, STEAM) and null for HOTP — HOTP advances per tap,
 * not per second, so a "remaining" value is meaningless. Forcing UI to handle
 * the null branches makes "don't show a timer for HOTP" structurally enforced
 * rather than relying on convention.
 */
data class OtpCode(
    val code: String,
    val remainingSeconds: Int?,
    val progress: Float?,
)
