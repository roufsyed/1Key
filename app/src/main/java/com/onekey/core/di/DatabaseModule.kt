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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .addCallback(DATABASE_CALLBACK)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideCredentialDao(db: OneKeyDatabase): CredentialDao = db.credentialDao()

    @Provides
    fun provideTagDao(db: OneKeyDatabase): TagDao = db.tagDao()

    @Provides
    fun provideCredentialHistoryDao(db: OneKeyDatabase): CredentialHistoryDao = db.credentialHistoryDao()
}
