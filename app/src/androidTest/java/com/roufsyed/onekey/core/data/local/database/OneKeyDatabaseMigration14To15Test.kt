package com.roufsyed.onekey.core.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that bumping the schema 14 -> 15 (which adds the nullable
 * `imported_at` column to `credentials`) is non-destructive:
 *
 *  - The post-migration schema matches `15.json` (Room's TableInfo validation
 *    via [MigrationTestHelper.runMigrationsAndValidate]).
 *  - Existing rows have `imported_at = NULL` (the migration ships no backfill;
 *    pre-v15 entries pre-date import tracking by definition).
 *  - Every encrypted column on every old row is byte-identical to its pre-
 *    migration value. An additive ALTER TABLE must not touch existing bytes
 * - if the BLOBs change, the AAD-bound AES-GCM decrypt will fail on
 *    subsequent unlock and the user's vault is unreadable. Asserting bit
 *    identity here is the cheapest tripwire against that worst-case bug.
 *
 * Uses synthesized fake blobs (deterministic byte patterns) rather than real
 * crypto output - we're testing the migration's behaviour on the DB engine,
 * not the cipher.
 */
@RunWith(AndroidJUnit4::class)
class OneKeyDatabaseMigration14To15Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OneKeyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /** Three rows with deterministic, distinguishable blob/text payloads. */
    private data class FixtureRow(
        val id: String,
        val title: String,
        val usernameEncrypted: ByteArray,
        val ivUsername: ByteArray,
        val passwordEncrypted: ByteArray,
        val ivPassword: ByteArray,
        val notesEncrypted: ByteArray,
        val ivNotes: ByteArray,
        val totpSecretEncrypted: ByteArray?,
        val ivTotp: ByteArray?,
        val urlEncrypted: ByteArray?,
        val ivUrl: ByteArray?,
        val titleEncrypted: ByteArray?,
        val ivTitle: ByteArray?,
        val createdAt: Long,
        val updatedAt: Long,
        val accessedAt: Long?,
    )

    private val fixtures = listOf(
        fixture(seed = 1, accessedAt = 1_700_000_001_000L, hasOtp = true,  hasTitleCt = true),
        fixture(seed = 2, accessedAt = null,                hasOtp = false, hasTitleCt = true),
        fixture(seed = 3, accessedAt = 1_700_000_003_000L, hasOtp = true,  hasTitleCt = false),
    )

    @Test
    fun migrate14To15_addsImportedAtColumn_preservesExistingRows() {
        // ── 1. Seed a v14 DB with three rows. ────────────────────────────────
        helper.createDatabase(TEST_DB, /* version = */ 14).use { db ->
            fixtures.forEach { row -> insertV14Row(db, row) }
        }

        // ── 2. Apply MIGRATION_14_15 and validate logical schema matches 15.json. ──
        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            /* version = */ 15,
            /* validateDroppedTables = */ true,
            MIGRATION_14_15,
        )

        // ── 3. Re-read every column on every row and verify byte / value identity. ──
        migrated.query(
            """
            SELECT id, title, title_encrypted, iv_title,
                   username_encrypted, iv_username,
                   password_encrypted, iv_password,
                   notes_encrypted, iv_notes,
                   totp_secret_encrypted, iv_totp,
                   url_encrypted, iv_url,
                   created_at, updated_at, accessed_at, imported_at
            FROM credentials
            ORDER BY id
            """.trimIndent()
        ).use { cursor ->
            assertEquals(
                "expected exactly ${fixtures.size} rows after migration",
                fixtures.size,
                cursor.count,
            )
            val expectedById = fixtures.associateBy { it.id }
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val expected = expectedById.getValue(id)

                // Plaintext columns (title is the legacy plaintext, retained verbatim).
                assertEquals("title plaintext preserved for $id", expected.title, cursor.getString(1))

                // Encrypted columns + IVs - the heart of the assertion. Any byte
                // change here would break AES-GCM authentication on read.
                assertBytesEqual("title_encrypted", expected.titleEncrypted, cursor.getBlobOrNull(2))
                assertBytesEqual("iv_title", expected.ivTitle, cursor.getBlobOrNull(3))
                assertBytesEqual("username_encrypted", expected.usernameEncrypted, cursor.getBlob(4))
                assertBytesEqual("iv_username", expected.ivUsername, cursor.getBlob(5))
                assertBytesEqual("password_encrypted", expected.passwordEncrypted, cursor.getBlob(6))
                assertBytesEqual("iv_password", expected.ivPassword, cursor.getBlob(7))
                assertBytesEqual("notes_encrypted", expected.notesEncrypted, cursor.getBlob(8))
                assertBytesEqual("iv_notes", expected.ivNotes, cursor.getBlob(9))
                assertBytesEqual("totp_secret_encrypted", expected.totpSecretEncrypted, cursor.getBlobOrNull(10))
                assertBytesEqual("iv_totp", expected.ivTotp, cursor.getBlobOrNull(11))
                assertBytesEqual("url_encrypted", expected.urlEncrypted, cursor.getBlobOrNull(12))
                assertBytesEqual("iv_url", expected.ivUrl, cursor.getBlobOrNull(13))

                // Timestamp columns survive verbatim.
                assertEquals("created_at preserved for $id", expected.createdAt, cursor.getLong(14))
                assertEquals("updated_at preserved for $id", expected.updatedAt, cursor.getLong(15))
                if (expected.accessedAt == null) {
                    assertTrue("accessed_at should be NULL for $id", cursor.isNull(16))
                } else {
                    assertEquals("accessed_at preserved for $id", expected.accessedAt, cursor.getLong(16))
                }

                // The new column - must be NULL on every pre-existing row.
                assertTrue("imported_at must be NULL on legacy row $id", cursor.isNull(17))
                // Belt-and-braces: getLong on a NULL column returns 0, but isNull is
                // the real check; if we read 0 here without isNull being true it would
                // mean the column has a non-NULL stored value of 0 (would be a bug).
                assertNull("imported_at must be SQL NULL for $id",
                    if (cursor.isNull(17)) null else cursor.getLong(17))
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun insertV14Row(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        row: FixtureRow,
    ) {
        // The v14 `credentials` schema (see schemas/.../14.json) has 30 columns.
        // We provide values for every NOT NULL column and let NULL flow through
        // for the nullable BLOB / Long columns where the fixture doesn't carry one.
        // Defaults on otp_type / totp_algorithm / totp_digits / totp_period /
        // cipher_version / is_favorite / type apply automatically.
        db.execSQL(
            """
            INSERT INTO credentials (
                id, title, title_encrypted, iv_title,
                username_encrypted, iv_username,
                password_encrypted, iv_password,
                url, url_encrypted, iv_url,
                notes_encrypted, iv_notes,
                totp_secret_encrypted, iv_totp,
                tags, custom_fields,
                created_at, updated_at, accessed_at,
                is_favorite, type, deleted_at,
                otp_type, totp_algorithm, totp_digits, totp_period, hotp_counter,
                cipher_version
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            """.trimIndent(),
            arrayOf(
                row.id, row.title, row.titleEncrypted, row.ivTitle,
                row.usernameEncrypted, row.ivUsername,
                row.passwordEncrypted, row.ivPassword,
                /* url legacy plaintext */ "",
                row.urlEncrypted, row.ivUrl,
                row.notesEncrypted, row.ivNotes,
                row.totpSecretEncrypted, row.ivTotp,
                /* tags json */ "[]",
                /* custom_fields json */ "[]",
                row.createdAt, row.updatedAt, row.accessedAt,
                /* is_favorite */ 0,
                /* type */ "LOGIN",
                /* deleted_at */ null,
                /* otp_type */ "TOTP",
                /* totp_algorithm */ "SHA1",
                /* totp_digits */ 6,
                /* totp_period */ 30,
                /* hotp_counter */ null,
                /* cipher_version */ 2,
            ),
        )
    }

    private fun fixture(
        seed: Int,
        accessedAt: Long?,
        hasOtp: Boolean,
        hasTitleCt: Boolean,
    ): FixtureRow {
        // Deterministic per-row, per-field byte patterns. Distinct across fields
        // so a mis-mapped column (e.g. password bytes leaking into username)
        // would surface as an assertion failure rather than a silent pass.
        fun blob(tag: Byte) = ByteArray(16) { (seed * 31 + tag + it).toByte() }
        return FixtureRow(
            id = "row-$seed",
            title = "Credential $seed",
            usernameEncrypted = blob(1),
            ivUsername = blob(2),
            passwordEncrypted = blob(3),
            ivPassword = blob(4),
            notesEncrypted = blob(5),
            ivNotes = blob(6),
            totpSecretEncrypted = if (hasOtp) blob(7) else null,
            ivTotp = if (hasOtp) blob(8) else null,
            urlEncrypted = blob(9),
            ivUrl = blob(10),
            titleEncrypted = if (hasTitleCt) blob(11) else null,
            ivTitle = if (hasTitleCt) blob(12) else null,
            createdAt = 1_700_000_000_000L + seed,
            updatedAt = 1_700_000_500_000L + seed,
            accessedAt = accessedAt,
        )
    }

    private fun assertBytesEqual(field: String, expected: ByteArray?, actual: ByteArray?) {
        if (expected == null) {
            assertNull("$field expected NULL after migration", actual)
        } else {
            assertArrayEquals("$field bytes mutated by migration", expected, actual)
        }
    }

    private fun android.database.Cursor.getBlobOrNull(index: Int): ByteArray? =
        if (isNull(index)) null else getBlob(index)

    private companion object {
        private const val TEST_DB = "migration-14-to-15-test.db"
    }
}
