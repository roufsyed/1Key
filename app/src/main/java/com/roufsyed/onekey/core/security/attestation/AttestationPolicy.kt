package com.roufsyed.onekey.core.security.attestation

/**
 * Path A (advisory) boot-state policy: maps a *trusted* [ParsedAttestation] to an
 * [AttestationResult]. Pure and host-testable.
 *
 * The caller ([AttestationChecker]) MUST have already established trust - chain
 * verified to a pinned root, leaf public key equal to the generated key, and the
 * attestation challenge matched - before calling this. This function only decides
 * warn-vs-silent from the boot state; it makes no trust decisions itself.
 *
 * Rules (in order):
 *  1. Software-level attestation is not hardware-rooted, so its boot state cannot
 *     be trusted -> [AttestationResult.Unavailable] (silent).
 *  2. An unlocked bootloader (`deviceLocked == false`) is the clearest weak
 *     signal -> [AttestationResult.Advisory], regardless of verified-boot state.
 *  3. `Unverified` / `Failed` verified boot -> [AttestationResult.Advisory].
 *  4. `Verified`, or `SelfSigned` (e.g. a re-locked GrapheneOS / CalyxOS device),
 *     with the bootloader locked -> [AttestationResult.Trusted] (silent).
 *  5. Anything unrecognised -> [AttestationResult.Unavailable]; under Path A we
 *     neither warn nor block on "cannot determine".
 */
object AttestationPolicy {

    fun evaluate(parsed: ParsedAttestation): AttestationResult = when {
        parsed.securityLevel == AttestationSecurityLevel.SOFTWARE ->
            AttestationResult.Unavailable("attestation is software-level, not hardware-backed")

        !parsed.deviceLocked ->
            AttestationResult.Advisory("the bootloader is unlocked")

        parsed.verifiedBootState == VerifiedBootState.UNVERIFIED ->
            AttestationResult.Advisory("verified boot is not being enforced")

        parsed.verifiedBootState == VerifiedBootState.FAILED ->
            AttestationResult.Advisory("verified boot has failed")

        parsed.verifiedBootState == VerifiedBootState.VERIFIED ||
            parsed.verifiedBootState == VerifiedBootState.SELF_SIGNED ->
            AttestationResult.Trusted

        else ->
            AttestationResult.Unavailable("unrecognized verified-boot state")
    }
}
