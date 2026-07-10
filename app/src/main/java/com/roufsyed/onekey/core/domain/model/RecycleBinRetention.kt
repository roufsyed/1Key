package com.roufsyed.onekey.core.domain.model

/**
 * How long soft-deleted credentials are kept in the recycle bin before being purged
 * automatically on next vault unlock. [NEVER] disables auto-purge entirely; the user
 * must empty the bin manually.
 */
enum class RecycleBinRetention(val days: Int, val label: String) {
    ONE_WEEK(7, "1 week"),
    DAYS_30(30, "30 days"),
    SIX_MONTHS(180, "6 months"),
    ONE_YEAR(365, "1 year"),
    NEVER(-1, "Do not auto-clear");

    /** Cutoff window in milliseconds, or `null` when auto-purge is disabled. */
    val millis: Long?
        get() = if (this == NEVER) null else days * 24L * 60L * 60L * 1000L
}
