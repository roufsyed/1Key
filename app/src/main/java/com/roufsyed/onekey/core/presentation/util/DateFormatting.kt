package com.roufsyed.onekey.core.presentation.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
private val dateOnlyFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

fun Long.toFormattedDateTime(): String =
    Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(dateTimeFormatter)

/** Date only, e.g. "Nov 1, 2026"; for compact rows where the time adds nothing. */
fun Long.toFormattedDate(): String =
    Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(dateOnlyFormatter)

fun Long.toRelativeTime(): String {
    val now = System.currentTimeMillis()
    val diff = now - this
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000}h ago"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000}d ago"
        else -> Instant.ofEpochMilli(this)
            .atZone(ZoneId.systemDefault())
            .format(dateOnlyFormatter)
    }
}
