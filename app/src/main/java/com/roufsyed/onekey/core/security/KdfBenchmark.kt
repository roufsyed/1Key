package com.roufsyed.onekey.core.security

import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG_BENCH = "KdfBenchmark"

// Per-call hard cap. Anything past this is hostile UX and likely indicates
// the preset is unrunnable on this hardware.
private const val BENCHMARK_TIMEOUT_MS = 30_000L

// Median of N runs absorbs first-run cold-cache jitter while staying cheap.
private const val BENCHMARK_RUNS = 3

// Salt bytes for the throwaway benchmark derivation. 32 bytes matches what
// the production verifier uses; no security need - just keeps the input
// distribution identical to the real call path.
private const val BENCHMARK_SALT_LEN = 32

// Throwaway password bytes. NOT a CharArray - we don't want to involve the
// JVM String/Char encoding path; uniform random bytes feed Argon2id directly.
private const val BENCHMARK_PASSWORD_LEN = 16

// DataStore key prefix; concatenated with a stable params-hash slug.
private const val KEY_PREFIX_TIMING = "kdf_bench_ms_"

// Stable cache-invalidation fingerprint. Bumps on device upgrade (OTA),
// architecture change, or kernel rev. Argon2id throughput shifts with the
// underlying CPU, so a Build.FINGERPRINT delta invalidates the entire cache.
private val KEY_FINGERPRINT = stringPreferencesKey("kdf_bench_fingerprint")

/**
 * Argon2id wall-clock benchmark with per-params persistent cache.
 *
 * Used by the Settings > Encryption strength picker to render a realistic
 * "Estimated unlock: ~Xms" subtitle for each preset, and by the Custom
 * dialog's "Estimate" button before the user commits to a custom config.
 *
 * Design:
 *  - One Mutex serialises all benchmark runs so a manual "Refresh benchmark"
 *    tap cannot pile on top of a background bootstrap run. Argon2id at the
 *    higher presets eats hundreds of MiB; running two at once on a 4 GB
 *    device is a fast path to OOM.
 *  - Each [benchmark] call runs Argon2id [BENCHMARK_RUNS] times and reports
 *    the median wall-clock ms. The first run is often slow due to native
 *    library load, JIT, and CPU governor ramp; median over 3 samples drops
 *    that without exposing first-call latency to the user.
 *  - Results are cached in the same DataStore the rest of the app uses
 *    (`onekey_prefs`). Key derivation: `kdf_bench_ms_<params-slug>` where
 *    the slug encodes `(m, t, p, hashLength)` so different param tuples
 *    cache independently. The fingerprint key [KEY_FINGERPRINT] invalidates
 *    the entire cache when `Build.FINGERPRINT` changes (device upgrade or
 *    cross-device restore picks up a new CPU profile).
 *  - Failures (OOM, Argon2id native crash, timeout) are NOT persisted. The
 *    caller observes a `null` cached value and may either retry or mark the
 *    preset as "Too slow on this device" in the picker.
 *
 * Threading:
 *  - All Argon2id work runs on [Dispatchers.Default]. Native Argon2id is
 *    CPU-bound; IO dispatcher is wrong for it (it'd starve actual IO work).
 *  - The DataStore reads/writes run on the caller's dispatcher; both
 *    operations are cheap (single int read/write to disk).
 */
@Singleton
class KdfBenchmark @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private val mutex = Mutex()
    private val random = SecureRandom()

    private val _cachedTimings = MutableStateFlow<Map<KdfParams, Long>>(emptyMap())
    val cachedTimings: StateFlow<Map<KdfParams, Long>> = _cachedTimings.asStateFlow()

    /**
     * Returns the cached benchmark for [params], or `null` if it has not yet
     * been measured (or was invalidated by a Build.FINGERPRINT change).
     *
     * This method DOES NOT trigger a measurement; callers wanting an
     * on-demand benchmark must call [benchmark] explicitly.
     */
    suspend fun getCachedTiming(params: KdfParams): Long? {
        ensureFingerprintFresh()
        val snapshot = dataStore.data.first()
        val key = longPreferencesKey(KEY_PREFIX_TIMING + paramsSlug(params))
        return snapshot[key]
    }

    /**
     * Runs Argon2id with [params] on [Dispatchers.Default], returning the
     * median wall-clock ms across [BENCHMARK_RUNS] runs. Caches the result
     * keyed on [params] so subsequent reads via [getCachedTiming] hit the
     * DataStore without re-running.
     *
     * Returns `null` if the benchmark fails (OOM, native crash, exceeds
     * [BENCHMARK_TIMEOUT_MS]). Failures are not persisted; the caller can
     * call [benchmark] again later.
     *
     * Reentrancy: serialised via [mutex]. A second concurrent call queues
     * behind the first. This prevents two Argon2id 128 MiB allocations
     * landing simultaneously on a 4 GB device.
     */
    suspend fun benchmark(params: KdfParams): Long? = mutex.withLock {
        ensureFingerprintFresh()
        try {
            val median = withContext(Dispatchers.Default) {
                withTimeout(BENCHMARK_TIMEOUT_MS) {
                    runMedian(params)
                }
            }
            persistTiming(params, median)
            median
        } catch (oom: OutOfMemoryError) {
            // OutOfMemoryError is a Throwable, not an Exception; coroutines do
            // not propagate it through normal channels. Catch it here so the
            // caller can fall back to marking the preset unrunnable.
            Log.w(TAG_BENCH, "OOM during benchmark for $params", oom)
            null
        } catch (t: Throwable) {
            Log.w(TAG_BENCH, "Benchmark failed for $params", t)
            null
        }
    }

    /**
     * Convenience wrapper for the picker bootstrap path: returns cached
     * timing if available, otherwise runs [benchmark] and returns the new
     * value (or null on failure).
     *
     * Mirrors a disk-cache hit into [_cachedTimings] before returning so the
     * picker StateFlow reflects persisted values on a process-restart visit.
     * Without this, the in-memory map stays empty (only [persistTiming] fills
     * it), and every preset chip would read "Estimating unlock time..." until
     * the user taps Refresh Benchmark - even though the timing is on disk.
     */
    suspend fun benchmarkIfMissing(params: KdfParams): Long? {
        getCachedTiming(params)?.let { cached ->
            if (_cachedTimings.value[params] != cached) {
                _cachedTimings.value = _cachedTimings.value + (params to cached)
            }
            return cached
        }
        return benchmark(params)
    }

    private fun runMedian(params: KdfParams): Long {
        val samples = LongArray(BENCHMARK_RUNS)
        for (i in 0 until BENCHMARK_RUNS) {
            samples[i] = runOnce(params)
        }
        samples.sort()
        return samples[BENCHMARK_RUNS / 2]
    }

    private fun runOnce(params: KdfParams): Long {
        val salt = ByteArray(BENCHMARK_SALT_LEN).also(random::nextBytes)
        val password = ByteArray(BENCHMARK_PASSWORD_LEN).also(random::nextBytes)
        return try {
            val t0 = System.nanoTime()
            val raw = Argon2Kt().hash(
                mode = Argon2Mode.ARGON2_ID,
                password = password,
                salt = salt,
                tCostInIterations = params.tCost,
                mCostInKibibyte = params.mCostKiB,
                parallelism = params.parallelism,
                hashLengthInBytes = params.hashLengthBytes,
            ).rawHashAsByteArray()
            val elapsedNs = System.nanoTime() - t0
            // Zero the derived hash immediately. It's keyed off throwaway
            // input bytes, so it's not security-sensitive in practice, but
            // we keep the same hygiene the production path uses.
            raw.fill(0)
            elapsedNs / 1_000_000L
        } finally {
            password.fill(0)
        }
    }

    private suspend fun persistTiming(params: KdfParams, ms: Long) {
        val key = longPreferencesKey(KEY_PREFIX_TIMING + paramsSlug(params))
        dataStore.edit { prefs ->
            prefs[key] = ms
            prefs[KEY_FINGERPRINT] = Build.FINGERPRINT.orEmpty()
        }
        _cachedTimings.value = _cachedTimings.value + (params to ms)
    }

    /**
     * Clears the entire benchmark cache if the persisted fingerprint differs
     * from the current device's [Build.FINGERPRINT]. Argon2id throughput is
     * a function of the CPU/kernel/firmware combination; a device upgrade
     * (OTA) or cross-device restore can shift it dramatically.
     */
    private suspend fun ensureFingerprintFresh() {
        val snapshot = dataStore.data.first()
        val current = Build.FINGERPRINT.orEmpty()
        val stored = snapshot[KEY_FINGERPRINT]
        if (stored != null && stored == current) return
        dataStore.edit { prefs ->
            // Remove only the per-params timing keys; leave unrelated entries
            // (everything in the same DataStore file) alone.
            val toRemove = prefs.asMap().keys.filter { it.name.startsWith(KEY_PREFIX_TIMING) }
            toRemove.forEach { prefs.remove(it) }
            prefs[KEY_FINGERPRINT] = current
        }
        _cachedTimings.value = emptyMap()
    }

    companion object {

        /**
         * Stable, filesystem-safe slug encoding [KdfParams] for use as a
         * DataStore key suffix. Exposed for tests so they can poke specific
         * cache entries without going through [persistTiming].
         */
        fun paramsSlug(params: KdfParams): String =
            "m${params.mCostKiB}_t${params.tCost}_p${params.parallelism}_h${params.hashLengthBytes}"
    }
}
