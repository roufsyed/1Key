package com.onekey.core.domain.model

/**
 * How quickly the vault locks after the app moves to the background.
 *
 * This is the security knob - the threat model is "someone picks up my unlocked phone
 * after I've put it down with the app open". Defaults to [IMMEDIATE].
 */
enum class BackgroundLockTimeout(val displayName: String, val millis: Long) {
    IMMEDIATE("Immediate", 0L),
    THIRTY_SECONDS("30 seconds", 30_000L),
    ONE_MINUTE("1 minute", 60_000L),
    FIVE_MINUTES("5 minutes", 300_000L),
}

/**
 * How long the vault stays unlocked while the app is in the foreground but receiving
 * no user input.
 *
 * This is the convenience knob - the threat model is "I walked away from the unlocked
 * phone with the app still open". [NEVER] disables the idle timer entirely (the
 * background timer still runs). Defaults to [FIVE_MINUTES].
 */
enum class InactivityLockTimeout(val displayName: String, val millis: Long, val isEnabled: Boolean = true) {
    NEVER("Never", -1L, isEnabled = false),
    THIRTY_SECONDS("30 seconds", 30_000L),
    ONE_MINUTE("1 minute", 60_000L),
    FIVE_MINUTES("5 minutes", 300_000L),
    FIFTEEN_MINUTES("15 minutes", 900_000L),
}
