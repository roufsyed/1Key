package com.roufsyed.onekey.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "onekey_prefs")

// Per-credential UI-only display state for the markdown-notes feature lives in
// a SEPARATE DataStore file (`notes_display.preferences_pb`) - kept isolated
// from `onekey_prefs` so it can be wiped independently and audited as a
// non-secret surface. See NotesDisplayPrefsRepository's class KDoc for the
// security contract; this file MUST NOT contain encrypted vault data.
private val Context.notesDisplayDataStore: DataStore<Preferences> by preferencesDataStore(name = "notes_display")

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dataStore

    @Provides
    @Singleton
    @NotesDisplayDataStore
    fun provideNotesDisplayDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.notesDisplayDataStore
}
