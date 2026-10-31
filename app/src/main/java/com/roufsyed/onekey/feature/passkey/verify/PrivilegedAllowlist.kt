package com.roufsyed.onekey.feature.passkey.verify

import android.content.Context
import android.os.Build
import com.roufsyed.onekey.core.domain.passkey.AllowlistSnapshotMeta
import com.roufsyed.onekey.core.domain.passkey.PrivilegedApp
import com.roufsyed.onekey.core.domain.passkey.PrivilegedAppList
import com.roufsyed.onekey.feature.passkey.data.PasskeyPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The trust anchor between a browser's claimed origin and the vault
 * (passkeys plan, section 5.2). Composes, in this order:
 *
 *  1. `assets/passkeys/privileged_apps_google.json`: a verbatim snapshot of
 *     Google's list, refreshed by `scripts/refresh-passkey-allowlist.sh`.
 *  2. `assets/passkeys/privileged_apps_community.json`: F-Droid style browsers
 *     Google does not list.
 *  3. `assets/passkeys/privileged_apps_fdroid.json`: present in the F-Droid
 *     flavour only, f-droid.org's own signing keys for the browsers it builds.
 *  4. The user's trusted browsers from [PasskeyPreferences].
 *
 * Nothing is ever fetched at runtime. `userdebug` fingerprints are dropped on
 * production devices. The merged document is handed to
 * `CallingAppInfo.getOrigin`, which does the package and certificate match
 * in-process.
 */
@Singleton
class PrivilegedAllowlist @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PasskeyPreferences,
) {
    private val bundled: List<PrivilegedApp> by lazy {
        PrivilegedAppList.merge(
            readAsset(ASSET_GOOGLE),
            readAsset(ASSET_COMMUNITY),
            readAsset(ASSET_FDROID),
        )
    }

    /** Snapshot metadata of the Google list, for Settings and diagnostics. Null if the sidecar is unreadable. */
    val snapshotMeta: AllowlistSnapshotMeta? by lazy {
        runCatching { AllowlistSnapshotMeta.parse(context.assets.open(ASSET_GOOGLE_META).bufferedReader().readText()) }.getOrNull()
    }

    @Volatile private var cachedUserJson: String? = null
    @Volatile private var cachedMerged: String? = null

    /** The merged allowlist as JSON in Google's schema, filtered for this device. */
    suspend fun json(): String {
        val userJson = prefs.trustedBrowsersJson().first()
        cachedMerged?.takeIf { cachedUserJson == userJson }?.let { return it }
        val user = userJson?.let { runCatching { PrivilegedAppList.parse(it) }.getOrDefault(emptyList()) }.orEmpty()
        val merged = PrivilegedAppList.toJson(
            PrivilegedAppList.filterForDevice(PrivilegedAppList.merge(bundled, user), isUserdebugDevice = Build.TYPE == "userdebug"),
        )
        cachedUserJson = userJson
        cachedMerged = merged
        return merged
    }

    /** Counts for the Settings summary line. */
    fun bundledCounts(): BundledCounts = BundledCounts(
        google = readAsset(ASSET_GOOGLE).size,
        community = readAsset(ASSET_COMMUNITY).size,
        fdroid = readAsset(ASSET_FDROID).size,
    )

    data class BundledCounts(val google: Int, val community: Int, val fdroid: Int)

    private fun readAsset(name: String): List<PrivilegedApp> = try {
        context.assets.open(name).bufferedReader().use { PrivilegedAppList.parse(it.readText()) }
    } catch (_: FileNotFoundException) {
        emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private companion object {
        const val ASSET_GOOGLE = "passkeys/privileged_apps_google.json"
        const val ASSET_GOOGLE_META = "passkeys/privileged_apps_google.meta.json"
        const val ASSET_COMMUNITY = "passkeys/privileged_apps_community.json"
        const val ASSET_FDROID = "passkeys/privileged_apps_fdroid.json"
    }
}
