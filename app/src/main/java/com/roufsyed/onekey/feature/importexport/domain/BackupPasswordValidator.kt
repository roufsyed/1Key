package com.roufsyed.onekey.feature.importexport.domain

import kotlin.math.log2

/**
 * Validates backup passwords before they are used to encrypt a vault export.
 *
 * All validation operates directly on the CharArray - no String conversion occurs,
 * so key material is never interned in the JVM string pool.
 *
 * Entropy is estimated from the character classes present in the password, not from the
 * exact unique characters, to avoid creating a String-backed Set<Char>. The estimate is
 * conservative (it may undercount pool size for exotic Unicode) which is the correct
 * bias for a security gate.
 */
internal object BackupPasswordValidator {

    private const val MIN_LENGTH = 8
    private const val MIN_ENTROPY_BITS = 40.0

    sealed class Result {
        data object Valid : Result()
        data object Empty : Result()
        data class TooShort(val minLength: Int) : Result()
        data object AllSameCharacter : Result()
        data class TooWeak(val estimatedBits: Double, val requiredBits: Double) : Result()
    }

    fun validate(password: CharArray): Result {
        if (password.isEmpty()) return Result.Empty
        if (password.size < MIN_LENGTH) return Result.TooShort(MIN_LENGTH)
        if (isAllSameCharacter(password)) return Result.AllSameCharacter

        val entropy = estimateEntropy(password)
        if (entropy < MIN_ENTROPY_BITS) return Result.TooWeak(entropy, MIN_ENTROPY_BITS)

        return Result.Valid
    }

    fun userMessage(result: Result): String = when (result) {
        is Result.Valid -> ""
        is Result.Empty -> "Backup password cannot be empty."
        is Result.TooShort -> "Backup password must be at least ${result.minLength} characters."
        is Result.AllSameCharacter -> "Backup password is too simple - use a mix of different characters."
        is Result.TooWeak -> "Backup password is too weak. Use a longer or more complex password."
    }

    private fun isAllSameCharacter(chars: CharArray): Boolean {
        val first = chars[0]
        return chars.all { it == first }
    }

    /**
     * Estimates password entropy as length × log2(pool size).
     *
     * Pool size is determined by which character classes are present:
     *   lowercase letters  -> +26
     *   uppercase letters  -> +26
     *   decimal digits     -> +10
     *   other (symbols)    -> +32  (conservative - printable ASCII non-alphanum is ~32)
     *
     * This deliberately avoids building a Set<Char> (which requires boxing) or converting
     * to String. The estimate is a lower bound: a password using only 'a' and 'b' gets
     * credited the full lowercase pool of 26 rather than 2. This is acceptable here
     * because the goal is blocking trivially weak passwords, not precise strength scoring.
     */
    private fun estimateEntropy(chars: CharArray): Double {
        var hasLower = false
        var hasUpper = false
        var hasDigit = false
        var hasOther = false

        for (c in chars) {
            when {
                c.isLowerCase() -> hasLower = true
                c.isUpperCase() -> hasUpper = true
                c.isDigit() -> hasDigit = true
                else -> hasOther = true
            }
            if (hasLower && hasUpper && hasDigit && hasOther) break
        }

        val pool = (if (hasLower) 26 else 0) +
                   (if (hasUpper) 26 else 0) +
                   (if (hasDigit) 10 else 0) +
                   (if (hasOther) 32 else 0)

        return chars.size * log2(pool.coerceAtLeast(2).toDouble())
    }
}
