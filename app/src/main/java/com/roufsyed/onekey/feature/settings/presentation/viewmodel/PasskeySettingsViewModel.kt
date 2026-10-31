package com.roufsyed.onekey.feature.settings.presentation.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roufsyed.onekey.core.domain.passkey.AllowlistSnapshotMeta
import com.roufsyed.onekey.core.domain.passkey.CallerPolicy
import com.roufsyed.onekey.core.domain.passkey.PrivilegedAppList
import com.roufsyed.onekey.feature.passkey.data.PasskeyPreferences
import com.roufsyed.onekey.feature.passkey.service.OneKeyCredentialProviderService
import com.roufsyed.onekey.feature.passkey.verify.PrivilegedAllowlist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Backs `SettingsPasskeysScreen`: the soft kill switch, the OS-level provider
 * status, the bundled allowlist summary and the user's trusted browsers.
 *
 * Trusting a browser stores its package name and every certificate in its
 * signing lineage, read from the PackageManager at the moment of trust. The
 * picker lists only installed apps that handle https links and are not already
 * on a bundled list, so the user cannot "trust" something that is not a browser
 * or re-add one that works already.
 */
@HiltViewModel
class PasskeySettingsViewModel @Inject constructor(
    private val prefs: PasskeyPreferences,
    private val allowlist: PrivilegedAllowlist,
    private val application: Application,
) : ViewModel() {

    enum class SystemStatus {
        UNSUPPORTED,
        UNKNOWN,
        ENABLED,
        NOT_ENABLED,
        /**
         * 1Key is the autofill service but its passkey provider is not enabled.
         * This is the upgrade case: Android enables only the components an app
         * had when the user chose it, so a user who picked 1Key before passkeys
         * existed must pick it again. Seen on first install of the feature.
         */
        AUTOFILL_ONLY,
    }

    data class TrustedBrowser(val packageName: String, val label: String, val certificateCount: Int, val installed: Boolean)
    data class InstalledBrowser(val packageName: String, val label: String)
    data class AllowlistSummary(val google: Int, val community: Int, val fdroid: Int, val snapshotDate: LocalDate?, val stale: Boolean)

    val isEnabled: StateFlow<Boolean> = prefs.isProviderEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val trustedBrowsers: StateFlow<List<TrustedBrowser>> = prefs.trustedBrowsersJson()
        .map { json -> parseTrusted(json) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _systemStatus = MutableStateFlow(querySystemStatus())
    val systemStatus: StateFlow<SystemStatus> = _systemStatus.asStateFlow()

    val allowlistSummary: AllowlistSummary by lazy {
        val counts = allowlist.bundledCounts()
        val meta = allowlist.snapshotMeta
        AllowlistSummary(
            google = counts.google,
            community = counts.community,
            fdroid = counts.fdroid,
            snapshotDate = meta?.fetchedAt,
            stale = meta?.isOlderThan(AllowlistSnapshotMeta.WARN_AFTER_DAYS, LocalDate.now()) ?: true,
        )
    }

    val supportedOnThisDevice: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setProviderEnabled(enabled) }
    }

    /** Re-reads the OS-level provider status; the screen calls it on every resume. */
    fun refreshSystemStatus() {
        _systemStatus.value = querySystemStatus()
    }

    /** Intent that opens Android's own passkey-provider picker for 1Key, with a generic fallback. */
    fun systemSettingsIntents(): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Intent(android.provider.Settings.ACTION_CREDENTIAL_PROVIDER).apply { data = Uri.parse("package:${application.packageName}") })
            add(Intent(android.provider.Settings.ACTION_CREDENTIAL_PROVIDER))
        }
        add(Intent(android.provider.Settings.ACTION_SETTINGS))
    }

    /** Installed browsers not yet trusted by any list, for the "add a browser" picker. */
    fun installedBrowsersToOffer(): List<InstalledBrowser> {
        val pm = application.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = runCatching { pm.queryIntentActivities(probe, PackageManager.MATCH_ALL) }.getOrDefault(emptyList())
        val excluded = allowlist.bundledPackages() + trustedBrowsers.value.map { it.packageName } + application.packageName
        return resolved
            .map { it.activityInfo.packageName }
            .distinct()
            .filterNot { it in excluded }
            .map { pkg -> InstalledBrowser(pkg, labelOf(pkg) ?: pkg) }
            .sortedBy { it.label.lowercase() }
    }

    /** Trusts [packageName] with its full current signing lineage. No effect if the lineage cannot be read. */
    fun trust(packageName: String) {
        val lineage = signingLineage(packageName) ?: return
        val entry = PrivilegedAppList.userEntry(packageName, lineage) ?: return
        viewModelScope.launch {
            val current = prefs.trustedBrowsersJson().first()?.let { PrivilegedAppList.parse(it) }.orEmpty()
            prefs.setTrustedBrowsersJson(PrivilegedAppList.toJson(PrivilegedAppList.merge(current, listOf(entry))))
        }
    }

    fun untrust(packageName: String) {
        viewModelScope.launch {
            val current = prefs.trustedBrowsersJson().first()?.let { PrivilegedAppList.parse(it) }.orEmpty()
            val remaining = current.filterNot { it.packageName == packageName }
            prefs.setTrustedBrowsersJson(if (remaining.isEmpty()) null else PrivilegedAppList.toJson(remaining))
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun parseTrusted(json: String?): List<TrustedBrowser> =
        json?.let { runCatching { PrivilegedAppList.parse(it) }.getOrNull() }.orEmpty().map { app ->
            TrustedBrowser(
                packageName = app.packageName,
                label = labelOf(app.packageName) ?: app.packageName,
                certificateCount = app.fingerprints.size,
                installed = labelOf(app.packageName) != null,
            )
        }

    private fun labelOf(packageName: String): String? = runCatching {
        val pm = application.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signingLineage(packageName: String): List<String>? = runCatching {
        val pm = application.packageManager
        val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo ?: return null
        if (signing.hasMultipleSigners()) return null
        val certs = if (signing.hasPastSigningCertificates()) signing.signingCertificateHistory else signing.apkContentsSigners
        certs.map { CallerPolicy.certificateSha256Hex(it.toByteArray()) }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun querySystemStatus(): SystemStatus {
        if (!supportedOnThisDevice) return SystemStatus.UNSUPPORTED
        val enabled = isPasskeyProviderEnabled() ?: return SystemStatus.UNKNOWN
        if (enabled) return SystemStatus.ENABLED
        return if (isAutofillProvider()) SystemStatus.AUTOFILL_ONLY else SystemStatus.NOT_ENABLED
    }

    /**
     * Android 15 and later answer directly. Android 14 has no public query, so
     * the enabled-provider list in Settings.Secure is read as a best effort;
     * null means "could not tell".
     */
    private fun isPasskeyProviderEnabled(): Boolean? {
        val component = ComponentName(application, OneKeyCredentialProviderService::class.java)
        if (Build.VERSION.SDK_INT >= 35) {
            return runCatching {
                val manager = application.getSystemService(android.credentials.CredentialManager::class.java) ?: return null
                manager.isEnabledCredentialProviderService(component)
            }.getOrNull()
        }
        return runCatching {
            val enabled = android.provider.Settings.Secure.getString(application.contentResolver, SECURE_CREDENTIAL_SERVICE) ?: return null
            enabled.split(':', ';').any { ComponentName.unflattenFromString(it.trim()) == component }
        }.getOrNull()
    }

    private fun isAutofillProvider(): Boolean = runCatching {
        application.getSystemService(android.view.autofill.AutofillManager::class.java)?.hasEnabledAutofillServices() ?: false
    }.getOrDefault(false)

    private companion object {
        /** Settings.Secure key the credential manager service keeps its enabled providers under (AOSP). */
        const val SECURE_CREDENTIAL_SERVICE = "credential_service"
    }
}
