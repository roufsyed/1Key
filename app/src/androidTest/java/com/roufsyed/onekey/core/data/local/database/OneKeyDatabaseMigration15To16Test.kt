package com.roufsyed.onekey.core.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that bumping the schema 15 -> 16 (which adds the NOT NULL
 * `card_theme` column with DEFAULT 'GRAPHITE' to `credentials`) is
 * non-destructive:
 *
 *  - The post-migration schema matches `16.json` (Room's TableInfo validation
 *    via [MigrationTestHelper.runMigrationsAndValidate]). This is also what
 *    pins the entity's `@ColumnInfo(defaultValue = "GRAPHITE")` to the
 *    migration's DEFAULT clause: if the two ever drift, validation fails here
 *    instead of at `checkIdentity` on a user's device.
 *  - Every pre-existing row reads `card_theme = 'GRAPHITE'` (the SQL default;
 *    there is no unlock-time backfill).
 *  - Every encrypted column on every old row is byte-identical to its
 *    pre-migration value, and the plaintext JSON TEXT columns (`tags`,
 *    `custom_fields`) that carry a card's own fields survive verbatim. An
 *    additive ALTER TABLE must not touch existing bytes: if the BLOBs change,
 *    the AAD-bound AES-GCM decrypt fails on the next unlock and the vault is
 *    unreadable.
 *
 * Uses synthesized fake blobs (deterministic byte patterns) rather than real
 * crypto output - this tests the migration's behaviour on the DB engine, not
 * the cipher. Modelled on [OneKeyDatabaseMigration14To15Test].
 */
@RunWith(AndroidJUnit4::class)
class OneKeyDatabaseMigration15To16Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OneKeyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

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
        val tagsJson: String,
        val customFieldsJson: String,
        val type: String,
        val createdAt: Long,
        val updatedAt: Long,
        val accessedAt: Long?,
        val importedAt: Long?,
    )

    private val fixtures = listOf(
        // A plain login row, as most rows in an existing vault look.
        fixture(
            seed = 1, type = "LOGIN", hasOtp = true, hasTitleCt = true,
            accessedAt = 1_700_000_001_000L, importedAt = null,
            tagsJson = """["Login"]""", customFieldsJson = "[]",
        ),
        // A credit card row whose card fields live in the custom_fields JSON -
        // exactly the data the new column decorates. Must survive verbatim.
        fixture(
            seed = 2, type = "CREDIT_CARD", hasOtp = false, hasTitleCt = true,
            accessedAt = null, importedAt = 1_700_000_002_000L,
            tagsJson = """["Credit Card"]""", customFieldsJson = CARD_CUSTOM_FIELDS_JSON,
        ),
        // A legacy-shaped row (plaintext title, no title ciphertext).
        fixture(
            seed = 3, type = "BANK_ACCOUNT", hasOtp = true, hasTitleCt = false,
            accessedAt = 1_700_000_003_000L, importedAt = null,
            tagsJson = "[]", customFieldsJson = "[]",
        ),
    )

    @Test
    fun migrate15To16_addsCardThemeColumn_preservesExistingRows() {
        // ── 1. Seed a v15 DB with three rows. ────────────────────────────────
        helper.createDatabase(TEST_DB, /* version = */ 15).use { db ->
            fixtures.forEach { row -> insertV15Row(db, row) }
        }

        // ── 2. Apply MIGRATION_15_16 and validate the schema against 16.json. ──
        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            /* version = */ 16,
            /* validateDroppedTables = */ true,
            MIGRATION_15_16,
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
                   tags, custom_fields, type,
                   created_at, updated_at, accessed_at, imported_at,
                   card_theme
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

                assertEquals("title plaintext preserved for $id", expected.title, cursor.getString(1))

                // Encrypted columns + IVs - any byte change here would break
                // AES-GCM authentication on read.
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

                // Plaintext JSON TEXT columns carrying the card's own fields.
                assertEquals("tags preserved for $id", expected.tagsJson, cursor.getString(14))
                assertEquals("custom_fields preserved for $id", expected.customFieldsJson, cursor.getString(15))
                assertEquals("type preserved for $id", expected.type, cursor.getString(16))

                // Timestamp columns survive verbatim, including NULLs.
                assertEquals("created_at preserved for $id", expected.createdAt, cursor.getLong(17))
                assertEquals("updated_at preserved for $id", expected.updatedAt, cursor.getLong(18))
                assertNullableLong("accessed_at", id, expected.accessedAt, cursor, 19)
                assertNullableLong("imported_at", id, expected.importedAt, cursor, 20)

                // The new column: NOT NULL with the neutral default on every legacy row.
                assertFalse("card_theme must not be NULL on legacy row $id", cursor.isNull(21))
                assertEquals("card_theme must default to GRAPHITE for $id", "GRAPHITE", cursor.getString(21))
            }
        }
    }

    @Test
    fun migrate15To16_columnAcceptsNonDefaultTheme() {
        // The write path stores CardTheme.name; the DEFAULT must not be sticky
        // and the column must round-trip an arbitrary enum name as TEXT.
        helper.createDatabase(TEST_DB_WRITE, /* version = */ 15).use { db ->
            insertV15Row(db, fixtures[1])
        }
        val migrated = helper.runMigrationsAndValidate(
            TEST_DB_WRITE,
            /* version = */ 16,
            /* validateDroppedTables = */ true,
            MIGRATION_15_16,
        )
        migrated.execSQL(
            "UPDATE credentials SET card_theme = ? WHERE id = ?",
            arrayOf("MIDNIGHT", fixtures[1].id),
        )
        migrated.query(
            "SELECT card_theme FROM credentials WHERE id = ?",
            arrayOf(fixtures[1].id),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("MIDNIGHT", cursor.getString(0))
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun insertV15Row(db: SupportSQLiteDatabase, row: FixtureRow) {
        // The v15 `credentials` schema (see schemas/.../15.json) is the v14 shape
        // plus the nullable `imported_at` column. Every NOT NULL column gets a
        // value; the nullable BLOB / Long columns take NULL where the fixture
        // carries none. Defaults on otp_type / totp_algorithm / totp_digits /
        // totp_period / cipher_version / is_favorite apply automatically.
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
                created_at, updated_at, accessed_at, imported_at,
                is_favorite, type, deleted_at,
                otp_type, totp_algorithm, totp_digits, totp_period, hotp_counter,
                cipher_version
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
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
                row.tagsJson,
                row.customFieldsJson,
                row.createdAt, row.updatedAt, row.accessedAt, row.importedAt,
                /* is_favorite */ 0,
                row.type,
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
        type: String,
        hasOtp: Boolean,
        hasTitleCt: Boolean,
        accessedAt: Long?,
        importedAt: Long?,
        tagsJson: String,
        customFieldsJson: String,
    ): FixtureRow {
        // Deterministic per-row, per-field byte patterns. Distinct across fields
        // so a mis-mapped column would surface as an assertion failure rather
        // than a silent pass.
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
            tagsJson = tagsJson,
            customFieldsJson = customFieldsJson,
            type = type,
            createdAt = 1_700_000_000_000L + seed,
            updatedAt = 1_700_000_500_000L + seed,
            accessedAt = accessedAt,
            importedAt = importedAt,
        )
    }

    private fun assertBytesEqual(field: String, expected: ByteArray?, actual: ByteArray?) {
        if (expected == null) {
            assertNull("$field expected NULL after migration", actual)
        } else {
            assertArrayEquals("$field bytes mutated by migration", expected, actual)
        }
    }

    private fun assertNullableLong(
        field: String,
        id: String,
        expected: Long?,
        cursor: android.database.Cursor,
        index: Int,
    ) {
        if (expected == null) {
            assertTrue("$field should be NULL for $id", cursor.isNull(index))
        } else {
            assertFalse("$field should not be NULL for $id", cursor.isNull(index))
            assertEquals("$field preserved for $id", expected, cursor.getLong(index))
        }
    }

    private fun android.database.Cursor.getBlobOrNull(index: Int): ByteArray? =
        if (isNull(index)) null else getBlob(index)

    private companion object {
        private const val TEST_DB = "migration-15-to-16-test.db"
        private const val TEST_DB_WRITE = "migration-15-to-16-write-test.db"

        // Gson shape of List<CustomFieldEntity> as the Converters write it: byte
        // arrays serialise as JSON int arrays. Two fields: a sensitive "Card
        // Number" and a plain "Cardholder". Stored and compared verbatim.
        private const val CARD_CUSTOM_FIELDS_JSON =
            """[{"key":"","keyEncrypted":[1,2,3,4],"keyIv":[5,6,7,8],"valueEncrypted":[9,10,11,12],"iv":[13,14,15,16],"isSensitive":true},""" +
                """{"key":"","keyEncrypted":[17,18,19,20],"keyIv":[21,22,23,24],"valueEncrypted":[25,26,27,28],"iv":[29,30,31,32],"isSensitive":false}]"""
    }
}
