package com.roufsyed.onekey.core.di

import com.roufsyed.onekey.core.data.repository.AppPreferencesRepositoryImpl
import com.roufsyed.onekey.core.data.repository.AuthRepositoryImpl
import com.roufsyed.onekey.core.data.repository.CredentialHistoryRepositoryImpl
import com.roufsyed.onekey.core.data.repository.CredentialRepositoryImpl
import com.roufsyed.onekey.core.data.repository.NotesDisplayPrefsRepositoryImpl
import com.roufsyed.onekey.core.data.repository.TagRepositoryImpl
import com.roufsyed.onekey.core.data.wordlist.AssetWordlistProvider
import com.roufsyed.onekey.core.domain.repository.AppPreferencesRepository
import com.roufsyed.onekey.core.domain.repository.AuthRepository
import com.roufsyed.onekey.core.domain.repository.CredentialHistoryRepository
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import com.roufsyed.onekey.core.domain.repository.NotesDisplayPrefsRepository
import com.roufsyed.onekey.core.domain.repository.TagRepository
import com.roufsyed.onekey.core.domain.wordlist.WordlistProvider
import com.roufsyed.onekey.feature.sync.data.AndroidSyncCompletionNotifier
import com.roufsyed.onekey.feature.sync.data.SyncEngineImpl
import com.roufsyed.onekey.feature.sync.domain.SyncCompletionNotifier
import com.roufsyed.onekey.feature.sync.domain.SyncEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAppPreferencesRepository(impl: AppPreferencesRepositoryImpl): AppPreferencesRepository

    @Binds
    @Singleton
    abstract fun bindNotesDisplayPrefsRepository(impl: NotesDisplayPrefsRepositoryImpl): NotesDisplayPrefsRepository

    @Binds
    @Singleton
    abstract fun bindCredentialRepository(impl: CredentialRepositoryImpl): CredentialRepository

    @Binds
    @Singleton
    abstract fun bindTagRepository(impl: TagRepositoryImpl): TagRepository

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindCredentialHistoryRepository(impl: CredentialHistoryRepositoryImpl): CredentialHistoryRepository

    @Binds
    @Singleton
    abstract fun bindWordlistProvider(impl: AssetWordlistProvider): WordlistProvider

    // ── Sync feature ─────────────────────────────────────────────────────────
    //
    // SyncEngine wraps the master-password-unlock-triggered backup write. Bound
    // as a singleton so the in-flight job + mutex + state are shared across all
    // unlock paths (main app + autofill). The NoOpSyncCompletionNotifier is
    // replaced by the real Android-NotificationManager-backed binding in the
    // notification module (L10).

    @Binds
    @Singleton
    abstract fun bindSyncEngine(impl: SyncEngineImpl): SyncEngine

    @Binds
    @Singleton
    abstract fun bindSyncCompletionNotifier(impl: AndroidSyncCompletionNotifier): SyncCompletionNotifier
}
