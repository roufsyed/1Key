package com.roufsyed.onekey.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural locks for [KdfPreset] and [KdfParams].
 *
 * These data structures are the source of truth that every other layer
 * (`CryptoManager.deriveKeyFromPasswordArgon2id`, `KdfMigrator`, the picker
 * UI) reads when it computes Argon2id parameters. Drift between this file
 * and the design - especially silent changes to the (m, t, p) numbers, the
 * `parallelism = 1` lock, or the `CUSTOM` placeholder semantics - would
 * either weaken the verifier across an entire release or cause the picker
 * to apply parameters that no longer match what the FAQ documents.
 *
 * Plain JVM test - no Android types touched anywhere in this surface.
 */
class KdfPresetTest {

    // ── Fixed preset parameter contracts ──────────────────────────────────

    @Test fun standard_matches_OWASP_2023_interactive_auth_recommendation() {
        // OWASP 2023: Argon2id m = 64 MiB, t = 3, p = 1. This is also the
        // historical default `CryptoManager.deriveKeyFromPasswordArgon2id`
        // uses without explicit params, so the Standard preset MUST produce
        // identical numeric output or every existing verifier on disk
        // becomes silently re-derivable under a different config.
        val preset = KdfPreset.STANDARD
        assertEquals("memory (KiB) must be 64 MiB", 65_536, preset.mCostKiB)
        assertEquals("iterations must be 3", 3, preset.tCost)
        assertEquals("parallelism must be 1", 1, preset.parallelism)
        assertEquals("Standard imposes no RAM floor", 0L, preset.minRamMb)
    }

    @Test fun standard_plus_keeps_memory_at_64MiB_and_raises_iterations_to_8() {
        // The intent of Standard-plus is "same memory budget, more iterations"
        // so the unlock cost increases linearly without needing more device
        // RAM. If a future change accidentally bumps the memory column,
        // existing low-RAM devices that were promised Standard-plus would
        // suddenly need to allocate more.
        val preset = KdfPreset.STANDARD_PLUS
        assertEquals(65_536, preset.mCostKiB)
        assertEquals(8, preset.tCost)
        assertEquals(1, preset.parallelism)
        assertEquals(0L, preset.minRamMb)
    }

    @Test fun hardened_doubles_memory_and_requires_at_least_3500MiB_device_ram() {
        // The minRamMb threshold (3500 MiB = ~4 GB after Android's reserve
        // for system processes) IS the picker's gating value. Lower it and
        // we promise Hardened on devices that will swap themselves to
        // death; raise it and we exclude a generation of mid-tier phones
        // unnecessarily. The FAQ and onboarding copy both quote 4 GB+ so
        // the threshold must stay aligned with the user-facing text.
        val preset = KdfPreset.HARDENED
        assertEquals(131_072, preset.mCostKiB)
        assertEquals(4, preset.tCost)
        assertEquals(1, preset.parallelism)
        assertEquals(3500L, preset.minRamMb)
    }

    @Test fun maximum_caps_memory_at_128MiB_and_requires_at_least_6500MiB() {
        // Maximum holds the same memory as Hardened (128 MiB) and raises t
        // to 8. The minRamMb of 6500 (= ~7 GB) excludes mid-tier devices
        // where Maximum would routinely cross 5s/unlock - the design's
        // hostile-UX threshold.
        val preset = KdfPreset.MAXIMUM
        assertEquals(131_072, preset.mCostKiB)
        assertEquals(8, preset.tCost)
        assertEquals(1, preset.parallelism)
        assertEquals(6500L, preset.minRamMb)
    }

    @Test fun custom_carries_sentinel_negative_params_so_misuse_fails_loudly() {
        // CUSTOM does NOT own (m, t) - those come from the user's slider
        // values via a separately-constructed `KdfParams`. The negative
        // sentinels guarantee that any code path which forgets to swap
        // CUSTOM's enum values for the real ones throws via the
        // `require(... > 0)` blocks in `KdfParams.init`, rather than
        // silently running Argon2id under nonsense parameters.
        assertEquals(-1, KdfPreset.CUSTOM.mCostKiB)
        assertEquals(-1, KdfPreset.CUSTOM.tCost)
        assertEquals(
            "Even CUSTOM's parallelism is locked at 1 per OWASP guidance",
            1,
            KdfPreset.CUSTOM.parallelism,
        )
    }

    @Test fun every_fixed_preset_has_parallelism_locked_at_1() {
        // Cross-cutting assertion: parallelism > 1 on commodity Android
        // hardware helps attackers more than defenders (the GPU/ASIC cost
        // ratio shifts unfavorably). The picker has no UI to change this,
        // and the design KDoc on KdfPreset documents the lock; the unit
        // test pins the rule so an "innovation" PR setting one preset to
        // p=2 trips here rather than in code review.
        listOf(
            KdfPreset.STANDARD,
            KdfPreset.STANDARD_PLUS,
            KdfPreset.HARDENED,
            KdfPreset.MAXIMUM,
        ).forEach { preset ->
            assertEquals("${preset.name} parallelism must be 1", 1, preset.parallelism)
        }
    }

    @Test fun displayName_and_description_are_present_and_distinct() {
        // The FAQ comparison table and the picker subtitles both read from
        // these fields. Empty strings would render as blank rows and the
        // user wouldn't know what they're choosing; identical strings
        // across two presets would confuse the picker (Standard vs
        // Standard-plus look the same).
        val presets = KdfPreset.entries
        presets.forEach {
            assertTrue("${it.name} displayName must be non-blank", it.displayName.isNotBlank())
            assertTrue("${it.name} description must be non-blank", it.description.isNotBlank())
        }
        assertEquals(
            "displayNames must be unique across all presets",
            presets.size,
            presets.map { it.displayName }.toSet().size,
        )
    }

    // ── toKdfParams() resolution ───────────────────────────────────────────

    @Test fun toKdfParams_returns_matching_KdfParams_for_STANDARD() {
        val params = KdfPreset.STANDARD.toKdfParams()
        assertEquals(65_536, params.mCostKiB)
        assertEquals(3, params.tCost)
        assertEquals(1, params.parallelism)
        assertEquals(
            "hash length must default to AES-256 key size",
            KdfParams.DEFAULT_HASH_LENGTH_BYTES,
            params.hashLengthBytes,
        )
    }

    @Test fun toKdfParams_returns_matching_KdfParams_for_STANDARD_PLUS() {
        val params = KdfPreset.STANDARD_PLUS.toKdfParams()
        assertEquals(65_536, params.mCostKiB)
        assertEquals(8, params.tCost)
        assertEquals(1, params.parallelism)
        assertEquals(KdfParams.DEFAULT_HASH_LENGTH_BYTES, params.hashLengthBytes)
    }

    @Test fun toKdfParams_returns_matching_KdfParams_for_HARDENED() {
        val params = KdfPreset.HARDENED.toKdfParams()
        assertEquals(131_072, params.mCostKiB)
        assertEquals(4, params.tCost)
        assertEquals(1, params.parallelism)
        assertEquals(KdfParams.DEFAULT_HASH_LENGTH_BYTES, params.hashLengthBytes)
    }

    @Test fun toKdfParams_returns_matching_KdfParams_for_MAXIMUM() {
        val params = KdfPreset.MAXIMUM.toKdfParams()
        assertEquals(131_072, params.mCostKiB)
        assertEquals(8, params.tCost)
        assertEquals(1, params.parallelism)
        assertEquals(KdfParams.DEFAULT_HASH_LENGTH_BYTES, params.hashLengthBytes)
    }

    @Test fun toKdfParams_on_CUSTOM_throws_IllegalStateException() {
        // CUSTOM must explode early rather than silently running Argon2id
        // under the sentinel (-1, -1) values. The exception message guides
        // future callers to construct KdfParams directly from the user's
        // slider values. Catching `IllegalStateException` here pins the
        // exact exception type the call sites rely on for error handling.
        val ex = assertThrows(IllegalStateException::class.java) {
            KdfPreset.CUSTOM.toKdfParams()
        }
        assertNotNull(ex.message)
        assertTrue(
            "Exception message must mention CUSTOM so a stack trace points at the right fix: ${ex.message}",
            ex.message!!.contains("CUSTOM"),
        )
    }

    @Test fun toKdfParams_results_are_value_equal_across_calls() {
        // KdfParams is a data class; two calls to `STANDARD.toKdfParams()`
        // must produce instances that compare equal so downstream cache
        // keys (`Map<KdfParams, Long>` in KdfBenchmark) don't multiply
        // entries on every call. This guards against an accidental
        // refactor that introduces non-data state into KdfParams.
        assertEquals(
            KdfPreset.STANDARD.toKdfParams(),
            KdfPreset.STANDARD.toKdfParams(),
        )
        assertEquals(
            KdfPreset.STANDARD.toKdfParams().hashCode(),
            KdfPreset.STANDARD.toKdfParams().hashCode(),
        )
    }

    @Test fun every_fixed_preset_resolves_to_distinct_KdfParams() {
        // The four fixed presets MUST produce four distinct param tuples,
        // otherwise the picker would let users pick "two strengths" that
        // produce identical verifiers - either a copy-paste bug in the
        // enum or a corruption of one of the (m, t) numbers.
        val params = listOf(
            KdfPreset.STANDARD.toKdfParams(),
            KdfPreset.STANDARD_PLUS.toKdfParams(),
            KdfPreset.HARDENED.toKdfParams(),
            KdfPreset.MAXIMUM.toKdfParams(),
        )
        assertEquals(
            "Each fixed preset must produce a unique KdfParams tuple",
            params.size,
            params.toSet().size,
        )
    }

    // ── KdfParams validation ───────────────────────────────────────────────

    @Test fun KdfParams_init_rejects_non_positive_mCost() {
        assertThrows(IllegalArgumentException::class.java) {
            KdfParams(mCostKiB = 0, tCost = 3, parallelism = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KdfParams(mCostKiB = -1, tCost = 3, parallelism = 1)
        }
    }

    @Test fun KdfParams_init_rejects_non_positive_tCost() {
        assertThrows(IllegalArgumentException::class.java) {
            KdfParams(mCostKiB = 65_536, tCost = 0, parallelism = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KdfParams(mCostKiB = 65_536, tCost = -1, parallelism = 1)
        }
    }

    @Test fun KdfParams_init_rejects_non_positive_parallelism() {
        assertThrows(IllegalArgumentException::class.java) {
            KdfParams(mCostKiB = 65_536, tCost = 3, parallelism = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KdfParams(mCostKiB = 65_536, tCost = 3, parallelism = -1)
        }
    }

    @Test fun KdfParams_init_rejects_non_positive_hashLength() {
        assertThrows(IllegalArgumentException::class.java) {
            KdfParams(mCostKiB = 65_536, tCost = 3, parallelism = 1, hashLengthBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KdfParams(mCostKiB = 65_536, tCost = 3, parallelism = 1, hashLengthBytes = -16)
        }
    }

    @Test fun KdfParams_default_hash_length_matches_AES_256_key_size() {
        // 32 bytes / 256 bits. This MUST match `CryptoManager.ARGON2_HASH_LENGTH`
        // or `wrapKey`/`unwrapKey` would feed a non-AES-256 key into the GCM
        // path and fail. The constant is the single source of truth.
        assertEquals(32, KdfParams.DEFAULT_HASH_LENGTH_BYTES)
    }

    @Test fun KdfParams_equality_is_structural() {
        // Two KdfParams with the same numeric content must compare equal.
        // KdfBenchmark uses `Map<KdfParams, Long>` to cache wall-clock
        // timings per parameter tuple; reference equality would cause every
        // call to `benchmark(STANDARD.toKdfParams())` to write a fresh
        // entry instead of replacing the cached one.
        val a = KdfParams(mCostKiB = 96 * 1024, tCost = 5, parallelism = 1)
        val b = KdfParams(mCostKiB = 96 * 1024, tCost = 5, parallelism = 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun KdfParams_with_different_m_is_not_equal() {
        val a = KdfParams(mCostKiB = 65_536, tCost = 3, parallelism = 1)
        val b = KdfParams(mCostKiB = 131_072, tCost = 3, parallelism = 1)
        assertNotEquals(a, b)
    }

    @Test fun KdfParams_with_different_t_is_not_equal() {
        val a = KdfParams(mCostKiB = 65_536, tCost = 3, parallelism = 1)
        val b = KdfParams(mCostKiB = 65_536, tCost = 8, parallelism = 1)
        assertNotEquals(a, b)
    }

    // ── currentActiveMt() resolution ───────────────────────────────────────
    //
    // The Custom dialog uses this helper to decide whether the slider
    // position equals the user's already-active configuration. A wrong
    // answer here either (a) lets the user commit a no-op migration that
    // still pays an Argon2id re-derive, or (b) refuses a genuine change.

    @Test fun currentActiveMt_returns_64_3_for_STANDARD() {
        assertEquals(64 to 3, currentActiveMt(KdfPreset.STANDARD, null))
    }

    @Test fun currentActiveMt_returns_64_8_for_STANDARD_PLUS() {
        assertEquals(64 to 8, currentActiveMt(KdfPreset.STANDARD_PLUS, null))
    }

    @Test fun currentActiveMt_returns_128_4_for_HARDENED() {
        assertEquals(128 to 4, currentActiveMt(KdfPreset.HARDENED, null))
    }

    @Test fun currentActiveMt_returns_128_8_for_MAXIMUM() {
        assertEquals(128 to 8, currentActiveMt(KdfPreset.MAXIMUM, null))
    }

    @Test fun currentActiveMt_for_fixed_presets_ignores_customMt() {
        // Defence in depth: a stray non-null customMt while a fixed preset
        // is active (impossible by construction in the repository, but
        // worth pinning) must not pollute the comparison value.
        assertEquals(
            64 to 3,
            currentActiveMt(KdfPreset.STANDARD, customMt = 96 to 5),
        )
    }

    @Test fun currentActiveMt_returns_customMt_for_CUSTOM() {
        assertEquals(
            96 to 5,
            currentActiveMt(KdfPreset.CUSTOM, customMt = 96 to 5),
        )
    }

    @Test fun currentActiveMt_returns_null_for_CUSTOM_with_null_customMt() {
        // Cold-start race: active preset is CUSTOM but the StateFlow for
        // custom params has not emitted yet. Callers must treat null as
        // "no comparison" and allow Apply, otherwise the dialog locks
        // until the flow catches up.
        assertEquals(null, currentActiveMt(KdfPreset.CUSTOM, customMt = null))
    }
}
