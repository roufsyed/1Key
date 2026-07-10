package com.roufsyed.onekey.core.security

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import com.roufsyed.onekey.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "HwKeyIsolation"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"

/**
 * Hardware-isolation tier of the Android Keystore key that wraps the vault.
 *
 *  - [STRONGBOX] - Key lives in a dedicated tamper-resistant chip, physically
 *    separate from the main CPU. The strongest isolation Android exposes to
 *    apps. Available on recent Pixels and many flagships.
 *  - [TEE] - Key lives in a Trusted Execution Environment - a separate,
 *    secure-only mode of the main CPU. Standard hardware-backed protection
 *    on every modern Android device. Full-strength; the warning UI does
 *    NOT trigger on this tier.
 *  - [SOFTWARE] - Key is NOT in secure hardware. On a non-rooted physical
 *    device this should never happen; observed only on emulators or rooted
 *    devices where the Keystore HAL is missing or disabled. This is the
 *    only tier that surfaces a warning in Settings.
 *
 * Tier ordering for UI hierarchy: STRONGBOX > TEE > SOFTWARE. The first two
 * both display the green-check icon; only SOFTWARE shows the warning.
 */
enum class HardwareKeyIsolationTier { STRONGBOX, TEE, SOFTWARE }

/**
 * Result of a single hardware-isolation probe.
 *
 * @property tier classification consumed by Settings UI to pick icon + dialog copy.
 * @property detail short human-readable diagnostic string. Not user-secret;
 *   intended for the explainer dialog's secondary line and for bug reports.
 *   Example: `"API 31; SecurityLevel=STRONGBOX"` or
 *   `"API 28; isInsideSecureHardware=true; lastKeyCreatedWithStrongBox=true"`.
 */
data class HardwareKeyIsolationStatus(
    val tier: HardwareKeyIsolationTier,
    val detail: String,
)

/**
 * Singleton probe that classifies the device's hardware-isolation tier for the
 * vault wrapping key.
 *
 * Lifecycle:
 *   - [start] is called once from `OneKeyApp.onCreate()` alongside other
 *     background bootstrap work. It is safe to call multiple times; subsequent
 *     calls are no-ops once a result has been published.
 *   - The probe runs on [Dispatchers.IO] because [SecretKeyFactory.getKeySpec]
 *     touches the AndroidKeyStore provider and on some devices takes 50-200ms.
 *     Definitely not main-thread work.
 *   - Result is published via [status] as a `StateFlow<HardwareKeyIsolationStatus?>`.
 *     `null` means "probe has not completed yet". Consumers should render a
 *     short "Checking..." placeholder for that brief window.
 *
 * Caching:
 *   - The tier never changes mid-process; the OS does not move keys between
 *     secure-hardware tiers at runtime. We therefore cache the result for the
 *     lifetime of the process and never re-probe.
 *   - We do NOT persist the result to disk. On the next process start the
 *     probe re-derives the tier from `KeyInfo` metadata, which is the source
 *     of truth. (`CryptoManager.lastKeyCreatedWithStrongBox` is only used as
 *     a same-process hint on API 28-30 where `KeyInfo.securityLevel` does
 *     not exist; on API 31+ the per-key SecurityLevel is always definitive.)
 *
 * Classification logic:
 *   - API 31+ (S+): read `KeyInfo.securityLevel` directly.
 *     `SECURITY_LEVEL_STRONGBOX` -> STRONGBOX,
 *     `SECURITY_LEVEL_TRUSTED_ENVIRONMENT` -> TEE,
 *     `SECURITY_LEVEL_SOFTWARE` -> SOFTWARE,
 *     `SECURITY_LEVEL_UNKNOWN_SECURE` / `SECURITY_LEVEL_UNKNOWN` -> treat as
 *     TEE (we know it's secure hardware, just not which kind).
 *   - API 28-30 (P, Q, R): read `KeyInfo.isInsideSecureHardware`. If false
 *     -> SOFTWARE. If true we cannot distinguish StrongBox vs TEE from
 *     `KeyInfo` alone, so we consult [CryptoManager.lastKeyCreatedWithStrongBox].
 *     True (same process created the key under StrongBox) -> STRONGBOX,
 *     false -> TEE. After a process restart the flag is reset, so on API 28-30
 *     a pre-existing StrongBox key will report as TEE until the user resets
 *     their vault. This is a known limitation of those API levels; the
 *     security posture is unchanged.
 *   - API < 28 (O, O_MR1): no StrongBox possible. `isInsideSecureHardware`
 *     true -> TEE; false -> SOFTWARE.
 *   - No key yet (brand-new install before `setupMasterPassword`): emit a
 *     best-effort placeholder based on `Build.VERSION.SDK_INT` only.
 *     `detail` records "before vault setup" so the consumer knows the
 *     result is provisional. The probe re-runs naturally on the next process
 *     start once the user has set up their vault.
 */
@Singleton
class HardwareKeyIsolationProbe @Inject constructor(
    private val crypto: CryptoManager,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    private val _status = MutableStateFlow<HardwareKeyIsolationStatus?>(null)

    /**
     * Latest probe result, or `null` while the probe is still running.
     * The flow completes (in the sense of emitting a final non-null value)
     * once probing finishes; the flow itself stays active for the process
     * lifetime so late subscribers see the cached value.
     */
    val status: StateFlow<HardwareKeyIsolationStatus?> = _status.asStateFlow()

    /**
     * Kick off the one-shot probe on a background coroutine. Idempotent:
     * subsequent calls are no-ops if a result has already been published or
     * if a probe is already in flight (we guard with the `_status.value !=
     * null` check on entry; the launch + assign sequence races at most once
     * per process and the latter write wins, which yields the same result
     * anyway).
     */
    fun start() {
        if (_status.value != null) return
        appScope.launch(Dispatchers.IO) {
            _status.value = probeOnce()
        }
    }

    /**
     * Synchronous one-shot detection. Public for testing; production code
     * should use [start] and observe [status] instead.
     */
    internal fun probeOnce(): HardwareKeyIsolationStatus {
        val keyToProbe: SecretKey? =
            crypto.loadKeystoreKey(KEYSTORE_ALIAS_V2)
                ?: crypto.loadKeystoreKey(KEYSTORE_ALIAS_V1)

        if (keyToProbe == null) {
            // No key exists yet (brand-new install before setupMasterPassword).
            // Best-effort placeholder based on API level only. The probe re-runs
            // on the next process start, after the user has set up their vault.
            val tier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // API 28+ supports StrongBox in principle, but we can't tell
                // whether THIS device has it without trying to create a key.
                // Conservative choice: TEE (the safer-to-claim baseline).
                HardwareKeyIsolationTier.TEE
            } else {
                HardwareKeyIsolationTier.TEE
            }
            return HardwareKeyIsolationStatus(
                tier = tier,
                detail = "Before vault setup; API ${Build.VERSION.SDK_INT}",
            )
        }

        return classifyKey(keyToProbe)
    }

    private fun classifyKey(key: SecretKey): HardwareKeyIsolationStatus {
        val keyInfo: KeyInfo = try {
            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        } catch (e: Exception) {
            // KeyInfo extraction can fail on malformed/legacy keys. Treat as
            // SOFTWARE so the UI surfaces the warning - if we cannot prove the
            // key is in secure hardware, we MUST NOT claim it is.
            Log.w(TAG, "KeyInfo extraction failed; reporting SOFTWARE", e)
            return HardwareKeyIsolationStatus(
                tier = HardwareKeyIsolationTier.SOFTWARE,
                detail = "KeyInfo unavailable: ${e.javaClass.simpleName}",
            )
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            classifyApi31Plus(keyInfo)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            classifyApi28To30(keyInfo)
        } else {
            classifyApiBelow28(keyInfo)
        }
    }

    @Suppress("DEPRECATION") // isInsideSecureHardware is fine on the legacy path.
    private fun classifyApi31Plus(keyInfo: KeyInfo): HardwareKeyIsolationStatus {
        val level = keyInfo.securityLevel
        val (tier, levelName) = when (level) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX ->
                HardwareKeyIsolationTier.STRONGBOX to "STRONGBOX"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ->
                HardwareKeyIsolationTier.TEE to "TRUSTED_ENVIRONMENT"
            KeyProperties.SECURITY_LEVEL_SOFTWARE ->
                HardwareKeyIsolationTier.SOFTWARE to "SOFTWARE"
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE ->
                HardwareKeyIsolationTier.TEE to "UNKNOWN_SECURE"
            KeyProperties.SECURITY_LEVEL_UNKNOWN ->
                // Unknown means we don't know - including whether it's even
                // secure hardware. Fall back to the isInsideSecureHardware
                // boolean as a tiebreaker.
                if (keyInfo.isInsideSecureHardware) {
                    HardwareKeyIsolationTier.TEE to "UNKNOWN (in secure hw)"
                } else {
                    HardwareKeyIsolationTier.SOFTWARE to "UNKNOWN (not in secure hw)"
                }
            else ->
                // Future-proofing: any new SECURITY_LEVEL_* constant Google
                // adds (e.g. some StrongBox-plus tier) lands here. Conservative
                // mapping to TEE if isInsideSecureHardware is true.
                if (keyInfo.isInsideSecureHardware) {
                    HardwareKeyIsolationTier.TEE to "UNRECOGNIZED($level)"
                } else {
                    HardwareKeyIsolationTier.SOFTWARE to "UNRECOGNIZED($level)"
                }
        }
        return HardwareKeyIsolationStatus(
            tier = tier,
            detail = "API ${Build.VERSION.SDK_INT}; SecurityLevel=$levelName",
        )
    }

    @Suppress("DEPRECATION") // isInsideSecureHardware is the only signal on API 28-30.
    private fun classifyApi28To30(keyInfo: KeyInfo): HardwareKeyIsolationStatus {
        if (!keyInfo.isInsideSecureHardware) {
            return HardwareKeyIsolationStatus(
                tier = HardwareKeyIsolationTier.SOFTWARE,
                detail = "API ${Build.VERSION.SDK_INT}; isInsideSecureHardware=false",
            )
        }
        // Inside secure hardware, but we can't distinguish StrongBox vs TEE
        // from KeyInfo alone on these API levels. Use the same-process hint.
        val strongBoxHint = crypto.lastKeyCreatedWithStrongBox
        val tier = if (strongBoxHint) {
            HardwareKeyIsolationTier.STRONGBOX
        } else {
            HardwareKeyIsolationTier.TEE
        }
        return HardwareKeyIsolationStatus(
            tier = tier,
            detail = "API ${Build.VERSION.SDK_INT}; " +
                "isInsideSecureHardware=true; " +
                "lastKeyCreatedWithStrongBox=$strongBoxHint",
        )
    }

    @Suppress("DEPRECATION") // isInsideSecureHardware is fine on the legacy path.
    private fun classifyApiBelow28(keyInfo: KeyInfo): HardwareKeyIsolationStatus {
        // StrongBox does not exist on API < 28; the SecurityLevel API does not
        // either. Only signal is isInsideSecureHardware.
        val tier = if (keyInfo.isInsideSecureHardware) {
            HardwareKeyIsolationTier.TEE
        } else {
            HardwareKeyIsolationTier.SOFTWARE
        }
        return HardwareKeyIsolationStatus(
            tier = tier,
            detail = "API ${Build.VERSION.SDK_INT} (< 28); " +
                "isInsideSecureHardware=${keyInfo.isInsideSecureHardware}; " +
                "StrongBox unsupported on this Android version",
        )
    }
}
