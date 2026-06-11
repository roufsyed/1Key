package com.onekey.core.security

/**
 * Argon2id key-derivation strength presets exposed to users in
 * Settings > Security > Encryption strength.
 *
 * Each preset is a fixed (memory cost, time cost, parallelism) triple that
 * trades unlock latency for resistance to offline brute-force attack. Memory
 * cost dominates GPU/ASIC cracking economics, so the higher tiers scale memory
 * (the "hard" axis) before they scale iterations.
 *
 *  - [STANDARD] - OWASP 2023 interactive-auth recommendation. The default for
 *    every fresh install. Balanced for all devices including 2 GB low-RAM
 *    phones; ~0.3-0.6 s unlock on a mid-tier device.
 *  - [STANDARD_PLUS] - Same memory as Standard, more iterations. Cheap to opt
 *    into for users who keep weak-ish master passwords and don't mind a
 *    slightly slower unlock. ~0.8-1.5 s on a mid-tier device.
 *  - [HARDENED] - Doubles memory to 128 MiB. Significantly raises GPU-farm
 *    attacker cost. Needs ~4 GB+ device RAM to avoid swap/OOM during the
 *    derivation. ~1-2 s on devices that meet the bar.
 *  - [MAXIMUM] - Highest fixed preset. 128 MiB + 8 iterations. ~2-4 s unlock.
 *    Recommended only on 6 GB+ devices.
 *  - [CUSTOM] - Placeholder enum entry; the live (m, t) values are NOT
 *    encoded in this enum and MUST be supplied separately at the call site
 *    (e.g. via [KdfParams] or stored in EncryptedSharedPreferences). Calling
 *    [toKdfParams] on [CUSTOM] throws because a parameterless preset cannot
 *    represent custom parameters.
 *
 * `parallelism` is locked to 1 on every preset (including custom). On
 * commodity Android hardware Argon2id parallelism greater than 1 reduces
 * attacker cost more than defender cost, contradicting OWASP guidance.
 *
 * `minRamMb` is the lower bound on device total RAM at which the preset is
 * offered in the picker. The picker also respects `ActivityManager.isLowRamDevice`
 * (it forces low-RAM devices to {Standard, Standard-plus}). See
 * `DeviceCapacityDetector` for the full gating rules.
 */
enum class KdfPreset(
    val mCostKiB: Int,
    val tCost: Int,
    val parallelism: Int,
    val minRamMb: Long,
    val displayName: String,
    val description: String,
) {
    STANDARD(
        mCostKiB = 65_536,
        tCost = 3,
        parallelism = 1,
        minRamMb = 0L,
        displayName = "Standard",
        description = "OWASP 2023 default. Balanced for all devices.",
    ),
    STANDARD_PLUS(
        mCostKiB = 65_536,
        tCost = 8,
        parallelism = 1,
        minRamMb = 0L,
        displayName = "Standard-plus",
        description = "More iterations on the same memory. Slower unlock, stronger against weak passwords.",
    ),
    HARDENED(
        mCostKiB = 131_072,
        tCost = 4,
        parallelism = 1,
        minRamMb = 3500L,
        displayName = "Hardened",
        description = "Doubles memory to 128 MiB. Significantly harder to brute-force. Needs 4 GB+ device RAM.",
    ),
    MAXIMUM(
        mCostKiB = 131_072,
        tCost = 8,
        parallelism = 1,
        minRamMb = 6500L,
        displayName = "Maximum",
        description = "Highest preset. ~2-4s unlock. Needs 6 GB+ device RAM.",
    ),
    CUSTOM(
        // Placeholder values; CUSTOM never owns its own (m, t). Callers must
        // supply explicit params via KdfParams. The numbers here are sentinel
        // -1 so any accidental use shows up immediately rather than silently
        // running Argon2id with garbage parameters.
        mCostKiB = -1,
        tCost = -1,
        parallelism = 1,
        minRamMb = 0L,
        displayName = "Custom",
        description = "Advanced: pick your own Argon2id parameters.",
    ),
    ;

    /**
     * Resolves this preset into concrete [KdfParams] suitable for
     * `CryptoManager.deriveKeyFromPasswordArgon2id`.
     *
     * Throws [IllegalStateException] on [CUSTOM] because that preset does not
     * own its own (m, t) - the caller MUST construct a [KdfParams] directly
     * from the user-chosen slider values. The exception fires early to
     * prevent accidentally running Argon2id under the sentinel `-1` values.
     */
    fun toKdfParams(): KdfParams {
        check(this != CUSTOM) {
            "KdfPreset.CUSTOM does not encode its own parameters; construct KdfParams directly."
        }
        return KdfParams(
            mCostKiB = mCostKiB,
            tCost = tCost,
            parallelism = parallelism,
            hashLengthBytes = KdfParams.DEFAULT_HASH_LENGTH_BYTES,
        )
    }
}

/**
 * Concrete Argon2id parameter bundle used by `CryptoManager.deriveKeyFromPasswordArgon2id`.
 *
 * Carries arbitrary (m, t, p, hashLength) so it can represent both fixed
 * [KdfPreset] values and user-defined custom parameters from the
 * Settings > Encryption strength > Custom dialog.
 *
 * Defaults:
 *  - [hashLengthBytes] defaults to [DEFAULT_HASH_LENGTH_BYTES] (32 bytes /
 *    256 bits) so callers get an AES-256-compatible key without thinking
 *    about it. Match `CryptoManager.ARGON2_HASH_LENGTH`.
 *
 * Equality is structural so two `KdfParams` with the same numeric content
 * collide in `Map<KdfParams, *>` (used by `KdfBenchmark` for its cache key).
 */
/**
 * Resolves the user's CURRENTLY-ACTIVE Argon2id `(memoryMiB, tCost)` pair from
 * `(activePreset, customMt)`, in the units used by the Custom dialog's
 * sliders. Returns `null` when [activePreset] is [KdfPreset.CUSTOM] but no
 * custom params have been observed yet (cold-start race), so callers can
 * choose to skip "matches active" comparisons rather than gate against a
 * pseudo-value.
 *
 * Single source of truth for "what is live right now" so the Custom dialog
 * does not commit a slider-position that already equals the active config
 * (a no-op migration that still pays the Argon2id re-derive cost).
 *
 *  - Fixed presets resolve from their declared `mCostKiB`/`tCost`.
 *  - [KdfPreset.CUSTOM] resolves from [customMt] directly (already in MiB).
 *
 * `parallelism` is intentionally not part of the tuple - it is locked to 1
 * everywhere and would just clutter the comparison.
 */
fun currentActiveMt(activePreset: KdfPreset, customMt: Pair<Int, Int>?): Pair<Int, Int>? =
    when (activePreset) {
        KdfPreset.CUSTOM -> customMt
        else -> activePreset.mCostKiB / 1024 to activePreset.tCost
    }

data class KdfParams(
    val mCostKiB: Int,
    val tCost: Int,
    val parallelism: Int,
    val hashLengthBytes: Int = DEFAULT_HASH_LENGTH_BYTES,
) {
    init {
        require(mCostKiB > 0) { "mCostKiB must be positive, was $mCostKiB" }
        require(tCost > 0) { "tCost must be positive, was $tCost" }
        require(parallelism > 0) { "parallelism must be positive, was $parallelism" }
        require(hashLengthBytes > 0) { "hashLengthBytes must be positive, was $hashLengthBytes" }
    }

    companion object {
        /** 32 bytes = 256-bit key, matches AES-256 and `CryptoManager.ARGON2_HASH_LENGTH`. */
        const val DEFAULT_HASH_LENGTH_BYTES: Int = 32
    }
}
