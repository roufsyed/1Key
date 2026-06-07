package com.onekey.core.di

import javax.inject.Qualifier

/**
 * Qualifier for the [kotlinx.coroutines.CoroutineScope] that owns the
 * lifetime of [com.onekey.core.data.snapshot.VaultSnapshotStore]'s
 * coordinator collector and any decryption work spawned from it.
 *
 * Kept distinct from [ApplicationScope] so tests can override just the
 * snapshot scope (e.g. with a [kotlinx.coroutines.test.TestScope]) without
 * affecting other singletons that pin to the application scope.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class SnapshotScope

/**
 * Qualifier for the `StateFlow<Boolean>` that signals "the
 * [com.onekey.core.security.CredentialCipherMigrator] is rewriting legacy
 * rows; the snapshot must suspend its `dao.observeListRaw` subscription
 * until migration completes."
 *
 * Provided as a qualifier rather than injecting the whole migrator class
 * so the snapshot store's testability is intact: a unit test passes a
 * `MutableStateFlow(false)` directly without standing up a real migrator
 * (which would in turn need a DAO, a key holder, a crypto manager, ...).
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class MigrationStatusFlow

/**
 * Qualifier for the `StateFlow<com.onekey.core.data.snapshot.SnapshotState>`
 * re-published from [com.onekey.core.data.snapshot.VaultSnapshotStore.state].
 *
 * Consumers (`VaultViewModel`, `AutofillUnlockViewModel` after PR2,
 * `FavouritesViewModel`/`TaggedCredentialListViewModel` after PR4) depend on
 * the state contract rather than the concrete store class. The qualifier
 * keeps unit tests independent of the store: a test injects a
 * `MutableStateFlow<SnapshotState>` directly and drives transitions
 * synchronously, without instantiating a real DAO + key holder + decryptor.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class SnapshotStateFlow
