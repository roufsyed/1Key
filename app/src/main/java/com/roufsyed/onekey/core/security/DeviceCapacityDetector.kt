package com.roufsyed.onekey.core.security

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of the device's relevant capacity-bearing characteristics for the
 * Argon2id encryption-strength picker.
 *
 *  - [totalRamMb] - Total physical RAM in MiB, read once via
 *    [ActivityManager.MemoryInfo.totalMem]. Stable for the process lifetime.
 *  - [isLowRamDevice] - The OS's own low-memory flag. True on devices with
 *    minimal RAM where running heavyweight foreground workloads (like the
 *    higher KDF presets) risks getting killed by `lowmemorykiller`.
 *  - [availableCores] - Logical processor count. Argon2id `parallelism` is
 *    locked to 1 in this app, so this is informational only - exposed for
 *    future tuning and for the explainer dialog.
 *  - [socModel] - `Build.SOC_MODEL` on API 31+, "unknown" below. Surfaced in
 *    bug reports so we can correlate slow-unlock complaints with chip family.
 *  - [maxArgon2MemoryMb] - Hard upper bound on the Argon2id `m` slider in the
 *    Custom dialog: `min(totalRamMb / 8, 256)`. The /8 leaves headroom for
 *    Android's foreground process and other heap allocations; an Argon2id
 *    call consuming more than 1/8 of total RAM is begging for OOM. 256 MiB
 *    is the absolute ceiling regardless of RAM (no benefit beyond that).
 *  - [recommendedPreset] - The picker's "Recommended" chip target. Reflects
 *    the device's tier: low-RAM/<3.5 GB -> Standard, 3.5-6.5 GB -> Hardened,
 *    >=6.5 GB -> Maximum.
 *  - [enabledPresets] - The set of [KdfPreset]s the picker offers as
 *    selectable. CUSTOM is always selectable (its memory slider is gated by
 *    [maxArgon2MemoryMb]); the four fixed presets are gated by `minRamMb`
 *    and `isLowRamDevice`. The use case that applies a preset validates
 *    against this set as defence-in-depth so a restored vault from a more
 *    powerful device cannot trigger an unrunnable preset here.
 */
data class CapacitySnapshot(
    val totalRamMb: Long,
    val isLowRamDevice: Boolean,
    val availableCores: Int,
    val socModel: String,
    val maxArgon2MemoryMb: Int,
    val recommendedPreset: KdfPreset,
    val enabledPresets: Set<KdfPreset>,
)

/**
 * Reads the device's RAM/CPU/SoC profile and classifies it into a
 * [CapacitySnapshot] consumed by the encryption-strength picker.
 *
 * Lifecycle:
 *  - No `start()`; lazy single-shot initialisation on first [snapshot] call.
 *  - The snapshot is memoised for the process lifetime via double-checked
 *    locking on [cachedSnapshot]. The OS does not hot-swap RAM at runtime,
 *    so recomputing is wasted work. The first call is microseconds; we
 *    cache anyway to keep the Settings ViewModel reads free of any cost.
 *  - Pre-warming from `OneKeyApp.onCreate()` is welcome but not required;
 *    the first cold call from `SettingsViewModel` is also acceptable.
 *
 * Persistence:
 *  - None. The snapshot is derived from immutable Build properties and a
 *    one-shot ActivityManager read. Restoring vault between devices means
 *    the new device computes its own snapshot from scratch.
 *
 * Testing:
 *  - The class is `open` so a test subclass can override [snapshot] with a
 *    fixed [CapacitySnapshot]. `FakeDeviceCapacityDetector(snapshot)` lives
 *    in `app/src/test/.../FakeDeviceCapacityDetector.kt` (test-only).
 *  - The classification logic lives in the companion object so unit tests
 *    can exercise the rules table directly without standing up a Context.
 */
@Singleton
open class DeviceCapacityDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var cachedSnapshot: CapacitySnapshot? = null

    /**
     * Returns the memoised [CapacitySnapshot] for this device, computing it
     * on first call. Double-checked locking keeps the lookup lock-free after
     * the first successful computation.
     */
    open fun snapshot(): CapacitySnapshot {
        cachedSnapshot?.let { return it }
        synchronized(this) {
            cachedSnapshot?.let { return it }
            val computed = computeSnapshot()
            cachedSnapshot = computed
            return computed
        }
    }

    /** Convenience accessor for picker UI; equivalent to `snapshot().enabledPresets.contains(preset)`. */
    fun isPresetEnabled(preset: KdfPreset): Boolean {
        if (preset == KdfPreset.CUSTOM) return true
        return preset in snapshot().enabledPresets
    }

    /** Max memory (MiB) the Custom dialog's slider may select on this device. */
    fun maxCustomMemoryMb(): Int = snapshot().maxArgon2MemoryMb

    private fun computeSnapshot(): CapacitySnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memoryInfo)
        val totalRamMb = memoryInfo.totalMem / (1024L * 1024L)
        val isLowRam = am.isLowRamDevice
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL ?: "unknown"
        } else {
            "unknown"
        }
        val maxCustomMb = computeMaxCustomMemoryMb(totalRamMb)
        val (recommended, enabled) = classify(totalRamMb, isLowRam)
        return CapacitySnapshot(
            totalRamMb = totalRamMb,
            isLowRamDevice = isLowRam,
            availableCores = cores,
            socModel = soc,
            maxArgon2MemoryMb = maxCustomMb,
            recommendedPreset = recommended,
            enabledPresets = enabled,
        )
    }

    companion object {

        /**
         * Hard ceiling for the Custom dialog's memory slider.
         *
         *  - Lower bound is `totalRamMb / 8` because Argon2id allocates the
         *    entire `m` block at once; consuming more than 1/8 of total RAM
         *    risks OOM under foreground pressure (browser, autofill IME,
         *    notifications all share the device heap).
         *  - Upper bound is 256 MiB because (a) attacker-cost gains plateau
         *    past that point for interactive auth, and (b) any setting that
         *    spends more than ~5 s unlocking is hostile to users.
         *
         * Exposed as a pure function for unit testing.
         */
        fun computeMaxCustomMemoryMb(totalRamMb: Long): Int {
            val byEighth = (totalRamMb / 8L).toInt().coerceAtLeast(32)
            return byEighth.coerceAtMost(256)
        }

        /**
         * Maps `(totalRamMb, isLowRamDevice)` to the recommended preset and
         * the set of enabled presets. Matches the rules table in the design
         * document. Exposed as a pure function so unit tests can exhaustively
         * exercise the boundary cases.
         */
        fun classify(totalRamMb: Long, isLowRamDevice: Boolean): Pair<KdfPreset, Set<KdfPreset>> {
            return when {
                isLowRamDevice || totalRamMb < 3500L ->
                    KdfPreset.STANDARD to setOf(KdfPreset.STANDARD, KdfPreset.STANDARD_PLUS)
                totalRamMb < 6500L ->
                    KdfPreset.HARDENED to setOf(KdfPreset.STANDARD, KdfPreset.STANDARD_PLUS, KdfPreset.HARDENED)
                else ->
                    KdfPreset.MAXIMUM to setOf(
                        KdfPreset.STANDARD,
                        KdfPreset.STANDARD_PLUS,
                        KdfPreset.HARDENED,
                        KdfPreset.MAXIMUM,
                    )
            }
        }
    }
}
