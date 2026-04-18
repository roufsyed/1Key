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

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE credentials ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [CredentialEntity::class, TagEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OneKeyDatabase : RoomDatabase() {
    abstract fun credentialDao(): CredentialDao
    abstract fun tagDao(): TagDao
}
