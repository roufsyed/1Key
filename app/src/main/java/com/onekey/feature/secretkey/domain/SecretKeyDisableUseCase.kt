package com.onekey.feature.secretkey.domain

import com.onekey.core.domain.model.AppResult
import com.onekey.core.security.KdfMigrator
import com.onekey.core.security.SecretKeyHolder
import com.onekey.core.security.SecretKeyTransition
import javax.inject.Inject

/**
 * Disables the Secret Key feature on this device: re-derives the verifier
 * under MP alone, drops the active wrapped SK blob, clears the
 * SP_SECRET_KEY_ENABLED flag, and removes the SK Keystore alias.
 *
 * The migrator's [KdfMigrator.runSecretKeyTransition] handles the two-phase
 * commit and the Keystore alias delete on the success path. On failure the
 * migrator's rollback path clears the staging keys; the active SK material
 * is untouched.
 *
 * # Memory hygiene
 *
 * The caller's [masterPassword] CharArray is zeroed in finally. The
 * in-memory holder is cleared on the success path so any subsequent V5
 * backup attempt or kit render fails fast with "SK not loaded" rather than
 * silently using a stale SK that no longer matches the active verifier.
 *
 * The pre-disable SK bytes are wiped from the holder by [SecretKeyHolder.clear]
 * (which calls [ByteArray.fill] on the held buffer in place). The wrapper's
 * Phase 2 commit drops the on-disk wrapped blob and the Keystore alias delete
 * removes the wrapping key, so no SK artefacts remain after Disable.
 *
 * # Wrong-password handling
 *
 * Identical to [SecretKeyEnableUseCase]: the migrator surfaces wrong-password
 * as [AppResult.Error]. The VM owns the lockout counter via
 * [com.onekey.core.security.AuthAttemptsStore]; this use case is a thin
 * orchestrator.
 */
class SecretKeyDisableUseCase @Inject constructor(
    private val migrator: KdfMigrator,
    private val holder: SecretKeyHolder,
) {

    /**
     * Runs the Disable transition. On success the in-memory holder is
     * cleared so subsequent SK reads fail fast.
     *
     * @param masterPassword caller-owned CharArray. Zeroed in finally
     *                       regardless of success or failure.
     */
    suspend operator fun invoke(masterPassword: CharArray): AppResult<Unit> {
        return try {
            when (val migrateResult = migrator.runSecretKeyTransition(
                masterPassword = masterPassword,
                transition = SecretKeyTransition.Disable,
            )) {
                is AppResult.Success -> {
                    // Drop the in-memory SK alongside the on-disk wipe so a
                    // subsequent `holder.withBytes` cannot accidentally
                    // hand out the SK that no longer matches the active
                    // verifier. The migrator handles the on-disk wipe and
                    // the Keystore alias delete; this clear() is the
                    // memory-side companion.
                    holder.clear()
                    AppResult.Success(Unit)
                }
                is AppResult.Error -> migrateResult
            }
        } finally {
            masterPassword.fill(' ')
        }
    }
}
