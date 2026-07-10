package com.roufsyed.onekey.feature.sync.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.roufsyed.onekey.core.di.ApplicationScope
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.repository.AppPreferencesRepository
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import com.roufsyed.onekey.core.domain.usecase.ExportFormat
import com.roufsyed.onekey.core.security.BackupKeyMaterial
import com.roufsyed.onekey.core.security.CryptoManager
import com.roufsyed.onekey.core.security.VaultKeyHolder
import com.roufsyed.onekey.core.security.VaultLockHook
import com.roufsyed.onekey.core.security.VaultVersionTracker
import com.roufsyed.onekey.feature.importexport.domain.BackupEncryption
import com.roufsyed.onekey.feature.importexport.domain.VaultExporter
import com.roufsyed.onekey.feature.sync.domain.SyncCompletionNotifier
import com.roufsyed.onekey.feature.sync.domain.SyncEngine
import com.roufsyed.onekey.feature.sync.domain.SyncFailureReason
import com.roufsyed.onekey.feature.sync.domain.SyncState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [SyncEngine].
 *
 * Pipeline (Dispatchers.IO):
 *  1. Read [AppPreferencesRepository.getSyncGateDirect] - if disabled, zero password
 *     and exit early.
 *  2. `mutex.tryLock()` - if a sync is already in flight (autofill unlock race), log
 *     and drop this attempt. Lock acquired here is held for the whole sync.
 *  3. Derive backup key via [CryptoManager.deriveBackupKey] - this zeros the password.
 *  4. Transition state to [SyncState.Syncing].
 *  5. Resolve the SAF tree URI; sweep stale `.part` files (recovery for prior crashes).
 *  6. Probe URI by writing+deleting a 1-byte sentinel - confirms write access.
 *  7. Collect active + recycle-bin credentials, serialise via [VaultExporter.serializeForSync].
 *  8. Encrypt via [BackupEncryption.encryptWithKey] with the pre-derived key.
 *  9. Write to `vault-backup.1key.part`, atomic-rename to `vault-backup.1key`.
 *  10. State -> [SyncState.Synced], fire optional completion notification, persist
 *      last-success timestamp.
 *  11. `finally`: zero key+salt, release mutex, clear current job pointer.
 *
 * Vault-lock safety: the engine installs a [VaultLockHook] on [VaultKeyHolder.syncHook]
 * in its init block. On lock(), the hook fires synchronously, cancels the in-flight
 * sync coroutine, and the coroutine's NonCancellable cleanup zeros the derived key.
 * No partial file ever lands at `vault-backup.1key` because the rename is the commit
 * point - if cancelled before rename, only `.part` exists, and that is wiped on the
 * next sync's start-of-run sweep.
 *
 * Concurrent autofill+main-app unlock: serialised by [mutex]. The second attempt sees
 * `tryLock() == false` and drops the sync without modifying anything.
 */
@Singleton
class SyncEngineImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    private val appPrefs: AppPreferencesRepository,
    private val crypto: CryptoManager,
    private val credentialRepository: CredentialRepository,
    private val vaultExporter: VaultExporter,
    private val vaultVersionTracker: VaultVersionTracker,
    keyHolder: VaultKeyHolder,
    private val notifier: SyncCompletionNotifier,
) : SyncEngine {

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    override val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * Serialises sync attempts across all unlock paths (main-app, autofill). `tryLock`
     * is used to drop the second concurrent attempt instead of queueing it.
     */
    private val mutex = Mutex()

    /**
     * The currently-launched sync coroutine, tracked so [VaultLockHook] can cancel it
     * synchronously on lock(). `@Volatile` because the hook runs on the lock() caller's
     * thread (typically Main.immediate from AutoLockManager) while the sync coroutine
     * runs on Dispatchers.IO.
     */
    @Volatile private var currentJob: Job? = null

    init {
        // Install the synchronous lock hook. Fires from VaultKeyHolder.lock() on
        // the lock() caller's thread, before key zeroing. Hook implementations
        // MUST be non-suspending and microsecond-fast - we just cancel the job and
        // let the coroutine's cleanup run on its own dispatcher.
        keyHolder.syncHook = VaultLockHook {
            currentJob?.cancel(CancellationException("vault locked"))
        }
    }

    override fun maybeTriggerSync(password: CharArray, secretKey: ByteArray?) {
        // Always launch - even if sync is disabled, we need a suspending context to read
        // the gate. The launched body is the single owner of `password` AND `secretKey`
        // from this point. Both are zeroed in the finally block at the end of the
        // coroutine, no matter which branch we take.
        currentJob = appScope.launch(Dispatchers.IO) {
            // Step 1 - gate read. Race-free direct read so a recent setSyncEnabled is
            // visible without StateFlow propagation lag.
            val gate = appPrefs.getSyncGateDirect()
            if (!gate.enabled || gate.locationUri == null) {
                password.fill(' ')
                secretKey?.fill(0)
                return@launch
            }

            // Step 2 - mutex tryLock. tryLock returns false immediately if another
            // sync is in flight (e.g. autofill unlock fired sync 50ms ago).
            if (!mutex.tryLock()) {
                Log.i(TAG, "Sync already in flight; dropping this attempt")
                password.fill(' ')
                secretKey?.fill(0)
                return@launch
            }

            var material: BackupKeyMaterial? = null
            try {
                // Step 3 - derive backup key. This consumes (zeros) `password`.
                // The SK (when non-null) is mixed into the Argon2id input so
                // the resulting backup is tied to BOTH master password and
                // Secret Key. CryptoManager.deriveBackupKey does NOT zero
                // the SK byte array - that ownership stays with this method,
                // and the finally block below handles it on every exit path.
                material = crypto.deriveBackupKey(password, secretKey)

                // Step 4 - flip to Syncing AFTER mutex is held + key is derived, so
                // a quick check on state.value can never observe Syncing without an
                // associated in-flight job.
                _state.value = SyncState.Syncing

                runSync(gate.locationUri, material, requiresSecretKey = secretKey != null)
            } catch (e: CancellationException) {
                // Vault-lock cancellation OR a structured cancellation from elsewhere.
                _state.value = SyncState.Failed(SyncFailureReason.VAULT_LOCKED)
                throw e // propagate so the launched job ends in cancelled state
            } catch (e: SecurityException) {
                Log.w(TAG, "Sync failed: storage permission revoked", e)
                _state.value = SyncState.Failed(SyncFailureReason.STORAGE_ACCESS_REVOKED)
            } catch (e: IOException) {
                val reason = if (e.message?.contains("No space", ignoreCase = true) == true) {
                    SyncFailureReason.STORAGE_FULL
                } else {
                    SyncFailureReason.INTERNAL_ERROR
                }
                Log.w(TAG, "Sync failed: I/O error", e)
                _state.value = SyncState.Failed(reason)
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed: unexpected error", e)
                _state.value = SyncState.Failed(SyncFailureReason.INTERNAL_ERROR)
            } finally {
                withContext(NonCancellable) {
                    // Zero key + salt regardless of branch. salt is not secret but we zero
                    // it for defensive habit consistency with VaultKeyHolder semantics.
                    material?.key?.fill(0)
                    material?.salt?.fill(0)
                    // Defensive double-zero of password in case any branch above failed to
                    // call deriveBackupKey (which is the path that consumes the array).
                    password.fill(' ')
                    // SK is OURS to zero - the unlock path passed us a fresh defensive
                    // copy and entrusted us with the cleanup. Zero on every branch
                    // including the cancellation case so even a vault-lock mid-sync
                    // never leaves SK bytes lingering on the sync coroutine's heap.
                    secretKey?.fill(0)
                    if (mutex.isLocked) {
                        runCatching { mutex.unlock() }
                    }
                    currentJob = null
                }
            }
        }
    }

    override fun dismissChip() {
        when (_state.value) {
            is SyncState.Synced, is SyncState.Failed -> _state.value = SyncState.Idle
            else -> Unit // ignore X presses during Syncing (the chip never shows X then anyway)
        }
    }

    private suspend fun runSync(
        uriString: String,
        material: BackupKeyMaterial,
        requiresSecretKey: Boolean,
    ) {
        val treeUri = Uri.parse(uriString)
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: run {
                _state.value = SyncState.Failed(SyncFailureReason.STORAGE_NOT_FOUND)
                return
            }
        if (!tree.exists() || !tree.isDirectory) {
            _state.value = SyncState.Failed(SyncFailureReason.STORAGE_NOT_FOUND)
            return
        }

        // Sweep stale .part files from prior failed/cancelled runs. Logged loudly so we
        // notice if it ever finds something - that means our finally block missed cleanup.
        sweepStaleParts(tree)

        // Probe: write+delete a tiny sentinel. Catches SAF permission revocation BEFORE
        // we burn cycles encrypting only to fail at write time.
        if (!probeWriteAccess(tree)) {
            _state.value = SyncState.Failed(SyncFailureReason.STORAGE_ACCESS_REVOKED)
            return
        }

        // Collect plaintext. Active + recycle-bin so a restore preserves the bin.
        val activeResult = credentialRepository.getAllCredentials()
        val binResult = credentialRepository.getAllInRecycleBin()
        if (activeResult is AppResult.Error || binResult is AppResult.Error) {
            _state.value = SyncState.Failed(SyncFailureReason.INTERNAL_ERROR)
            return
        }
        val all = (activeResult as AppResult.Success).data + (binResult as AppResult.Success).data
        val plaintext = vaultExporter.serializeForSync(all, ExportFormat.JSON)
        val vaultVersion = vaultVersionTracker.getVersion()

        // Encrypt with pre-derived key (skips Argon2id - we already paid that
        // cost above in deriveBackupKey). When SK is enabled on this device,
        // the pre-derived key was derived from MP || SK and the envelope
        // version is V5 with FLAGS=1 so a new-device restore must supply
        // the Emergency Kit alongside MP. When SK is disabled, the envelope
        // version is V4 with no FLAGS byte (legacy compatible).
        val encryptedBytes = BackupEncryption.encryptWithKey(
            plaintext = plaintext,
            key = material.key,
            salt = material.salt,
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = System.currentTimeMillis(),
            vaultVersion = vaultVersion,
            requiresSecretKey = requiresSecretKey,
        )

        // Write to .part first, then atomic rename. Never overwrites the live
        // vault-backup.1key directly.
        val partFile = tree.findFile(PART_FILENAME)?.also { runCatching { it.delete() } }
            ?: null
        // Re-create after sweep ensures we own a fresh document.
        val newPart = tree.createFile(MIME_OCTET_STREAM, PART_FILENAME)
            ?: run {
                _state.value = SyncState.Failed(SyncFailureReason.INTERNAL_ERROR)
                return
            }

        try {
            context.contentResolver.openOutputStream(newPart.uri, "w")?.use { out ->
                out.write(encryptedBytes)
                out.flush()
            } ?: run {
                runCatching { newPart.delete() }
                _state.value = SyncState.Failed(SyncFailureReason.STORAGE_ACCESS_REVOKED)
                return
            }
        } catch (t: Throwable) {
            // Cleanup the .part before propagating - finally block also zeroes key.
            withContext(NonCancellable) {
                runCatching { newPart.delete() }
            }
            throw t
        }

        // Delete the prior vault-backup.1key first - most SAF providers refuse
        // rename onto an existing document. Best-effort: if delete fails the rename
        // will fail too and we'll surface the error.
        tree.findFile(FINAL_FILENAME)?.let { runCatching { it.delete() } }

        // Atomic-from-the-filesystem-API rename. If this fails, the .part lingers
        // and the next sync's sweepStaleParts cleans it up.
        if (!newPart.renameTo(FINAL_FILENAME)) {
            withContext(NonCancellable) {
                runCatching { newPart.delete() }
            }
            _state.value = SyncState.Failed(SyncFailureReason.INTERNAL_ERROR)
            return
        }

        // Success.
        appPrefs.setSyncLastSuccessAt(System.currentTimeMillis())
        _state.value = SyncState.Synced

        // Optional completion notification (gated by the user preference).
        if (appPrefs.isSyncCompletionNotificationEnabled().first()) {
            runCatching { notifier.notifyCompletion() }
                .onFailure { Log.w(TAG, "Notification post failed; ignored", it) }
        }
    }

    private fun sweepStaleParts(tree: DocumentFile) {
        val stale = tree.listFiles().filter { it.name?.endsWith(".part") == true }
        if (stale.isEmpty()) return
        Log.i(TAG, "Sweeping ${stale.size} stale .part file(s) - prior sync left a leak")
        stale.forEach { runCatching { it.delete() } }
    }

    private fun probeWriteAccess(tree: DocumentFile): Boolean {
        val probe = tree.createFile(MIME_OCTET_STREAM, PROBE_FILENAME) ?: return false
        return try {
            context.contentResolver.openOutputStream(probe.uri, "w")?.use { out ->
                out.write(0)
                out.flush()
            } ?: return false
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { probe.delete() }
        }
    }

    private companion object {
        const val TAG = "SyncEngine"
        const val FINAL_FILENAME = "vault-backup.1key"
        const val PART_FILENAME = "vault-backup.1key.part"
        const val PROBE_FILENAME = "1key-sync-probe.tmp"
        const val MIME_OCTET_STREAM = "application/octet-stream"
    }
}
