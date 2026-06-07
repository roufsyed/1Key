package com.onekey.core.di

import com.onekey.core.security.CredentialCipherMigrator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Singleton

/**
 * Provides the [CoroutineScope] for [com.onekey.core.data.snapshot.VaultSnapshotStore].
 *
 * Pinned to [Dispatchers.Default] explicitly: the coordinator's child
 * decryption coroutines are CPU-bound (HKDF + AES-GCM per row) and must NOT
 * land on Main. The existing [ApplicationScope] provider in
 * [DatabaseModule] omits the dispatcher and would dispatch through whatever
 * the call site provides — too implicit for a security-critical CPU loop.
 *
 * `SupervisorJob` ensures one decryption batch's failure (e.g. corrupt row
 * surfacing as an unexpected exception) does not cancel the coordinator
 * itself or any sibling launches.
 */
@Module
@InstallIn(SingletonComponent::class)
object SnapshotModule {

    @Provides
    @Singleton
    @SnapshotScope
    fun provideSnapshotScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Re-publishes [CredentialCipherMigrator.isMigrating] under the
     * [MigrationStatusFlow] qualifier so callers depend on the contract
     * (a boolean StateFlow that signals an in-flight legacy-row rewrite)
     * rather than on the concrete migrator class. Pivot point for tests
     * that want to drive the snapshot's `migrating` branch without
     * standing up a real migrator.
     */
    @Provides
    @Singleton
    @MigrationStatusFlow
    fun provideMigrationStatusFlow(migrator: CredentialCipherMigrator): StateFlow<Boolean> =
        migrator.isMigrating
}
