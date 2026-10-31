package com.roufsyed.onekey.core.domain.webauthn

import java.math.BigInteger
import java.security.interfaces.ECPublicKey

/**
 * COSE_Key encoding (RFC 9052 section 7, RFC 9053 section 7.1.1) of a P-256
 * public key for the ES256 algorithm, as embedded in attested credential data.
 *
 * Only ES256 on P-256 is produced: it is the algorithm every relying party
 * supports because the CTAP1 interoperability rules mandate it. Labels:
 * `kty` (1) = EC2 (2), `alg` (3) = ES256 (-7), `crv` (-1) = P-256 (1),
 * `x` (-2) and `y` (-3) = 32-byte big-endian coordinates.
 */
object CoseKey {
    const val KTY_EC2 = 2
    const val ALG_ES256 = -7
    const val CRV_P256 = 1
    private const val COORDINATE_BYTES = 32

    fun es256(publicKey: ECPublicKey): ByteArray =
        es256(x = toFixedLength(publicKey.w.affineX), y = toFixedLength(publicKey.w.affineY))

    fun es256(x: ByteArray, y: ByteArray): ByteArray {
        require(x.size == COORDINATE_BYTES && y.size == COORDINATE_BYTES) { "P-256 coordinates must be 32 bytes" }
        return Cbor.encode(
            mapOf(
                1 to KTY_EC2,
                3 to ALG_ES256,
                -1 to CRV_P256,
                -2 to x,
                -3 to y,
            ),
        )
    }

    /** Left-pads (or strips a sign byte from) a BigInteger coordinate to exactly 32 bytes. */
    internal fun toFixedLength(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == COORDINATE_BYTES -> raw
            raw.size == COORDINATE_BYTES + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
            raw.size < COORDINATE_BYTES -> ByteArray(COORDINATE_BYTES - raw.size) + raw
            else -> throw IllegalArgumentException("Coordinate does not fit in 32 bytes")
        }
    }
}
