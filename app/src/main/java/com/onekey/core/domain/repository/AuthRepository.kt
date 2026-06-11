package com.onekey.core.domain.repository

import com.onekey.core.domain.model.AppResult
import com.onekey.core.security.KdfPreset
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    fun isSetupComplete(): Flow<Boolean>
    suspend fun setupMasterPassword(password: CharArray): AppResult<Unit>
    suspend fun unlockWithPassword(password: CharArray): AppResult<Unit>
    suspend fun unlockWithPin(pin: CharArray): AppResult<Unit>
    suspend fun unlockWithBiometric(): AppResult<Unit>
    /**
     * Verifies a PIN matches the stored PIN_VALID hash WITHOUT setting the vault key on
     * the [com.onekey.core.security.VaultKeyHolder]. For in-vault flows that need to confirm
     * "the user knows the current PIN" (e.g. changing the PIN) without re-emitting Unlocked
     * state to anyone observing AuthRepository.isUnlocked / AuthViewModel.state.
     */
    suspend fun verifyPin(pin: CharArray): AppResult<Unit>

    /**
     * Verifies the master password matches the stored verifier WITHOUT setting
     * the vault key on [com.onekey.core.security.VaultKeyHolder] or triggering
     * any side effects (sync, silent KDF/Keystore migration, attempt-counter
     * resets in this repo's collaborators).
     *
     * Use for in-vault, already-unlocked reauth flows where the caller needs
     * to confirm "the user knows the current master password" before doing
     * something security-sensitive (changing KDF preset, exporting raw bytes,
     * etc.) but does not want the side effects of [unlockWithPassword].
     *
     * Defensively copies [password] internally so the caller retains ownership;
     * the implementation zeroes its copy in a finally block. Callers SHOULD
     * still zero their own [password] after this returns - the standard
     * `password.fill(' ')` pattern.
     *
     * Returns:
     *  - [AppResult.Success] when the verifier decrypts to the sentinel "VALID".
     *  - [AppResult.Error] on wrong password, missing/corrupted verifier, or
     *    unknown stored KDF version. The [AppResult.Error.message] is a
     *    user-facing string suitable for inline error UI.
     */
    suspend fun verifyMasterPassword(password: CharArray): AppResult<Unit>

    suspend fun setupPin(pin: CharArray): AppResult<Unit>
    suspend fun changePassword(oldPassword: CharArray, newPassword: CharArray): AppResult<Unit>
    suspend fun lock(): AppResult<Unit>
    fun isUnlocked(): Flow<Boolean>
    fun isPinSetup(): Flow<Boolean>
    suspend fun resetPin(): AppResult<Unit>
    suspend fun resetVault(): AppResult<Unit>
    suspend fun clearAll(): AppResult<Unit>

    /**
     * Observes the currently-active [KdfPreset] resolved from the stored
     * `SP_KDF_VERSION` integer. The flow emits the current value on collection
     * and re-emits whenever the verifier KDF version changes (e.g. after a
     * successful preset migration or a silent legacy -> v3 normalisation).
     *
     * Legacy codes (0 = PBKDF2, 1 = Argon2id Standard) are surfaced as
     * [KdfPreset.STANDARD] because that is the semantic config they represent.
     * A user who has never opened Settings > Encryption strength sees
     * "Standard" - which is correct, because Standard is what their vault was
     * created with (and what the silent verifier-migration path normalises to).
     */
    fun observeActiveKdfPreset(): Flow<KdfPreset>

    /**
     * Observes the currently-active custom (m, t) parameters, in MiB and
     * iterations respectively, emitted as a `Pair<m, t>`. Emits `null` when
     * the active preset is not [KdfPreset.CUSTOM] (i.e. one of the four fixed
     * presets is in effect or the legacy default is active).
     *
     * Used by the Settings UI to render the custom subtitle line
     * ("m=96 MiB, t=5") under the picker's selected-preset row. Mirrors the
     * pair shape the picker pushes back through `applyCustom(...)`.
     */
    fun observeActiveKdfCustomParams(): Flow<Pair<Int, Int>?>
}
