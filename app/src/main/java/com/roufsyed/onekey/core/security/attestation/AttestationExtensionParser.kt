package com.roufsyed.onekey.core.security.attestation

import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.BERTags
import java.security.cert.X509Certificate

/**
 * Parses the Android Key Attestation extension (OID [ATTESTATION_EXTENSION_OID])
 * from an attestation leaf certificate, extracting only the fields the
 * boot-state policy needs: [ParsedAttestation.securityLevel],
 * [ParsedAttestation.deviceLocked], [ParsedAttestation.verifiedBootState], and
 * the [ParsedAttestation.attestationChallenge] (so the caller can bind the
 * record to its own key-generation request).
 *
 * Design constraints (deliberate - see the security roadmap):
 *  - Uses ONLY `org.bouncycastle.asn1.*` and never registers the JCE provider,
 *    so R8 tree-shakes BouncyCastle down to the ASN.1 subset.
 *  - **Version-robust:** the top-level `KeyDescription` fields are read by their
 *    version-stable positions, and the `RootOfTrust` is located by *scanning*
 *    the hardware-enforced `AuthorizationList` for context tag `[704]`, skipping
 *    every other (possibly newer, unknown) field. New attestation versions add
 *    fields; they are skipped, never parsed - so no per-version maintenance.
 *  - Reads `RootOfTrust` ONLY from the hardware-enforced list, never the
 *    software-enforced one (which an attacker on a compromised OS can populate).
 *  - **Fail-safe:** any structural surprise, wrong type, overflow, or exception
 *    yields `null`. The caller maps `null` to `AttestationResult.Unavailable`
 *    (silent, Path A). The parser never throws and never returns a partial or
 *    partially-trusted record.
 *
 * The ASN.1 shape (AOSP `KeyDescription`, stable across attestation v1..v400):
 * ```
 * KeyDescription ::= SEQUENCE {
 *   attestationVersion        INTEGER,            -- 0
 *   attestationSecurityLevel  ENUMERATED,         -- 1  <-- read
 *   keymasterVersion          INTEGER,            -- 2
 *   keymasterSecurityLevel    ENUMERATED,         -- 3
 *   attestationChallenge      OCTET STRING,       -- 4  <-- read
 *   uniqueId                  OCTET STRING,       -- 5
 *   softwareEnforced          AuthorizationList,  -- 6
 *   hardwareEnforced          AuthorizationList,  -- 7  <-- scan for [704]
 * }
 * RootOfTrust ::= SEQUENCE {
 *   verifiedBootKey    OCTET STRING,              -- 0
 *   deviceLocked       BOOLEAN,                   -- 1  <-- read
 *   verifiedBootState  ENUMERATED,                -- 2  <-- read
 *   verifiedBootHash   OCTET STRING OPTIONAL,     -- 3  (absent on attestation v1/v2)
 * }
 * ```
 */
object AttestationExtensionParser {

    /** `id-ce-keyAttestation` - the Android Keystore attestation extension. */
    const val ATTESTATION_EXTENSION_OID: String = "1.3.6.1.4.1.11129.2.1.17"

    // KeyDescription field indices (version-stable).
    private const val IDX_ATTESTATION_SECURITY_LEVEL = 1
    private const val IDX_ATTESTATION_CHALLENGE = 4
    private const val IDX_HARDWARE_ENFORCED = 7
    private const val KEY_DESCRIPTION_MIN_FIELDS = 8

    // AuthorizationList context tag for `rootOfTrust [704] EXPLICIT RootOfTrust`.
    private const val TAG_ROOT_OF_TRUST = 704

    // RootOfTrust field indices.
    private const val IDX_DEVICE_LOCKED = 1
    private const val IDX_VERIFIED_BOOT_STATE = 2
    private const val ROOT_OF_TRUST_MIN_FIELDS = 3

    /**
     * Parse the attestation record from [leaf]. Returns `null` if [leaf] carries
     * no attestation extension or the extension cannot be fully, safely parsed.
     */
    fun parse(leaf: X509Certificate): ParsedAttestation? {
        val rawExtnValue = leaf.getExtensionValue(ATTESTATION_EXTENSION_OID) ?: return null
        return parseExtensionValue(rawExtnValue)
    }

    /**
     * Parse a raw X.509 `extnValue` - the DER of an OCTET STRING wrapping the
     * `KeyDescription`. Exposed so host-JVM unit tests can feed synthetic or
     * captured extension bytes without constructing an [X509Certificate].
     */
    fun parseExtensionValue(rawExtnValue: ByteArray): ParsedAttestation? = runCatching {
        // extnValue is an OCTET STRING whose content is the DER KeyDescription.
        val inner = (ASN1Primitive.fromByteArray(rawExtnValue) as? ASN1OctetString)
            ?.octets ?: return null
        val keyDescription = ASN1Primitive.fromByteArray(inner) as? ASN1Sequence ?: return null
        if (keyDescription.size() < KEY_DESCRIPTION_MIN_FIELDS) return null

        val securityLevelTag = ASN1Enumerated
            .getInstance(keyDescription.getObjectAt(IDX_ATTESTATION_SECURITY_LEVEL))
            .safeIntOrNull() ?: return null
        val securityLevel = AttestationSecurityLevel.fromTag(securityLevelTag)

        val challenge = ASN1OctetString
            .getInstance(keyDescription.getObjectAt(IDX_ATTESTATION_CHALLENGE))
            .octets

        val hardwareEnforced = ASN1Sequence
            .getInstance(keyDescription.getObjectAt(IDX_HARDWARE_ENFORCED))
        val rootOfTrust = findRootOfTrust(hardwareEnforced) ?: return null
        if (rootOfTrust.size() < ROOT_OF_TRUST_MIN_FIELDS) return null

        val deviceLocked = ASN1Boolean
            .getInstance(rootOfTrust.getObjectAt(IDX_DEVICE_LOCKED))
            .isTrue

        val verifiedBootStateTag = ASN1Enumerated
            .getInstance(rootOfTrust.getObjectAt(IDX_VERIFIED_BOOT_STATE))
            .safeIntOrNull() ?: return null
        val verifiedBootState = VerifiedBootState.fromTag(verifiedBootStateTag)

        ParsedAttestation(
            securityLevel = securityLevel,
            deviceLocked = deviceLocked,
            verifiedBootState = verifiedBootState,
            attestationChallenge = challenge,
        )
    }.getOrNull()

    /**
     * Locate `rootOfTrust [704] EXPLICIT RootOfTrust` in an AuthorizationList by
     * scanning for its context tag and skipping every other field. Returns
     * `null` if absent. Explicitly requires the context tag class so we can
     * never mistake an application/private-tagged field for it.
     */
    private fun findRootOfTrust(authorizationList: ASN1Sequence): ASN1Sequence? {
        for (i in 0 until authorizationList.size()) {
            val entry = authorizationList.getObjectAt(i)
            if (entry is ASN1TaggedObject &&
                entry.tagClass == BERTags.CONTEXT_SPECIFIC &&
                entry.tagNo == TAG_ROOT_OF_TRUST
            ) {
                // [704] is EXPLICIT: unwrap the tag to reach the inner SEQUENCE.
                return ASN1Sequence.getInstance(entry, true)
            }
        }
        return null
    }

    /**
     * Read an ENUMERATED as an Int, rejecting absurd/overflowing values (a
     * hostile cert could encode a huge integer that would silently truncate).
     */
    private fun ASN1Enumerated.safeIntOrNull(): Int? =
        value.takeIf { it.bitLength() <= 31 }?.toInt()
}
