package com.roufsyed.onekey.core.security

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Behavioural locks for [DeviceCapacityDetector].
 *
 * The detector classifies the device's RAM/CPU footprint into a recommended
 * Argon2id preset and a set of enabled presets, which the picker UI gates
 * its rows on AND the migration use case re-validates against as defence
 * in depth. The decision rules MUST stay aligned with three other surfaces:
 *
 *  - The FAQ comparison table (Min RAM column) in `SettingsFaqScreen.kt`.
 *  - The KdfPreset.minRamMb field on each preset.
 *  - The picker's "Requires N GB RAM" warning text on disabled rows.
 *
 * Robolectric is required because the production code path reads
 * `ActivityManager.MemoryInfo.totalMem` and `ActivityManager.isLowRamDevice`
 * through the application context. `ShadowActivityManager.setMemoryInfo` /
 * `setIsLowRamDevice` let us inject any totalMem the rules table needs.
 *
 * The companion-object pure functions (`classify`, `computeMaxCustomMemoryMb`)
 * are also tested directly so a future move of the detector to a
 * non-Robolectric harness can keep the rule coverage without rewiring
 * shadow plumbing.
 *
 * `application = Application::class` mirrors the other Robolectric tests in
 * the suite - it bypasses HiltAndroidApp's eager EncryptedSharedPreferences
 * provisioning, which Robolectric's shadow KeyStore cannot resolve.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceCapacityDetectorTest {

    // ── classify() pure-function rules ────────────────────────────────────

    @Test fun classify_lowRamDevice_overrides_RAM_and_returns_Standard_only() {
        // The OS's `isLowRamDevice` is a hard veto regardless of totalMem.
        // Some 4 GB devices declare lowRam=true (Android Go, OEM variants,
        // emulator profiles). In that case the picker must offer only
        // Standard / Standard-plus even though the RAM column would normally
        // unlock Hardened. The rule comes from the design table:
        //   "any totalRamMb | isLowRamDevice=true -> STANDARD + STANDARD_PLUS"
        val (recommended, enabled) = DeviceCapacityDetector.classify(
            totalRamMb = 6_000L,
            isLowRamDevice = true,
        )
        assertEquals(KdfPreset.STANDARD, recommended)
        assertEquals(
            setOf(KdfPreset.STANDARD, KdfPreset.STANDARD_PLUS),
            enabled,
        )
    }

    @Test fun classify_below_3500MiB_returns_Standard_tier() {
        // Devices with less than 3500 MiB (~4 GB) total RAM cannot afford
        // a 128 MiB Argon2id allocation alongside the foreground browser /
        // autofill IME / system services. They get Standard + Standard-plus.
        val (recommended, enabled) = DeviceCapacityDetector.classify(
            totalRamMb = 2_048L,
            isLowRamDevice = false,
        )
        assertEquals(KdfPreset.STANDARD, recommended)
        assertEquals(
            setOf(KdfPreset.STANDARD, KdfPreset.STANDARD_PLUS),
            enabled,
        )
    }

    @Test fun classify_at_lower_boundary_3499MiB_still_returns_Standard_tier() {
        // Boundary test for the `< 3500` rule. 3499 must stay in the
        // Standard tier; 3500 (next test) is the first value that unlocks
        // Hardened. Off-by-one here would suddenly promote a whole class
        // of devices and start producing slow unlocks for them.
        val (recommended, enabled) = DeviceCapacityDetector.classify(
            totalRamMb = 3_499L,
            isLowRamDevice = false,
        )
        assertEquals(KdfPreset.STANDARD, recommended)
        assertFalse(
            "Hardened must NOT appear below the 3500 MiB threshold",
            enabled.contains(KdfPreset.HARDENED),
        )
    }

    @Test fun classify_at_3500MiB_promotes_to_Hardened_tier() {
        val (recommended, enabled) = DeviceCapacityDetector.classify(
            totalRamMb = 3_500L,
            isLowRamDevice = false,
        )
        assertEquals(KdfPreset.HARDENED, recommended)
        assertEquals(
            setOf(KdfPreset.STANDARD, KdfPreset.STANDARD_PLUS, KdfPreset.HARDENED),
            enabled,
        )
    }

    @Test fun classify_4GB_device_returns_Hardened_tier() {
        // The "4 GB device" headline copy in the FAQ resolves to a totalMem
        // somewhere around 3700-3800 MiB after the kernel reserves its
        // share. We test 4096 (which actually maps to a 4-GB-marketed
        // device's totalMem post-reserve) and 3_500 above.
        val (recommended, enabled) = DeviceCapacityDetector.classify(
            totalRamMb = 4_096L,
            isLowRamDevice = false,
        )
        assertEquals(KdfPreset.HARDENED, recommended)
        assertEquals(
            setOf(KdfPreset.STANDARD, KdfPreset.STANDARD_PLUS, KdfPreset.HARDENED),
            enabled,
        )
        assertFalse(
            "Maximum must NOT appear below the 6500 MiB threshold",
            enabled.contains(KdfPreset.MAXIMUM),
        )
    }

    @Test fun classify_at_6499MiB_stays_in_Hardened_tier() {
        // The Maximum boundary: 6499 stays Hardened, 6500 promotes. Same
        // off-by-one regression risk as the Hardened boundary above.
        val (recommended, enabled) = DeviceCapacityDetector.classify(
            totalRamMb = 6_499L,
            isLowRamDevice = false,
        )
        assertEquals(KdfPreset.HARDENED, recommended)
        assertFalse(
            "Maximum must NOT appear below the 6500 MiB threshold",
            enabled.contains(KdfPreset.MAXIMUM),
        )
    }

    @Test fun classify_at_6500MiB_promotes_to_Maximum_tier() {
        val (recommended, enabled) = DeviceCapacityDetector.classify(
            totalRamMb = 6_500L,
            isLowRamDevice = false,
        )
        assertEquals(KdfPreset.MAXIMUM, recommended)
        assertEquals(
            setOf(
                KdfPreset.STANDARD,
                KdfPreset.STANDARD_PLUS,
                KdfPreset.HARDENED,
                KdfPreset.MAXIMUM,
            ),
            enabled,
        )
    }

    @Test fun classify_8GB_device_returns_Maximum_tier_with_all_presets_enabled() {
        val (recommended, enabled) = DeviceCapacityDetector.classify(
            totalRamMb = 8_192L,
            isLowRamDevice = false,
        )
        assertEquals(KdfPreset.MAXIMUM, recommended)
        assertEquals(
            "Top-tier device must offer every fixed preset",
            setOf(
                KdfPreset.STANDARD,
                KdfPreset.STANDARD_PLUS,
                KdfPreset.HARDENED,
                KdfPreset.MAXIMUM,
            ),
            enabled,
        )
    }

    @Test fun classify_never_includes_CUSTOM_in_enabled_set() {
        // The enabled set governs which fixed-preset rows render selectable
        // in the picker. CUSTOM is ALWAYS selectable through its own button,
        // and its slider cap is handled separately by `computeMaxCustomMemoryMb`.
        // Including CUSTOM in `enabled` would change the picker's chip
        // rendering and could short-circuit defence-in-depth checks in
        // KdfMigrator. Exhaust every RAM bucket to confirm the rule.
        listOf(2_000L, 4_000L, 8_000L, 16_000L).forEach { ramMb ->
            listOf(true, false).forEach { lowRam ->
                val (_, enabled) = DeviceCapacityDetector.classify(ramMb, lowRam)
                assertFalse(
                    "CUSTOM must never appear in enabled at ramMb=$ramMb, lowRam=$lowRam",
                    enabled.contains(KdfPreset.CUSTOM),
                )
            }
        }
    }

    // ── computeMaxCustomMemoryMb() pure-function rules ────────────────────

    @Test fun maxCustomMemoryMb_uses_one_eighth_of_total_RAM_below_the_256_ceiling() {
        // The /8 quota is the design's safety margin: an Argon2id allocation
        // taking more than 1/8 of total RAM risks OOM under foreground
        // pressure (browser, autofill IME, notifications all share heap).
        //
        // On 1.5 GB (1536 MiB) -> 1536/8 = 192 -> below the 256 ceiling so
        // the /8 value passes through. This is the only RAM band where the
        // /8 path is observable - higher RAM saturates the 256 ceiling.
        assertEquals(192, DeviceCapacityDetector.computeMaxCustomMemoryMb(1_536L))
        // 1 GB -> 1024/8 = 128, also below the ceiling.
        assertEquals(128, DeviceCapacityDetector.computeMaxCustomMemoryMb(1_024L))
        // 2 GB -> 2048/8 = 256, exactly at the ceiling.
        assertEquals(256, DeviceCapacityDetector.computeMaxCustomMemoryMb(2_048L))
    }

    @Test fun maxCustomMemoryMb_caps_at_256_regardless_of_RAM() {
        // Attacker-cost gains plateau past 256 MiB for interactive auth;
        // ceiling is a hard MAX regardless of how much RAM the device has.
        // Test a couple of high-RAM values to lock the cap.
        assertEquals(256, DeviceCapacityDetector.computeMaxCustomMemoryMb(8_192L))
        assertEquals(256, DeviceCapacityDetector.computeMaxCustomMemoryMb(12_288L))
        assertEquals(256, DeviceCapacityDetector.computeMaxCustomMemoryMb(16_384L))
    }

    @Test fun maxCustomMemoryMb_floors_at_32_MiB_on_tiny_devices() {
        // 32 MiB is the OWASP 2023 floor for memory-constrained interactive
        // auth and matches `CUSTOM_M_MIN` in `KdfCustomDialog`. The detector
        // MUST never report a maxM below 32 - the slider would then have
        // a range of (32..lower), which is a runtime crash for Material's
        // Slider. The .coerceAtLeast(32) in the implementation defends this.
        // Test with a degenerate-low totalMem (1 MiB) to confirm the floor.
        assertEquals(
            "32 MiB floor must hold even on a degenerate device",
            32,
            DeviceCapacityDetector.computeMaxCustomMemoryMb(1L),
        )
        assertEquals(32, DeviceCapacityDetector.computeMaxCustomMemoryMb(64L))
    }

    @Test fun maxCustomMemoryMb_at_boundary_2048MiB_returns_256() {
        // 2048 / 8 = 256, exactly the ceiling. Pins the boundary so a
        // future change to either the /8 quota or the ceiling doesn't
        // silently shift this.
        assertEquals(256, DeviceCapacityDetector.computeMaxCustomMemoryMb(2_048L))
    }

    // ── snapshot() integration via Robolectric ────────────────────────────

    @Test fun snapshot_reads_RAM_from_ActivityManager_and_classifies() {
        // End-to-end through the real Android API: stub ActivityManager
        // with a 2 GB MemoryInfo + lowRam=false, build the detector, take
        // a snapshot, assert it landed in the Standard tier.
        val context: Context = RuntimeEnvironment.getApplication()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().apply {
            totalMem = 2L * 1024L * 1024L * 1024L  // 2 GB
        }
        shadowOf(am).setMemoryInfo(mi)
        shadowOf(am).setIsLowRamDevice(false)

        val detector = DeviceCapacityDetector(context)
        val snap = detector.snapshot()

        assertEquals(
            "totalRamMb conversion (bytes -> MiB) must round-down",
            2_048L,
            snap.totalRamMb,
        )
        assertFalse(snap.isLowRamDevice)
        assertEquals(KdfPreset.STANDARD, snap.recommendedPreset)
        assertEquals(
            setOf(KdfPreset.STANDARD, KdfPreset.STANDARD_PLUS),
            snap.enabledPresets,
        )
    }

    @Test fun snapshot_with_4GB_RAM_returns_Hardened_tier() {
        val context: Context = RuntimeEnvironment.getApplication()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().apply {
            totalMem = 4L * 1024L * 1024L * 1024L  // 4 GB
        }
        shadowOf(am).setMemoryInfo(mi)
        shadowOf(am).setIsLowRamDevice(false)

        val detector = DeviceCapacityDetector(context)
        val snap = detector.snapshot()

        assertEquals(4_096L, snap.totalRamMb)
        assertEquals(KdfPreset.HARDENED, snap.recommendedPreset)
        assertTrue(snap.enabledPresets.contains(KdfPreset.HARDENED))
        assertFalse(
            "Maximum must stay locked at 4 GB",
            snap.enabledPresets.contains(KdfPreset.MAXIMUM),
        )
    }

    @Test fun snapshot_with_8GB_RAM_returns_Maximum_tier() {
        val context: Context = RuntimeEnvironment.getApplication()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().apply {
            totalMem = 8L * 1024L * 1024L * 1024L  // 8 GB
        }
        shadowOf(am).setMemoryInfo(mi)
        shadowOf(am).setIsLowRamDevice(false)

        val detector = DeviceCapacityDetector(context)
        val snap = detector.snapshot()

        assertEquals(8_192L, snap.totalRamMb)
        assertEquals(KdfPreset.MAXIMUM, snap.recommendedPreset)
        assertTrue(
            "Top tier must enable every fixed preset",
            snap.enabledPresets.containsAll(
                listOf(
                    KdfPreset.STANDARD,
                    KdfPreset.STANDARD_PLUS,
                    KdfPreset.HARDENED,
                    KdfPreset.MAXIMUM,
                )
            ),
        )
    }

    @Test fun snapshot_isLowRamDevice_overrides_RAM_classification() {
        // 8 GB physical RAM but the OS reports lowRam=true (Android Go
        // profile, custom OEM build): the picker MUST still cap at
        // Standard + Standard-plus. This is the most-likely source of
        // a real-world device-classification surprise; pin it.
        val context: Context = RuntimeEnvironment.getApplication()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().apply {
            totalMem = 8L * 1024L * 1024L * 1024L
        }
        shadowOf(am).setMemoryInfo(mi)
        shadowOf(am).setIsLowRamDevice(true)

        val detector = DeviceCapacityDetector(context)
        val snap = detector.snapshot()

        assertTrue(snap.isLowRamDevice)
        assertEquals(KdfPreset.STANDARD, snap.recommendedPreset)
        assertEquals(
            setOf(KdfPreset.STANDARD, KdfPreset.STANDARD_PLUS),
            snap.enabledPresets,
        )
    }

    @Test fun snapshot_is_memoised_across_calls_on_the_same_detector() {
        // Double-checked-lock contract: the second call returns the same
        // instance, no re-read of ActivityManager. This matters because
        // SettingsViewModel reads the snapshot from multiple places (the
        // picker, the migrator's defence-in-depth check, the search index)
        // and a cold-read on each call would amplify any underlying cost.
        val context: Context = RuntimeEnvironment.getApplication()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().apply {
            totalMem = 4L * 1024L * 1024L * 1024L
        }
        shadowOf(am).setMemoryInfo(mi)
        shadowOf(am).setIsLowRamDevice(false)

        val detector = DeviceCapacityDetector(context)
        val first = detector.snapshot()
        val second = detector.snapshot()

        assertSame(
            "snapshot() must return the SAME instance after memoisation",
            first,
            second,
        )
    }

    @Test fun separate_detector_instances_each_have_their_own_cache() {
        // Sanity check that memoisation is per-instance (not a global
        // static). A second detector with a different injected context
        // would re-classify; this is the path tests rely on for parallel
        // scenarios with different mocked RAM values.
        val context: Context = RuntimeEnvironment.getApplication()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().apply {
            totalMem = 4L * 1024L * 1024L * 1024L
        }
        shadowOf(am).setMemoryInfo(mi)
        shadowOf(am).setIsLowRamDevice(false)

        val a = DeviceCapacityDetector(context).snapshot()
        val b = DeviceCapacityDetector(context).snapshot()

        assertNotSame(
            "Different detector instances must not share the memoised slot",
            a,
            b,
        )
        // But they classify identically given identical inputs.
        assertEquals(a.recommendedPreset, b.recommendedPreset)
        assertEquals(a.enabledPresets, b.enabledPresets)
    }

    // ── isPresetEnabled() / maxCustomMemoryMb() facade methods ────────────

    @Test fun isPresetEnabled_returns_true_for_CUSTOM_regardless_of_device_tier() {
        // CUSTOM is the design's "always selectable" preset; its slider
        // cap is what enforces device safety, not the row's selectability.
        val context: Context = RuntimeEnvironment.getApplication()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().apply {
            totalMem = 2L * 1024L * 1024L * 1024L  // 2 GB - lowest tier
        }
        shadowOf(am).setMemoryInfo(mi)
        shadowOf(am).setIsLowRamDevice(false)

        val detector = DeviceCapacityDetector(context)
        assertTrue(
            "CUSTOM must be selectable even on the most-restricted device tier",
            detector.isPresetEnabled(KdfPreset.CUSTOM),
        )
    }

    @Test fun isPresetEnabled_returns_false_for_preset_above_device_tier() {
        // On a 2 GB device, Hardened and Maximum must NOT be selectable
        // through the picker. The migration use case re-asserts this
        // (defence in depth); this test pins the picker's primary gate.
        val context: Context = RuntimeEnvironment.getApplication()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().apply {
            totalMem = 2L * 1024L * 1024L * 1024L
        }
        shadowOf(am).setMemoryInfo(mi)
        shadowOf(am).setIsLowRamDevice(false)

        val detector = DeviceCapacityDetector(context)
        assertTrue(detector.isPresetEnabled(KdfPreset.STANDARD))
        assertTrue(detector.isPresetEnabled(KdfPreset.STANDARD_PLUS))
        assertFalse(detector.isPresetEnabled(KdfPreset.HARDENED))
        assertFalse(detector.isPresetEnabled(KdfPreset.MAXIMUM))
    }

    @Test fun maxCustomMemoryMb_facade_matches_companion_object_output() {
        val context: Context = RuntimeEnvironment.getApplication()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().apply {
            totalMem = 4L * 1024L * 1024L * 1024L
        }
        shadowOf(am).setMemoryInfo(mi)
        shadowOf(am).setIsLowRamDevice(false)

        val detector = DeviceCapacityDetector(context)
        val viaFacade = detector.maxCustomMemoryMb()
        val viaCompanion = DeviceCapacityDetector.computeMaxCustomMemoryMb(4_096L)
        assertEquals(viaFacade, viaCompanion)
    }

    @Test fun snapshot_socModel_is_non_null() {
        // We surface SOC_MODEL in bug reports and the placeholder for
        // sub-API-31 devices is the literal string "unknown" (never null).
        // A null leak here would propagate into nullable-error territory
        // through the Settings UI.
        val context: Context = RuntimeEnvironment.getApplication()
        val detector = DeviceCapacityDetector(context)
        val snap = detector.snapshot()
        assertNotNull(snap.socModel)
        assertTrue(
            "SOC_MODEL must be either a real chip name or 'unknown'",
            snap.socModel.isNotBlank(),
        )
    }

    @Test fun snapshot_availableCores_is_at_least_1() {
        // Runtime.getRuntime().availableProcessors() can return 0 under
        // some sandbox conditions; the detector coerces to >= 1 so the
        // value is safe to use in informational UI without a divide-by-
        // zero risk.
        val context: Context = RuntimeEnvironment.getApplication()
        val detector = DeviceCapacityDetector(context)
        val snap = detector.snapshot()
        assertTrue(
            "availableCores must be coerced to at least 1, got ${snap.availableCores}",
            snap.availableCores >= 1,
        )
    }
}
