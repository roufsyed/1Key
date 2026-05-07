package com.onekey.core.di

import android.content.Context
import androidx.room.Room
import com.onekey.core.data.local.dao.CredentialDao
import com.onekey.core.data.local.dao.CredentialHistoryDao
import com.onekey.core.data.local.dao.TagDao
import com.onekey.core.data.local.database.DATABASE_CALLBACK
import com.onekey.core.data.local.database.MIGRATION_1_2
import com.onekey.core.data.local.database.MIGRATION_2_3
import com.onekey.core.data.local.database.MIGRATION_3_4
import com.onekey.core.data.local.database.MIGRATION_4_5
import com.onekey.core.data.local.database.MIGRATION_5_6
import com.onekey.core.data.local.database.MIGRATION_6_7
import com.onekey.core.data.local.database.MIGRATION_7_8
import com.onekey.core.data.local.database.MIGRATION_8_9
import com.onekey.core.data.local.database.MIGRATION_9_10
import com.onekey.core.data.local.database.OneKeyDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob())

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OneKeyDatabase =
        Room.databaseBuilder(context, OneKeyDatabase::class.java, "onekey.db")
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
            )
            .addCallback(DATABASE_CALLBACK)
            // Deliberately NOT calling fallbackToDestructiveMigration(): for a password
            // manager, silently wiping the user's encrypted vault on a schema slip is the
            // worst possible failure mode. A real migration bug should crash so it's
            // caught in dev/QA — never destroy data behind the user's back.
            .build()

    @Provides
    fun provideCredentialDao(db: OneKeyDatabase): CredentialDao = db.credentialDao()

    @Provides
    fun provideTagDao(db: OneKeyDatabase): TagDao = db.tagDao()

    @Provides
    fun provideCredentialHistoryDao(db: OneKeyDatabase): CredentialHistoryDao = db.credentialHistoryDao()
}
