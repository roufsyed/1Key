package com.roufsyed.onekey.feature.passkey.verify

import androidx.credentials.provider.CallingAppInfo
import com.roufsyed.onekey.core.domain.passkey.CallerIdentity
import com.roufsyed.onekey.core.domain.passkey.CallerKind
import com.roufsyed.onekey.core.domain.passkey.CallerPolicy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android adapter for [CallerPolicy]: turns the platform-attested
 * [CallingAppInfo] into a [CallerIdentity] and resolves it. The only
 * platform-specific work is reading the signing lineage and asking
 * `CallingAppInfo.getOrigin` whether the allowlist vouches for the caller.
 */
@Singleton
class CallerVerifier @Inject constructor(
    private val allowlist: PrivilegedAllowlist,
) {
    suspend fun resolve(info: CallingAppInfo): CallerKind = CallerPolicy.resolve(identityOf(info, allowlist.json()))

    private fun identityOf(info: CallingAppInfo, allowlistJson: String): CallerIdentity {
        val signing = info.signingInfoCompat
        // signingCertificateHistory lists the lineage oldest first, so the current
        // signer is the last entry. Packages that never rotated have only
        // apkContentsSigners.
        val certificates = if (signing.hasPastSigningCertificates) signing.signingCertificateHistory else signing.apkContentsSigners
        val lineage = certificates.map { CallerPolicy.certificateSha256Hex(it.toByteArray()) }
        val claimedOrigin = info.isOriginPopulated()
        val privilegedOrigin = if (claimedOrigin) {
            // Throws IllegalStateException when the caller set an origin but is
            // not privileged; a malformed allowlist would throw IllegalArgumentException.
            runCatching { info.getOrigin(allowlistJson) }.getOrNull()
        } else null
        return CallerIdentity(
            packageName = info.packageName,
            currentCertSha256 = lineage.lastOrNull().orEmpty(),
            lineageSha256 = lineage.toSet(),
            multipleSigners = signing.hasMultipleSigners || lineage.isEmpty(),
            claimedOrigin = claimedOrigin,
            privilegedOrigin = privilegedOrigin,
        )
    }
}
