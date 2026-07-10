package com.roufsyed.onekey.feature.importexport.presentation.screen

import com.roufsyed.onekey.core.domain.usecase.ExportFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Unit tests for [defaultExportFilename]. The format string is asserted directly so a
 * future locale-sensitive refactor of [java.text.SimpleDateFormat] is caught - we
 * deliberately pin Locale.US so the file picker presents identical names on every
 * device. The clock parameter is supplied explicitly so test output is deterministic.
 */
class BackupExportFilenameTest {

    private val defaultTimeZone = TimeZone.getDefault()

    @Before
    fun pinTimezone() {
        // SimpleDateFormat without an explicit zone uses the default. Pin to UTC so the
        // assertions below match regardless of the developer's or CI machine's locale.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimezone() {
        TimeZone.setDefault(defaultTimeZone)
    }

    // ── Reference instant: 2026-06-11 20:46:00 UTC ──────────────────────────────

    private val referenceMillis: Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse("2026-06-11 20:46:00")!!
            .time

    // ── Extensions ──────────────────────────────────────────────────────────────

    @Test
    fun encrypted_export_uses_1key_extension() {
        val name = defaultExportFilename(
            encrypted = true,
            format = ExportFormat.JSON,
            nowMillis = referenceMillis,
        )
        assertEquals("1key_backup_2026-06-11_2046.1key", name)
    }

    @Test
    fun encrypted_export_ignores_format_for_extension() {
        // The container envelope is always .1key regardless of the inner payload's
        // serialisation. The CSV-vs-JSON distinction is captured inside the envelope.
        val nameJson = defaultExportFilename(true, ExportFormat.JSON, referenceMillis)
        val nameCsv = defaultExportFilename(true, ExportFormat.CSV, referenceMillis)
        assertEquals(nameJson, nameCsv)
        assertTrue(nameJson.endsWith(".1key"))
    }

    @Test
    fun plain_json_export_uses_lowercase_json_extension() {
        val name = defaultExportFilename(
            encrypted = false,
            format = ExportFormat.JSON,
            nowMillis = referenceMillis,
        )
        assertEquals("1key_backup_2026-06-11_2046.json", name)
    }

    @Test
    fun plain_csv_export_uses_lowercase_csv_extension() {
        val name = defaultExportFilename(
            encrypted = false,
            format = ExportFormat.CSV,
            nowMillis = referenceMillis,
        )
        assertEquals("1key_backup_2026-06-11_2046.csv", name)
    }

    // ── Timestamp shape ─────────────────────────────────────────────────────────

    @Test
    fun timestamp_uses_yyyy_mm_dd_underscore_hhmm() {
        // Regex: literal "1key_backup_", then YYYY-MM-DD_HHMM, then a dot and extension.
        val pattern = Regex("^1key_backup_\\d{4}-\\d{2}-\\d{2}_\\d{2}\\d{2}\\.[a-z0-9]+$")
        val now = System.currentTimeMillis()
        listOf(
            defaultExportFilename(true, ExportFormat.JSON, now),
            defaultExportFilename(false, ExportFormat.JSON, now),
            defaultExportFilename(false, ExportFormat.CSV, now),
        ).forEach { name ->
            assertTrue(
                "Expected $name to match $pattern",
                pattern.matches(name),
            )
        }
    }

    @Test
    fun timestamp_is_zero_padded_for_single_digit_components() {
        // 2026-01-02 03:04 UTC - every component is a single digit before padding.
        val singleDigitMillis = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse("2026-01-02 03:04:00")!!
            .time
        val name = defaultExportFilename(
            encrypted = true,
            format = ExportFormat.JSON,
            nowMillis = singleDigitMillis,
        )
        assertEquals("1key_backup_2026-01-02_0304.1key", name)
    }

    @Test
    fun different_instants_produce_different_filenames() {
        // The minute resolution is enough to keep back-to-back manual exports from
        // colliding under normal user interaction. Two events one minute apart must
        // produce distinct names.
        val later = referenceMillis + 60_000L
        val a = defaultExportFilename(true, ExportFormat.JSON, referenceMillis)
        val b = defaultExportFilename(true, ExportFormat.JSON, later)
        assertEquals("1key_backup_2026-06-11_2046.1key", a)
        assertEquals("1key_backup_2026-06-11_2047.1key", b)
    }

    @Test
    fun filename_does_not_collide_with_sync_engine_final_filename() {
        // The bug we are fixing is that the manual encrypted export's filename was
        // a near-twin of the sync engine's "vault-backup.1key" once dropped into the
        // same folder. Pin the contract: the manual name must NEVER equal the sync
        // name regardless of clock or format.
        val now = System.currentTimeMillis()
        val syncName = "vault-backup.1key"
        listOf(
            defaultExportFilename(true, ExportFormat.JSON, now),
            defaultExportFilename(true, ExportFormat.CSV, now),
            defaultExportFilename(false, ExportFormat.JSON, now),
            defaultExportFilename(false, ExportFormat.CSV, now),
        ).forEach { name ->
            assertTrue(
                "Manual export filename '$name' must not equal sync filename '$syncName'",
                name != syncName,
            )
        }
    }
}
