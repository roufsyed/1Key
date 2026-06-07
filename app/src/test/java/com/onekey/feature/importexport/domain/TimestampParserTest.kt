package com.onekey.feature.importexport.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimestampParserTest {

    // 2024-05-06T13:34:54Z - reference instant the numeric tests pivot around.
    private val REFERENCE_MILLIS = 1715002494000L

    // ── Numeric epoch (with magnitude-based unit detection) ─────────────────

    @Test fun seconds_as_long_string() {
        assertEquals(REFERENCE_MILLIS, TimestampParser.parseToEpochMillis("1715002494"))
    }

    @Test fun seconds_as_number() {
        assertEquals(REFERENCE_MILLIS, TimestampParser.parseToEpochMillis(1715002494L))
        assertEquals(REFERENCE_MILLIS, TimestampParser.parseToEpochMillis(1715002494.0))
    }

    @Test fun milliseconds_as_string() {
        assertEquals(1715002494773L, TimestampParser.parseToEpochMillis("1715002494773"))
    }

    @Test fun microseconds_truncate_to_millis() {
        assertEquals(1715002494773L, TimestampParser.parseToEpochMillis("1715002494773123"))
    }

    @Test fun nanoseconds_truncate_to_millis() {
        assertEquals(1715002494773L, TimestampParser.parseToEpochMillis("1715002494773123456"))
    }

    @Test fun scientific_notation_is_seconds() {
        assertEquals(REFERENCE_MILLIS, TimestampParser.parseToEpochMillis("1.715002494E9"))
    }

    // ── ISO 8601 ────────────────────────────────────────────────────────────

    @Test fun iso_instant_with_z() {
        assertEquals(REFERENCE_MILLIS, TimestampParser.parseToEpochMillis("2024-05-06T13:34:54Z"))
    }

    @Test fun iso_instant_with_millis() {
        assertEquals(1715002494773L, TimestampParser.parseToEpochMillis("2024-05-06T13:34:54.773Z"))
    }

    @Test fun iso_with_positive_offset() {
        assertEquals(REFERENCE_MILLIS, TimestampParser.parseToEpochMillis("2024-05-06T19:04:54+05:30"))
    }

    @Test fun iso_with_named_zone() {
        assertEquals(
            REFERENCE_MILLIS,
            TimestampParser.parseToEpochMillis("2024-05-06T19:04:54+05:30[Asia/Kolkata]"),
        )
    }

    // ── RFC 1123 ────────────────────────────────────────────────────────────

    @Test fun rfc_1123_http_date() {
        assertEquals(
            REFERENCE_MILLIS,
            TimestampParser.parseToEpochMillis("Mon, 06 May 2024 13:34:54 GMT"),
        )
    }

    // ── SQL / log ───────────────────────────────────────────────────────────

    @Test fun sql_datetime_no_millis() {
        assertEquals(REFERENCE_MILLIS, TimestampParser.parseToEpochMillis("2024-05-06 13:34:54"))
    }

    @Test fun sql_datetime_with_millis() {
        assertEquals(1715002494773L, TimestampParser.parseToEpochMillis("2024-05-06 13:34:54.773"))
    }

    @Test fun log_format_with_comma_millis() {
        assertEquals(1715002494773L, TimestampParser.parseToEpochMillis("2024-05-06 13:34:54,773"))
    }

    // ── Bare ISO date ───────────────────────────────────────────────────────

    @Test fun bare_iso_date_is_start_of_day_utc() {
        assertEquals(1714953600000L, TimestampParser.parseToEpochMillis("2024-05-06"))
    }

    // ── Whitespace tolerance ────────────────────────────────────────────────

    @Test fun leading_and_trailing_whitespace_is_trimmed() {
        assertEquals(REFERENCE_MILLIS, TimestampParser.parseToEpochMillis("  1715002494  "))
        assertEquals(REFERENCE_MILLIS, TimestampParser.parseToEpochMillis("\t2024-05-06T13:34:54Z\n"))
    }

    // ── Refusals (deliberate) ───────────────────────────────────────────────

    @Test fun null_input_returns_null() {
        assertNull(TimestampParser.parseToEpochMillis(null))
    }

    @Test fun empty_string_returns_null() {
        assertNull(TimestampParser.parseToEpochMillis(""))
        assertNull(TimestampParser.parseToEpochMillis("   "))
    }

    @Test fun unsupported_type_returns_null() {
        assertNull(TimestampParser.parseToEpochMillis(true))
        assertNull(TimestampParser.parseToEpochMillis(listOf(1, 2, 3)))
    }

    @Test fun garbage_returns_null() {
        assertNull(TimestampParser.parseToEpochMillis("not a date"))
        assertNull(TimestampParser.parseToEpochMillis("2025-13-45"))
    }

    @Test fun excel_serial_is_rejected_as_too_small() {
        assertNull(TimestampParser.parseToEpochMillis("45784"))
    }

    @Test fun compact_yyyymmdd_is_rejected_as_too_small() {
        assertNull(TimestampParser.parseToEpochMillis("20250507"))
    }

    @Test fun regional_slash_date_is_rejected() {
        assertNull(TimestampParser.parseToEpochMillis("05/07/2025"))
    }

    @Test fun java_date_to_string_is_rejected() {
        assertNull(TimestampParser.parseToEpochMillis("Mon May 06 13:34:54 IST 2024"))
    }

    @Test fun zero_and_negative_are_rejected() {
        assertNull(TimestampParser.parseToEpochMillis("0"))
        assertNull(TimestampParser.parseToEpochMillis("-1"))
        assertNull(TimestampParser.parseToEpochMillis(0))
        assertNull(TimestampParser.parseToEpochMillis(-1L))
    }
}
