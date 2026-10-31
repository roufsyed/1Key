package com.roufsyed.onekey.core.domain.webauthn

import java.nio.ByteBuffer
import java.util.UUID

/**
 * The Authenticator Attestation GUID that identifies 1Key as a passkey
 * provider inside attested credential data. One constant for both flavours,
 * generated once (2026-10-31) and never changed: relying parties and
 * passkey directories key provider names and icons off this value.
 */
object OneKeyAaguid {
    val UUID_VALUE: UUID = UUID.fromString("34b612ab-ee35-4a53-b81c-594986fdfab3")

    /** 16 big-endian bytes, the form authenticator data carries. */
    val BYTES: ByteArray = ByteBuffer.allocate(16)
        .putLong(UUID_VALUE.mostSignificantBits)
        .putLong(UUID_VALUE.leastSignificantBits)
        .array()
}
