package com.onekey.core.domain.model

enum class LockTimeout(val displayName: String, val millis: Long) {
    IMMEDIATE("Immediate", 0L),
    ONE_MINUTE("1 minute", 60_000L),
    TWO_MINUTES("2 minutes", 120_000L),
    FIVE_MINUTES("5 minutes", 300_000L),
}
