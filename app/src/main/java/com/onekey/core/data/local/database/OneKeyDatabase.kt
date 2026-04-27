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
        // Add encrypted URL columns; clear legacy plaintext url (vault key not available here).
        db.execSQL("ALTER TABLE credentials ADD COLUMN url_encrypted BLOB")
        db.execSQL("ALTER TABLE credentials ADD COLUMN iv_url BLOB")
        db.execSQL("UPDATE credentials SET url = ''")
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
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OneKeyDatabase : RoomDatabase() {
    abstract fun credentialDao(): CredentialDao
    abstract fun tagDao(): TagDao
    abstract fun credentialHistoryDao(): CredentialHistoryDao
}
