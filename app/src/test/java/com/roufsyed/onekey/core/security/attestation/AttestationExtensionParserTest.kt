package com.roufsyed.onekey.core.security.attestation

import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERTaggedObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves [AttestationExtensionParser] against spec-correct DER we build with
 * BouncyCastle (hermetic, no network, no device). Real captured chains are
 * exercised separately by the chain-verifier tests.
 */
class AttestationExtensionParserTest {

    // [704] EXPLICIT rootOfTrust, per the AOSP AuthorizationList schema.
    private val tagRootOfTrust = 704

    /** Build a `RootOfTrust` SEQUENCE. */
    private fun rootOfTrust(
        deviceLocked: Boolean,
        verifiedBootState: Int,
        includeVerifiedBootHash: Boolean = true,
        verifiedBootKey: ByteArray = ByteArray(32) { 0x11 },
    ): DERSequence {
        val v = ASN1EncodableVector()
        v.add(DEROctetString(verifiedBootKey))          // 0 verifiedBootKey
        v.add(ASN1Boolean.getInstance(deviceLocked))    // 1 deviceLocked
        v.add(ASN1Enumerated(verifiedBootState))        // 2 verifiedBootState
        if (includeVerifiedBootHash) {
            v.add(DEROctetString(ByteArray(32) { 0x22 })) // 3 verifiedBootHash (opt)
        }
        return DERSequence(v)
    }

    /**
     * Build a raw X.509 extnValue (OCTET STRING wrapping the KeyDescription).
     * A `null` [rootOfTrust] omits the `[704]` entry entirely.
     */
    private fun extnValue(
        securityLevel: Int = 1,                 // TrustedEnvironment
        challenge: ByteArray = byteArrayOf(1, 2, 3, 4),
        rootOfTrust: DERSequence? = rootOfTrust(deviceLocked = true, verifiedBootState = 0),
        keyDescriptionFieldCount: Int = 8,
        includeDecoyTeeFields: Boolean = true,
    ): ByteArray {
        val tee = ASN1EncodableVector()
        // A lower-numbered context tag before [704] that MUST be skipped.
        if (includeDecoyTeeFields) tee.add(DERTaggedObject(true, 400, ASN1Integer(140000L)))
        if (rootOfTrust != null) tee.add(DERTaggedObject(true, tagRootOfTrust, rootOfTrust))
        // A higher-numbered context tag after [704] that MUST also be skipped.
        if (includeDecoyTeeFields) tee.add(DERTaggedObject(true, 705, ASN1Integer(1L)))
        val hardwareEnforced = DERSequence(tee)

        val allFields: List<ASN1Encodable> = listOf(
            ASN1Integer(200L),              // 0 attestationVersion (KeyMint 2.0)
            ASN1Enumerated(securityLevel),  // 1 attestationSecurityLevel
            ASN1Integer(200L),              // 2 keymasterVersion
            ASN1Enumerated(1),              // 3 keymasterSecurityLevel
            DEROctetString(challenge),      // 4 attestationChallenge
            DEROctetString(ByteArray(0)),   // 5 uniqueId
            DERSequence(),                  // 6 softwareEnforced (empty)
            hardwareEnforced,               // 7 hardwareEnforced
        )
        val kd = ASN1EncodableVector()
        repeat(keyDescriptionFieldCount.coerceAtMost(allFields.size)) { kd.add(allFields[it]) }

        val keyDescription = DERSequence(kd).getEncoded(ASN1Encoding.DER)
        return DEROctetString(keyDescription).getEncoded(ASN1Encoding.DER)
    }

    // ---- happy path ----

    @Test
    fun `parses verified locked TEE record and round-trips the challenge`() {
        val challenge = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1)
        val result = AttestationExtensionParser.parseExtensionValue(
            extnValue(
                securityLevel = 1,
                challenge = challenge,
                rootOfTrust = rootOfTrust(deviceLocked = true, verifiedBootState = 0),
            )
        )!!
        assertEquals(AttestationSecurityLevel.TRUSTED_ENVIRONMENT, result.securityLevel)
        assertTrue(result.deviceLocked)
        assertEquals(VerifiedBootState.VERIFIED, result.verifiedBootState)
        assertArrayEquals(challenge, result.attestationChallenge)
    }

    @Test
    fun `parses unverified unlocked record`() {
        val result = AttestationExtensionParser.parseExtensionValue(
            extnValue(rootOfTrust = rootOfTrust(deviceLocked = false, verifiedBootState = 2))
        )!!
        assertFalse(result.deviceLocked)
        assertEquals(VerifiedBootState.UNVERIFIED, result.verifiedBootState)
    }

    @Test
    fun `parses selfsigned locked record (GrapheneOS-style)`() {
        val result = AttestationExtensionParser.parseExtensionValue(
            extnValue(rootOfTrust = rootOfTrust(deviceLocked = true, verifiedBootState = 1))
        )!!
        assertTrue(result.deviceLocked)
        assertEquals(VerifiedBootState.SELF_SIGNED, result.verifiedBootState)
    }

    @Test
    fun `parses failed boot state`() {
        val result = AttestationExtensionParser.parseExtensionValue(
            extnValue(rootOfTrust = rootOfTrust(deviceLocked = false, verifiedBootState = 3))
        )!!
        assertEquals(VerifiedBootState.FAILED, result.verifiedBootState)
    }

    @Test
    fun `maps strongbox and software security levels`() {
        assertEquals(
            AttestationSecurityLevel.STRONG_BOX,
            AttestationExtensionParser.parseExtensionValue(extnValue(securityLevel = 2))!!.securityLevel,
        )
        assertEquals(
            AttestationSecurityLevel.SOFTWARE,
            AttestationExtensionParser.parseExtensionValue(extnValue(securityLevel = 0))!!.securityLevel,
        )
    }

    @Test
    fun `parses v2-style RootOfTrust without verifiedBootHash`() {
        val result = AttestationExtensionParser.parseExtensionValue(
            extnValue(
                rootOfTrust = rootOfTrust(
                    deviceLocked = true,
                    verifiedBootState = 0,
                    includeVerifiedBootHash = false,
                )
            )
        )!!
        assertTrue(result.deviceLocked)
        assertEquals(VerifiedBootState.VERIFIED, result.verifiedBootState)
    }

    @Test
    fun `skips unknown tee fields on both sides of RootOfTrust`() {
        val result = AttestationExtensionParser.parseExtensionValue(
            extnValue(
                includeDecoyTeeFields = true,
                rootOfTrust = rootOfTrust(deviceLocked = true, verifiedBootState = 1),
            )
        )!!
        assertEquals(VerifiedBootState.SELF_SIGNED, result.verifiedBootState)
    }

    @Test
    fun `unrecognised verifiedBootState maps to UNKNOWN not a valid state`() {
        val result = AttestationExtensionParser.parseExtensionValue(
            extnValue(rootOfTrust = rootOfTrust(deviceLocked = true, verifiedBootState = 99))
        )!!
        assertEquals(VerifiedBootState.UNKNOWN, result.verifiedBootState)
    }

    // ---- fail-safe / malformed (must return null, never throw) ----

    @Test
    fun `returns null when extnValue is not an octet string`() {
        val bare = DERSequence(ASN1EncodableVector().apply { add(ASN1Integer(1L)) })
            .getEncoded(ASN1Encoding.DER)
        assertNull(AttestationExtensionParser.parseExtensionValue(bare))
    }

    @Test
    fun `returns null when KeyDescription has too few fields`() {
        assertNull(AttestationExtensionParser.parseExtensionValue(extnValue(keyDescriptionFieldCount = 6)))
    }

    @Test
    fun `returns null when RootOfTrust is absent`() {
        assertNull(AttestationExtensionParser.parseExtensionValue(extnValue(rootOfTrust = null)))
    }

    @Test
    fun `returns null when RootOfTrust has too few fields`() {
        val shortRot = DERSequence(ASN1EncodableVector().apply {
            add(DEROctetString(ByteArray(32)))
            add(ASN1Boolean.getInstance(true))
        })
        assertNull(AttestationExtensionParser.parseExtensionValue(extnValue(rootOfTrust = shortRot)))
    }

    @Test
    fun `returns null on garbage bytes without throwing`() {
        assertNull(AttestationExtensionParser.parseExtensionValue(ByteArray(0)))
        assertNull(AttestationExtensionParser.parseExtensionValue(byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x01)))
        assertNull(AttestationExtensionParser.parseExtensionValue(byteArrayOf(-1, -1, -1, -1)))
    }

    @Test
    fun `zero-length ENUMERATED verifiedBootState is rejected, not read as VERIFIED`() {
        // Hand-built extnValue whose verifiedBootState is a malformed zero-length
        // ENUMERATED (0A 00). A lenient parser could misread it as 0 = VERIFIED;
        // it MUST return null. Regression-locks BouncyCastle's length==0 rejection
        // against a future dependency change (flagged by the parser review).
        // Layout: OCTET STRING { KeyDescription SEQ { ver, secLvl=TEE, kmVer,
        // kmSecLvl, challenge, uniqueId, swEnforced{}, hwEnforced{ [704]{ RoT{
        // vbKey, deviceLocked=TRUE, verifiedBootState=<0A 00> } } } } }.
        val extn = hex(
            "04 27 30 25 02 01 C8 0A 01 01 02 01 C8 0A 01 01 " +
                "04 04 01 02 03 04 04 00 30 00 " +
                "30 0D BF 85 40 09 30 07 04 00 01 01 FF 0A 00"
        )
        assertNull(AttestationExtensionParser.parseExtensionValue(extn))
    }

    private fun hex(s: String): ByteArray =
        s.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
