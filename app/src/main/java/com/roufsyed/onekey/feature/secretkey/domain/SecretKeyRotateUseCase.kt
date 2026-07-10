package com.roufsyed.onekey.feature.secretkey.domain

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.security.KdfMigrator
import com.roufsyed.onekey.core.security.SecretKeyHolder
import com.roufsyed.onekey.core.security.SecretKeyKeystoreWrapper
import com.roufsyed.onekey.core.security.SecretKeyTransition
import java.security.SecureRandom
import javax.inject.Inject

/**
 * Replaces the active Secret Key with a fresh 128-bit value. Mirrors
 * [SecretKeyEnableUseCase] but uses the Rotate transition so the migrator
 * validates that an SK is already active. The wrapper's `wrap()` deletes the
 * prior Keystore alias before creating the new one, satisfying the
 * "drop old SK on rotate" property locked by the design.
 *
 * Existing backup files written before the rotate continue to require the
 * OLD SK; this use case does NOT mutate any export artefacts. The Settings
 * banner that nags the user to export a fresh kit after rotate is owned by
 * [com.roufsyed.onekey.feature.settings.presentation.viewmodel.SecretKeySettingsViewModel].
 *
 * # Memory hygiene
 *
 * Identical contract to [SecretKeyEnableUseCase]:
 *  - Caller's [masterPassword] zeroed in finally.
 *  - Local SK working copy zeroed in finally.
 *  - Holder receives an independent defensive copy of the new SK.
 *  - Returned ByteArray is a separate defensive copy the caller owns and
 *    MUST zero after rendering the kit.
 *
 * The OLD SK in the holder is zeroed by [SecretKeyHolder.setBytes] (which
 * fills the prior buffer in place before publishing the new one). So even
 * a successful rotate leaves no copy of the previous SK in the holder's
 * heap residency.
 */
class SecretKeyRotateUseCase @Inject constructor(
    private val migrator: KdfMigrator,
    private val holder: SecretKeyHolder,
    private val wrapper: SecretKeyKeystoreWrapper,
) {

    /**
     * Generates a fresh SK, runs the Rotate transition, and on success
     * installs the new SK into the holder. Returns a defensive copy of the
     * fresh SK so the caller can render a new Emergency Kit.
     *
     * @param masterPassword caller-owned CharArray. Zeroed in finally
     *                       regardless of success or failure.
     * @return [AppResult.Success] carrying the fresh 16-byte ByteArray that
     *         the caller MUST zero after rendering the kit. On failure the
     *         active SK is untouched and the prior Keystore alias remains
     *         intact (the wrapper's deletion only happens inside `wrap()`
     *         which the migrator only calls after the precondition check
     *         passes).
     */
    suspend operator fun invoke(masterPassword: CharArray): AppResult<ByteArray> {
        val skRaw = ByteArray(SecretKeyHolder.SECRET_KEY_RAW_LENGTH).also {
            SecureRandom().nextBytes(it)
        }
        return try {
            when (val migrateResult = migrator.runSecretKeyTransition(
                masterPassword = masterPassword,
                transition = SecretKeyTransition.Rotate(newSk = skRaw),
            )) {
                is AppResult.Success -> {
                    // setBytes zeros the old held buffer in place before
                    // publishing the new reference, so the OLD SK never
                    // outlives the rotate by even one allocation cycle.
                    holder.setBytes(skRaw)
                    AppResult.Success(skRaw.copyOf())
                }
                is AppResult.Error -> {
                    // The migrator's rollback path covers this on the
                    // happy-failure side, but a future migrator change
                    // that throws before reaching rollbackSk would
                    // leave a freshly-minted pending alias orphaned.
                    // Sweep removes every 1key_secret_key_v* alias
                    // except the one still pointed at by the active
                    // version - rotation failure keeps that intact.
                    runCatching {
                        wrapper.sweepOrphanAliases(keepVersions = setOf(wrapper.activeVersion()))
                    }
                    migrateResult
                }
            }
        } finally {
            skRaw.fill(0)
            masterPassword.fill(' ')
        }
    }
}
