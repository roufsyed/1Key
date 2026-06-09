package com.onekey

import android.app.Application
import com.onekey.core.data.snapshot.VaultSnapshotStore
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.security.CredentialCipherMigrator
import com.onekey.core.security.ScreenOffLockReceiver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OneKeyApp : Application() {
    /**
     * Eagerly injected because each of these singletons hosts a load-bearing
     * side effect on the cold-start critical path:
     *
     *  - `AppPreferencesRepositoryImpl` exposes a `prefs: StateFlow<Preferences>`
     *    started with `SharingStarted.Eagerly`. `MainActivity.onCreate` calls
     *    `runBlocking { appPrefs.getThemeMode().first() }` before `setContent`,
     *    expecting that hot StateFlow to have hydrated from DataStore in the
     *    background. Deferring would either block the main thread on DataStore
     *    I/O during the runBlocking, or flash the light theme for one frame on
     *    dark-mode users.
     *
     *  - `AuthRepositoryImpl.init` launches the one-shot DataStore to
     *    EncryptedSharedPreferences migration on appScope(IO) and arms the
     *    `migrationComplete` barrier that every downstream auth read awaits.
     *    `ScreenOffLockReceiver` (registered below) and `OneKeyAutofillService`
     *    can both call into `authRepository` before any Activity is alive, so
     *    construction must happen here rather than on lazy first-touch.
     *
     *  - `CredentialRepository` exposes `countFlow` and `favoriteCountFlow` as
     *    `Eagerly` stateIn flows. The eager startup subscription is a
     *    defensive invariant documented to keep credential counts warm before
     *    any early-render surface (Lock screen, Onboarding, future
     *    count-badge UIs) reads them; without it, an early render would see a
     *    stale/zero count until VaultViewModel is created on user navigation.
     *    No current call-site relies on this, but the contract is deliberate.
     *
     * `TagRepository` previously lived here under the same comment. It was
     * removed because nothing on the startup path touches it and the only
     * StateFlow consumers (VaultViewModel et al.) construct lazily on user
     * navigation. The brief empty tag-count window on first VaultScreen
     * render is acceptable; the StateFlow has an `emptyList()` initial value.
     */
    @Inject lateinit var appPreferences: AppPreferencesRepository
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var credentialRepository: CredentialRepository

    /**
     * Defence-in-depth backstop for the inactivity auto-lock. See the class
     * KDoc - fires when the device display turns off, regardless of whether
     * the in-app `Modifier.lockAware()` compensation is correct or complete.
     */
    @Inject lateinit var screenOffLockReceiver: ScreenOffLockReceiver

    /**
     * Re-encrypts pre-DB-v12 credential rows to the new HKDF-subkey + per-field-AAD
     * scheme after every unlock. Idle until the vault unlocks; cancellable on lock.
     */
    @Inject lateinit var credentialCipherMigrator: CredentialCipherMigrator

    /**
     * Shared decrypted vault snapshot. Injected here (with no other consumers in
     * this PR) so Hilt instantiates it on app startup - the store's `init` block
     * installs the synchronous `VaultLockHook` on [com.onekey.core.security.VaultKeyHolder].
     * Without this injection the hook never registers and the lock-clear guarantee
     * the store provides is silently absent on first lock(). Field is unused beyond
     * triggering construction.
     */
    @Suppress("unused")
    @Inject lateinit var vaultSnapshotStore: VaultSnapshotStore

    override fun onCreate() {
        super.onCreate()
        screenOffLockReceiver.register()
        credentialCipherMigrator.start()
    }
}
