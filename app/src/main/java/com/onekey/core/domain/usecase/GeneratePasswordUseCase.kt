package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.PasswordConfig
import com.onekey.core.domain.model.PasswordStrength
import com.onekey.core.domain.model.PasswordType
import com.onekey.core.domain.wordlist.WordlistProvider
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log2

private const val UPPERCASE = "ABCDEFGHJKLMNPQRSTUVWXYZ"
private const val UPPERCASE_FULL = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val LOWERCASE = "abcdefghjkmnpqrstuvwxyz"
private const val LOWERCASE_FULL = "abcdefghijklmnopqrstuvwxyz"
private const val DIGITS = "23456789"
private const val DIGITS_FULL = "0123456789"
private const val SYMBOLS = "!@#\$%^&*-_=+?"

data class GeneratedPassword(val password: String, val strength: PasswordStrength)

@Singleton
class GeneratePasswordUseCase @Inject constructor(
    private val wordlistProvider: WordlistProvider,
) {
    private val rng = SecureRandom()

    private val wordlist: List<String> get() = wordlistProvider.memorableWordlist()

    operator fun invoke(config: PasswordConfig): GeneratedPassword {
        val password = when (config.type) {
            PasswordType.RANDOM -> generateRandom(config)
            PasswordType.MEMORABLE -> generateMemorable(config)
            PasswordType.PIN -> generatePin(config)
        }
        return GeneratedPassword(password, strength(password, config))
    }

    private fun generateRandom(config: PasswordConfig): String {
        val upper = if (config.avoidAmbiguous) UPPERCASE else UPPERCASE_FULL
        val lower = if (config.avoidAmbiguous) LOWERCASE else LOWERCASE_FULL
        val num = if (config.avoidAmbiguous) DIGITS else DIGITS_FULL

        val pool = buildString {
            if (config.uppercase) append(upper)
            if (config.lowercase) append(lower)
            if (config.digits) append(num)
            if (config.symbols) append(SYMBOLS)
        }.ifEmpty { LOWERCASE_FULL }

        val length = config.length.coerceIn(4, 128)
        val buf = CharArray(length)
        var cursor = 0

        // Guarantee at least one char from each enabled set
        if (config.uppercase && cursor < length) buf[cursor++] = upper.secureRandom()
        if (config.lowercase && cursor < length) buf[cursor++] = lower.secureRandom()
        if (config.digits && cursor < length) buf[cursor++] = num.secureRandom()
        if (config.symbols && cursor < length) buf[cursor++] = SYMBOLS.secureRandom()

        // Fill the rest from the full pool
        for (i in cursor until length) buf[i] = pool.secureRandom()

        fisherYates(buf)
        val result = String(buf)
        buf.fill('\u0000')
        return result
    }

    private fun generateMemorable(config: PasswordConfig): String {
        val count = config.wordCount.coerceIn(2, 10)
        return (1..count).joinToString("-") { wordlist[rng.nextInt(wordlist.size)].lowercase() }
    }

    private fun generatePin(config: PasswordConfig): String {
        val len = config.pinLength.coerceIn(4, 12)
        val buf = CharArray(len) { DIGITS_FULL[rng.nextInt(DIGITS_FULL.length)] }
        val result = String(buf)
        buf.fill('\u0000')
        return result
    }

    private fun fisherYates(arr: CharArray) {
        for (i in arr.lastIndex downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp
        }
    }

    private fun String.secureRandom(): Char = this[rng.nextInt(length)]

    private fun strength(password: String, config: PasswordConfig): PasswordStrength {
        val entropy = when (config.type) {
            PasswordType.PIN -> password.length * log2(10.0)
            PasswordType.MEMORABLE -> {
                config.wordCount * log2(wordlist.size.coerceAtLeast(2).toDouble())
            }
            PasswordType.RANDOM -> {
                val upper = if (config.avoidAmbiguous) UPPERCASE else UPPERCASE_FULL
                val lower = if (config.avoidAmbiguous) LOWERCASE else LOWERCASE_FULL
                val num = if (config.avoidAmbiguous) DIGITS else DIGITS_FULL
                val poolSize = buildString {
                    if (config.uppercase) append(upper)
                    if (config.lowercase) append(lower)
                    if (config.digits) append(num)
                    if (config.symbols) append(SYMBOLS)
                }.length.coerceAtLeast(1)
                password.length * log2(poolSize.toDouble())
            }
        }
        return when {
            entropy < 36 -> PasswordStrength.WEAK
            entropy < 60 -> PasswordStrength.FAIR
            entropy < 80 -> PasswordStrength.STRONG
            else -> PasswordStrength.VERY_STRONG
        }
    }
}
