package com.onekey.core.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.onekey.core.data.local.dao.CredentialDao
import com.onekey.core.data.local.dao.TagDao
import com.onekey.core.data.local.entity.CredentialEntity
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
    entities = [CredentialEntity::class, TagEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OneKeyDatabase : RoomDatabase() {
    abstract fun credentialDao(): CredentialDao
    abstract fun tagDao(): TagDao
}
