package com.onekey.feature.importexport.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Normalises whatever timestamp shape an import file gives us into Unix
 * milliseconds since 1970 UTC - the contract every column in our schema
 * (`created_at`, `updated_at`, `deleted_at`, `accessed_at`) actually stores.
 *
 * Handled formats:
 *  - Numeric epoch (Number or numeric String). The unit is detected by
 *    magnitude:
 *      - `1e8 ..< 1e11`  → seconds (≈ year 1973–5138)
 *      - `1e11 ..< 1e14` → milliseconds
 *      - `1e14 ..< 1e17` → microseconds
 *      - `1e17 ..< 1e20` → nanoseconds
 *  - ISO 8601 instant (`2025-05-07T18:30:00Z`)
 *  - ISO 8601 with offset (`2025-05-07T18:30:00+05:30`)
 *  - RFC 1123 / HTTP-date (`Wed, 07 May 2025 18:30:00 GMT`)
 *  - SQL DATETIME (`2025-05-07 18:30:00` with optional `.SSS`)
 *  - Log format (`2025-05-07 18:30:00,123`)
 *  - Bare ISO date (`2025-05-07`)
 *
 * Deliberately not detected (silently-wrong-data risk):
 *  - Regional `MM/DD/YYYY` / `DD/MM/YYYY` - locale-ambiguous.
 *  - Excel serial dates - collide with arbitrary 5-digit numbers.
 *  - Compact `YYYYMMDD` - collides with 8-digit integers.
 *  - Java `Date.toString()` - ambiguous timezone abbreviations (`IST`, `CST`…).
 *
 * Unrecognised input returns `null`; callers fall back to
 * `System.currentTimeMillis()`.
 *
 * Naked datetimes without an offset (SQL, log, ISO date) are interpreted
 * as UTC. Local-time interpretation would require knowing the source
 * system's zone, which an import file doesn't carry.
 */
object TimestampParser {

    private val SQL_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]")
    private val LOG_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS")

    fun parseToEpochMillis(input: Any?): Long? = when (input) {
        null -> null
        is Number -> normalizeNumeric(input.toDouble())
        is String -> parseString(input)
        else -> null
    }

    private fun parseString(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null

        // Numeric first - covers "1715002494", "1715002494773.0", "1.71E+09".
        s.toDoubleOrNull()?.let { return normalizeNumeric(it) }

        // ISO 8601 instant: "2025-05-07T18:30:00Z" / with millis / nanos.
        runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()?.let { return it }

        // ISO 8601 with offset: "2025-05-07T18:30:00+05:30".
        runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .getOrNull()?.let { return it }

        // ISO 8601 with named zone: "2025-05-07T18:30:00+05:30[Asia/Kolkata]".
        runCatching { ZonedDateTime.parse(s).toInstant().toEpochMilli() }
            .getOrNull()?.let { return it }

        // RFC 1123 (HTTP headers): "Wed, 07 May 2025 18:30:00 GMT".
        runCatching {
            ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }

        // Log format: "2025-05-07 18:30:00,123". Try before SQL because the
        // optional millis pattern in SQL_DATETIME doesn't accept the comma.
        runCatching {
            LocalDateTime.parse(s, LOG_DATETIME).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()?.let { return it }

        // SQL DATETIME: "2025-05-07 18:30:00" (optionally ".SSS").
        runCatching {
            LocalDateTime.parse(s, SQL_DATETIME).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()?.let { return it }

        // Bare date: "2025-05-07" → start of day UTC.
        runCatching {
            LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }

        return null
    }

    private fun normalizeNumeric(value: Double): Long? {
        if (!value.isFinite() || value <= 0.0) return null
        return when {
            value < 1e8 -> null               // pre-1973-in-seconds - almost certainly not a timestamp
            value < 1e11 -> (value * 1000).toLong()       // seconds → ms
            value < 1e14 -> value.toLong()                // already ms
            value < 1e17 -> (value / 1000).toLong()       // μs → ms
            value < 1e20 -> (value / 1_000_000).toLong()  // ns → ms
            else -> null
        }
    }
}
