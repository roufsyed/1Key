package com.onekey.core.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.onekey.core.data.local.dao.CredentialDao
import com.onekey.core.data.local.dao.CredentialHistoryDao
import com.onekey.core.data.local.dao.TagDao
import com.onekey.core.data.local.entity.CredentialEntity
import com.onekey.core.data.local.entity.CredentialHistoryEntity
import com.onekey.core.data.local.entity.TagEntity

val DEFAULT_TAG_NAMES = listOf(
    "Login",
    "Secure Note",
    "Credit Card",
    "Password",
    "Bank Account",
    "Database",
    "Email Account",
    "Server",
)

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE credentials ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tags ADD COLUMN is_default INTEGER NOT NULL DEFAULT 0")
        val now = System.currentTimeMillis()
        DEFAULT_TAG_NAMES.forEach { name ->
            db.execSQL(
                "INSERT OR IGNORE INTO tags (name, color, icon, created_at, is_default) VALUES (?, ?, ?, ?, ?)",
                arrayOf(name, 0xFF6200EE.toInt(), "", now, 1),
            )
        }
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add encrypted URL columns. The vault key isn't available during migration, so
        // legacy plaintext url stays in place — `CredentialRepositoryImpl.toDomain()`
        // reads url_encrypted when present and falls back to the legacy `url` column,
        // and saves rewrite to encrypted form (clearing `url`). That gives us a lazy
        // upgrade per credential without losing pre-v4 URLs at migration time.
        db.execSQL("ALTER TABLE credentials ADD COLUMN url_encrypted BLOB")
        db.execSQL("ALTER TABLE credentials ADD COLUMN iv_url BLOB")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS credential_history (
                id TEXT NOT NULL PRIMARY KEY,
                credential_id TEXT NOT NULL,
                title TEXT NOT NULL,
                username_encrypted BLOB NOT NULL,
                iv_username BLOB NOT NULL,
                password_encrypted BLOB NOT NULL,
                iv_password BLOB NOT NULL,
                url_encrypted BLOB,
                iv_url BLOB,
                modified_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_credential_history_credential_id ON credential_history (credential_id)")
    }
}

// Adds credential type column. All pre-existing rows become LOGIN (the previous implicit kind).
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE credentials ADD COLUMN type TEXT NOT NULL DEFAULT 'LOGIN'")
    }
}

// Adds nullable deleted_at column for soft-delete (recycle bin). Existing rows stay active (NULL).
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE credentials ADD COLUMN deleted_at INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_credentials_deleted_at ON credentials (deleted_at)")
    }
}

// No-op SQL migration. Required because adding @ColumnInfo(defaultValue = ...) to existing
// entities (CredentialEntity.type/isFavorite, TagEntity.isDefault) changes Room's entity
// identity hash. Without a version bump, existing installs crash at openHelper.checkIdentity
// because room_master_table still holds the prior hash. The actual SQLite columns already
// have DEFAULT clauses from MIGRATION_1_2 / MIGRATION_2_3 / MIGRATION_5_6, so no schema
// rewrite is needed — just a version bump for Room to re-store the new hash.
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // intentionally empty
    }
}

// Adds 2FA params columns: otp_type, algorithm, digits, period, hotp_counter. Each
// non-null column ships a DEFAULT matching the constants the previous TotpGenerator
// hard-coded (TOTP / SHA1 / 6 digits / 30s) so pre-v9 entries decode bit-identically.
// hotp_counter stays nullable — only HOTP entries populate it.
//
// This unlocks Option C (full RFC 6238 + HOTP + Steam Guard). Storing as plaintext
// columns instead of an encrypted JSON blob is deliberate: these are metadata, not
// secrets, and the DAO needs to filter on otp_type at the SQL layer to keep HOTP
// entries out of the per-second TOTP recompute loop.
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE credentials ADD COLUMN otp_type TEXT NOT NULL DEFAULT 'TOTP'")
        db.execSQL("ALTER TABLE credentials ADD COLUMN totp_algorithm TEXT NOT NULL DEFAULT 'SHA1'")
        db.execSQL("ALTER TABLE credentials ADD COLUMN totp_digits INTEGER NOT NULL DEFAULT 6")
        db.execSQL("ALTER TABLE credentials ADD COLUMN totp_period INTEGER NOT NULL DEFAULT 30")
        db.execSQL("ALTER TABLE credentials ADD COLUMN hotp_counter INTEGER")
    }
}

// Adds `accessed_at` to carry the "last used / accessed" timestamp from
// foreign exports (Firefox's `timeLastUsed`, etc.). Nullable — no DEFAULT
// clause — so existing rows stay null instead of being misread as "accessed
// at epoch 0".
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE credentials ADD COLUMN accessed_at INTEGER")
    }
}

// `accessed_at` is now a first-class, always-present field used for display
// and (in future) sort. Backfill any null rows with their `updated_at` so
// existing entries have a sensible "last used" value immediately after
// upgrade — same heuristic Firefox / Bitwarden / 1Password use when
// migrating older entries that pre-date their last-used tracking. New
// manually-created rows default to `now` via toEntity().
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE credentials SET accessed_at = updated_at WHERE accessed_at IS NULL")
    }
}

// Seeds default tags on a brand-new database (no migration path yet exists).
val DATABASE_CALLBACK = object : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        val now = System.currentTimeMillis()
        DEFAULT_TAG_NAMES.forEach { name ->
            db.execSQL(
                "INSERT OR IGNORE INTO tags (name, color, icon, created_at, is_default) VALUES (?, ?, ?, ?, ?)",
                arrayOf(name, 0xFF6200EE.toInt(), "", now, 1),
            )
        }
    }
}

@Database(
    entities = [CredentialEntity::class, TagEntity::class, CredentialHistoryEntity::class],
    version = 11,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OneKeyDatabase : RoomDatabase() {
    abstract fun credentialDao(): CredentialDao
    abstract fun tagDao(): TagDao
    abstract fun credentialHistoryDao(): CredentialHistoryDao
}
