package com.roufsyed.onekey.core.security.attestation

/**
 * `VerifiedBootState` from the Key Attestation `RootOfTrust`
 * (extension OID 1.3.6.1.4.1.11129.2.1.17). The integer [tag] values are the
 * AOSP-defined enumeration and MUST match exactly - they drive the boot-state
 * policy, so do not renumber:
 *
 *  - [VERIFIED]    (0) stock, OEM-signed, verified boot intact
 *  - [SELF_SIGNED] (1) custom OS signed with the user's own key, verified boot
 *                      intact (e.g. a re-locked GrapheneOS / CalyxOS device)
 *  - [UNVERIFIED]  (2) bootloader unlocked / verified boot not enforced
 *  - [FAILED]      (3) verified boot failed
 *
 * [UNKNOWN] is our own sentinel for an absent RootOfTrust or an integer we do
 * not recognise; it is never a value the platform emits.
 */
enum class VerifiedBootState(val tag: Int) {
    VERIFIED(0),
    SELF_SIGNED(1),
    UNVERIFIED(2),
    FAILED(3),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromTag(tag: Int): VerifiedBootState =
            entries.firstOrNull { it != UNKNOWN && it.tag == tag } ?: UNKNOWN
    }
}

/**
 * `attestationSecurityLevel` / `keymasterSecurityLevel` enumeration (AOSP).
 * A [SOFTWARE] level means the attestation is not rooted in secure hardware and
 * therefore cannot be trusted as a boot-state signal.
 */
enum class AttestationSecurityLevel(val tag: Int) {
    SOFTWARE(0),
    TRUSTED_ENVIRONMENT(1),
    STRONG_BOX(2),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromTag(tag: Int): AttestationSecurityLevel =
            entries.firstOrNull { it != UNKNOWN && it.tag == tag } ?: UNKNOWN
    }
}

/**
 * The subset of the attestation record 1Key needs, extracted from the leaf
 * certificate's attestation extension. [securityLevel] and [attestationChallenge]
 * are top-level `KeyDescription` fields; [deviceLocked] and [verifiedBootState]
 * come from the hardware-enforced `RootOfTrust`. Deliberately narrow - we never
 * parse the full AuthorizationList.
 */
data class ParsedAttestation(
    val securityLevel: AttestationSecurityLevel,
    val deviceLocked: Boolean,
    val verifiedBootState: VerifiedBootState,
    /**
     * The `attestationChallenge` from the record. The caller MUST compare this
     * (constant-time, exact length) against the challenge it passed to
     * `setAttestationChallenge()`, to bind the record to its own key-generation
     * request and reject replayed/captured attestation chains.
     */
    val attestationChallenge: ByteArray,
) {
    // equals/hashCode are hand-written because a data class would compare the
    // ByteArray by reference identity, which is never what we want.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedAttestation) return false
        return securityLevel == other.securityLevel &&
            deviceLocked == other.deviceLocked &&
            verifiedBootState == other.verifiedBootState &&
            attestationChallenge.contentEquals(other.attestationChallenge)
    }

    override fun hashCode(): Int {
        var result = securityLevel.hashCode()
        result = 31 * result + deviceLocked.hashCode()
        result = 31 * result + verifiedBootState.hashCode()
        result = 31 * result + attestationChallenge.contentHashCode()
        return result
    }
}

/**
 * Outcome of the device boot-state attestation check.
 *
 * Path A (advisory): only [Advisory] surfaces a (dismissible) warning to the
 * user; [Trusted] and [Unavailable] are silent. The app is NEVER hard-blocked
 * by this result - hard blocking remains solely the trimmed heuristic
 * `RootDetector`'s job.
 */
sealed interface AttestationResult {
    /** Hardware-attested, bootloader locked, boot state Verified or SelfSigned. */
    data object Trusted : AttestationResult

    /** Hardware-attested but a weak boot state (unlocked / Unverified / Failed). */
    data class Advisory(val reason: String) : AttestationResult

    /**
     * Attestation could not be obtained or trusted (no hardware attestation,
     * software security level, keygen/parse/chain failure, unrecognised state).
     * Silent under Path A - we neither warn nor block on "cannot determine".
     */
    data class Unavailable(val reason: String) : AttestationResult
}
