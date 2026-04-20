package com.onekey.core.domain.model

enum class PasswordType { RANDOM, MEMORABLE, PIN }

enum class PasswordStrength(val label: String, val fraction: Float) {
    WEAK("Weak", 0.25f),
    FAIR("Fair", 0.50f),
    STRONG("Strong", 0.75f),
    VERY_STRONG("Very Strong", 1.00f),
}

data class PasswordConfig(
    val type: PasswordType = PasswordType.RANDOM,
    val length: Int = 16,
    val wordCount: Int = 4,
    val pinLength: Int = 6,
    val uppercase: Boolean = true,
    val lowercase: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    val avoidAmbiguous: Boolean = false,
)
