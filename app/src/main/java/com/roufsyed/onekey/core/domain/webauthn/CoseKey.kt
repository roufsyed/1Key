package com.roufsyed.onekey.core.domain.webauthn

import java.math.BigInteger
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey

/**
 * COSE_Key encodings (RFC 9052 section 7, RFC 9053 section 7.1.1, RFC 8230
 * for RSA) of the public keys 1Key embeds in attested credential data, one
 * per [PasskeyAlgorithm]:
 *
 *  - ES256: `kty` (1) = EC2 (2), `alg` (3) = -7, `crv` (-1) = P-256 (1),
 *    `x` (-2) and `y` (-3) = 32-byte big-endian coordinates;
 *  - EdDSA: `kty` = OKP (1), `alg` = -8, `crv` = Ed25519 (6), `x` = 32 bytes;
 *  - RS256: `kty` = RSA (3), `alg` = -257, `n` (-1) and `e` (-2) as unsigned
 *    big-endian integers without a leading zero.
 */
object CoseKey {
    const val KTY_OKP = 1
    const val KTY_EC2 = 2
    const val KTY_RSA = 3
    const val ALG_ES256 = -7
    const val ALG_EDDSA = -8
    const val ALG_RS256 = -257
    const val CRV_P256 = 1
    const val CRV_ED25519 = 6
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

    fun ed25519(x: ByteArray): ByteArray {
        require(x.size == COORDINATE_BYTES) { "Ed25519 public key must be 32 bytes" }
        return Cbor.encode(
            mapOf(
                1 to KTY_OKP,
                3 to ALG_EDDSA,
                -1 to CRV_ED25519,
                -2 to x,
            ),
        )
    }

    fun rs256(publicKey: RSAPublicKey): ByteArray = rs256(n = unsigned(publicKey.modulus), e = unsigned(publicKey.publicExponent))

    fun rs256(n: ByteArray, e: ByteArray): ByteArray {
        require(n.isNotEmpty() && e.isNotEmpty()) { "RSA modulus and exponent must not be empty" }
        return Cbor.encode(
            mapOf(
                1 to KTY_RSA,
                3 to ALG_RS256,
                -1 to n,
                -2 to e,
            ),
        )
    }

    /** Unsigned big-endian bytes of a positive integer, with the two's-complement sign byte removed. */
    internal fun unsigned(value: BigInteger): ByteArray {
        require(value.signum() > 0) { "RSA parameters must be positive" }
        val raw = value.toByteArray()
        return if (raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
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
