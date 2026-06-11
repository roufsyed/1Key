package com.onekey.feature.secretkey.domain

import com.onekey.core.domain.model.AppResult
import com.onekey.core.security.KdfMigrator
import com.onekey.core.security.SecretKeyHolder
import com.onekey.core.security.SecretKeyKeystoreWrapper
import com.onekey.core.security.SecretKeyTransition
import java.security.SecureRandom
import javax.inject.Inject

/**
 * Generates a fresh 16-byte Secret Key, stages it via the SK-aware
 * two-phase commit on [KdfMigrator.runSecretKeyTransition], and on success
 * installs the new SK into the in-memory [SecretKeyHolder] so the unlocked
 * session can render the Emergency Kit and write V5 backups.
 *
 * # Why this lives in a use case (and not in the ViewModel)
 *
 * The use case is the orchestration boundary the design doc pins for SK
 * generation, wrapping, and holder installation. The ViewModel owns lockout
 * policy and SharedFlow event emission; the use case owns the deterministic
 * crypto path. Splitting the two means:
 *  - The migrator stays generic - it accepts an SK byte array without
 *    knowing where the bytes came from.
 *  - The ViewModel-side tests can exercise lockout / event paths against a
 *    stub use case, and the use-case tests can exercise the byte-flow
 *    contract independently.
 *
 * # Memory hygiene
 *
 * The fresh SK lives in three places by the time this returns:
 *  1. As `skRaw` in this method's local scope. Zeroed in finally after the
 *     migrator returns.
 *  2. As the migrator's transient copy inside the wrap step. The wrapper
 *     zeros its own working copy.
 *  3. As the holder's defensive copy, owned by the unlocked session.
 *
 * The caller's [masterPassword] CharArray is zeroed in finally on every
 * branch (success, migrator failure, throw). The use case never stores a
 * reference to the master password.
 *
 * # Wrong-password handling
 *
 * The migrator surfaces a wrong-password attempt as [AppResult.Error] with
 * a user-facing message. The ViewModel inspects the message string to drive
 * its attempts-counter logic (see [com.onekey.core.security.AuthAttemptsStore]);
 * this use case does NOT participate in the lockout counter - that is the
 * VM's responsibility, since the counter is shared across enable / disable /
 * rotate flows and the use case only sees one transition at a time.
 *
 * # On Phase 1c failure (wrap landed, Phase 2 did not commit)
 *
 * The migrator's internal `rollbackSk` path zeros the pending SP keys and
 * deletes the Keystore alias minted by the wrap step. The orphan-alias sweep
 * in [KdfMigrator.resumePendingInternal] is a second line of defence if the
 * process dies between wrap and rollback. Together they leave no SK
 * artefacts behind on a failed enable.
 */
class SecretKeyEnableUseCase @Inject constructor(
    private val migrator: KdfMigrator,
    private val holder: SecretKeyHolder,
    private val wrapper: SecretKeyKeystoreWrapper,
) {

    /**
     * Generates a fresh 128-bit SK from [SecureRandom], hands it to the
     * migrator's two-phase transition, and on success installs the bytes
     * into the in-memory holder. Returns a defensive copy of the SK so the
     * caller can render the Emergency Kit; the holder receives its own
     * independent copy so a subsequent zero on the returned array does not
     * disturb the live session.
     *
     * @param masterPassword caller-owned CharArray. Zeroed in finally
     *                       regardless of success or failure.
     * @return [AppResult.Success] carrying a fresh 16-byte ByteArray that
     *         the caller MUST zero after rendering the kit. On failure the
     *         active SK setting is untouched and no Keystore artefacts
     *         remain.
     */
    suspend operator fun invoke(masterPassword: CharArray): AppResult<ByteArray> {
        // Generate the SK first so a wrong-password attempt never wastes the
        // user's time on Argon2id without a real SK to install. The bytes
        // live on the heap for the duration of this call; the finally block
        // zeros our local working copy in every branch.
        val skRaw = ByteArray(SecretKeyHolder.SECRET_KEY_RAW_LENGTH).also {
            SecureRandom().nextBytes(it)
        }
        return try {
            when (val migrateResult = migrator.runSecretKeyTransition(
                masterPassword = masterPassword,
                transition = SecretKeyTransition.Enable(newSk = skRaw),
            )) {
                is AppResult.Success -> {
                    // Install into the holder so the unlocked session can
                    // read the SK via withBytes for kit rendering / V5
                    // backup writes. setBytes copies its argument; the
                    // returned ByteArray below is a separate defensive
                    // copy the caller owns.
                    holder.setBytes(skRaw)
                    AppResult.Success(skRaw.copyOf())
                }
                is AppResult.Error -> {
                    // Defence in depth: the migrator already rolls back
                    // the pending versioned alias on its failure path,
                    // but if a future migrator refactor breaks that
                    // guarantee we still want zero orphan SK aliases on
                    // a failed enable. Sweep keeps only the alias
                    // matching the current active version (0 = none).
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
