package com.roufsyed.onekey.core.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that bumping the schema 16 -> 17 (which adds the `passkeys` table)
 * is non-destructive and lands exactly the shape Room expects:
 *
 *  - The post-migration schema matches `17.json` (Room's TableInfo validation
 *    via [MigrationTestHelper.runMigrationsAndValidate]). This pins the
 *    hand-written CREATE TABLE in MIGRATION_16_17 to the entity: a column,
 *    NOT NULL or DEFAULT drift fails here instead of at `checkIdentity` on a
 *    user's device.
 *  - Every pre-existing `credentials` row is byte-identical afterwards. A new
 *    table must not touch existing rows: if a BLOB changed, the AAD-bound
 *    AES-GCM decrypt would fail on the next unlock.
 *  - The new table starts empty, accepts a row with the nullable columns left
 *    NULL, and carries the `credential_id` index.
 *
 * Uses synthesized byte patterns rather than real crypto output - this tests
 * the migration on the DB engine, not the cipher. Modelled on
 * [OneKeyDatabaseMigration15To16Test].
 */
@RunWith(AndroidJUnit4::class)
class OneKeyDatabaseMigration16To17Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OneKeyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private data class FixtureRow(
        val id: String,
        val titleEncrypted: ByteArray,
        val ivTitle: ByteArray,
        val usernameEncrypted: ByteArray,
        val ivUsername: ByteArray,
        val passwordEncrypted: ByteArray,
        val ivPassword: ByteArray,
        val notesEncrypted: ByteArray,
        val ivNotes: ByteArray,
        val urlEncrypted: ByteArray,
        val ivUrl: ByteArray,
        val tagsJson: String,
        val customFieldsJson: String,
        val type: String,
        val cardTheme: String,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private val fixtures = listOf(fixture(1, "LOGIN", "GRAPHITE"), fixture(2, "CREDIT_CARD", "MIDNIGHT"))

    @Test
    fun migrate16To17_addsPasskeysTable_preservesExistingRows() {
        helper.createDatabase(TEST_DB, /* version = */ 16).use { db ->
            fixtures.forEach { insertV16Row(db, it) }
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            /* version = */ 17,
            /* validateDroppedTables = */ true,
            MIGRATION_16_17,
        )

        // Existing rows untouched, byte for byte.
        migrated.query(
            """
            SELECT id, title_encrypted, iv_title, username_encrypted, iv_username,
                   password_encrypted, iv_password, notes_encrypted, iv_notes,
                   url_encrypted, iv_url, tags, custom_fields, type, card_theme,
                   created_at, updated_at
            FROM credentials ORDER BY id
            """.trimIndent()
        ).use { c ->
            assertEquals(fixtures.size, c.count)
            val byId = fixtures.associateBy { it.id }
            while (c.moveToNext()) {
                val expected = byId.getValue(c.getString(0))
                assertArrayEquals(expected.titleEncrypted, c.getBlob(1))
                assertArrayEquals(expected.ivTitle, c.getBlob(2))
                assertArrayEquals(expected.usernameEncrypted, c.getBlob(3))
                assertArrayEquals(expected.ivUsername, c.getBlob(4))
                assertArrayEquals(expected.passwordEncrypted, c.getBlob(5))
                assertArrayEquals(expected.ivPassword, c.getBlob(6))
                assertArrayEquals(expected.notesEncrypted, c.getBlob(7))
                assertArrayEquals(expected.ivNotes, c.getBlob(8))
                assertArrayEquals(expected.urlEncrypted, c.getBlob(9))
                assertArrayEquals(expected.ivUrl, c.getBlob(10))
                assertEquals(expected.tagsJson, c.getString(11))
                assertEquals(expected.customFieldsJson, c.getString(12))
                assertEquals(expected.type, c.getString(13))
                assertEquals(expected.cardTheme, c.getString(14))
                assertEquals(expected.createdAt, c.getLong(15))
                assertEquals(expected.updatedAt, c.getLong(16))
            }
        }

        // New table: present, empty, indexed.
        migrated.query("SELECT COUNT(*) FROM passkeys").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        migrated.query("PRAGMA index_list('passkeys')").use { c ->
            val names = generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null }.toList()
            assertTrue("missing credential_id index, got $names", "index_passkeys_credential_id" in names)
        }
    }

    @Test
    fun migrate16To17_passkeysTableAcceptsRowsWithNullableColumnsUnset() {
        helper.createDatabase(TEST_DB_WRITE, 16).use { db -> insertV16Row(db, fixtures[0]) }
        val migrated = helper.runMigrationsAndValidate(TEST_DB_WRITE, 17, true, MIGRATION_16_17)

        fun blob(tag: Int) = ByteArray(12) { (tag * 3 + it).toByte() }
        migrated.execSQL(
            """
            INSERT INTO passkeys (
                id, credential_id,
                rp_id_encrypted, iv_rp_id, rp_name_encrypted, iv_rp_name,
                user_name_encrypted, iv_user_name, user_display_name_encrypted, iv_user_display_name,
                user_handle_encrypted, iv_user_handle, webauthn_id_encrypted, iv_webauthn_id,
                private_key_encrypted, iv_private_key, origin_binding_encrypted, iv_origin_binding,
                created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                "pk-1", fixtures[0].id,
                blob(1), blob(2), blob(3), blob(4),
                blob(5), blob(6), blob(7), blob(8),
                blob(9), blob(10), blob(11), blob(12),
                blob(13), blob(14), blob(15), blob(16),
                1_700_000_000_000L,
            ),
        )
        migrated.query(
            "SELECT credential_id, prf_secret_encrypted, last_used_at, backed_up_at, cipher_version FROM passkeys WHERE id = 'pk-1'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(fixtures[0].id, c.getString(0))
            assertTrue("prf_secret_encrypted must default to NULL", c.isNull(1))
            assertTrue("last_used_at must default to NULL", c.isNull(2))
            assertTrue("backed_up_at must default to NULL", c.isNull(3))
            assertEquals("cipher_version must default to 2", 2, c.getInt(4))
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun insertV16Row(db: SupportSQLiteDatabase, row: FixtureRow) {
        // v16 = v15 plus the NOT NULL `card_theme` column. Defaults on the OTP
        // metadata, is_favorite and cipher_version apply automatically.
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
                is_favorite, type, deleted_at, card_theme,
                otp_type, totp_algorithm, totp_digits, totp_period, hotp_counter,
                cipher_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                row.id, "", row.titleEncrypted, row.ivTitle,
                row.usernameEncrypted, row.ivUsername,
                row.passwordEncrypted, row.ivPassword,
                "", row.urlEncrypted, row.ivUrl,
                row.notesEncrypted, row.ivNotes,
                null, null,
                row.tagsJson, row.customFieldsJson,
                row.createdAt, row.updatedAt, row.updatedAt, null,
                0, row.type, null, row.cardTheme,
                "TOTP", "SHA1", 6, 30, null,
                2,
            ),
        )
    }

    private fun fixture(seed: Int, type: String, cardTheme: String): FixtureRow {
        fun blob(tag: Byte) = ByteArray(16) { (seed * 31 + tag + it).toByte() }
        return FixtureRow(
            id = "row-$seed",
            titleEncrypted = blob(11), ivTitle = blob(12),
            usernameEncrypted = blob(1), ivUsername = blob(2),
            passwordEncrypted = blob(3), ivPassword = blob(4),
            notesEncrypted = blob(5), ivNotes = blob(6),
            urlEncrypted = blob(9), ivUrl = blob(10),
            tagsJson = """["Login"]""",
            customFieldsJson = "[]",
            type = type,
            cardTheme = cardTheme,
            createdAt = 1_700_000_000_000L + seed,
            updatedAt = 1_700_000_500_000L + seed,
        )
    }

    private companion object {
        private const val TEST_DB = "migration-16-to-17-test.db"
        private const val TEST_DB_WRITE = "migration-16-to-17-write-test.db"
    }
}
