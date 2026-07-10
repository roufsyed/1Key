package com.roufsyed.onekey.core.di

import javax.inject.Qualifier

/**
 * Distinguishes the `notes_display.preferences_pb` [androidx.datastore.core.DataStore]
 * (per-credential UI-only display state for the markdown-notes feature) from the
 * main app-wide `onekey_prefs` DataStore. Both are `DataStore<Preferences>`; the
 * qualifier makes the injection site explicit.
 *
 * The main DataStore stays **unqualified** so existing constructor injections
 * (AppPreferencesRepositoryImpl, LockReasonStore, etc.) keep working without
 * a coordinated rename.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class NotesDisplayDataStore
