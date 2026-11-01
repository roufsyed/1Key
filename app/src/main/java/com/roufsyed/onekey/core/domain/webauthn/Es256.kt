package com.roufsyed.onekey.core.domain.webauthn

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * ES256 (ECDSA over P-256 with SHA-256) primitives for passkeys, on the
 * platform JCA provider. Keys are plain software keys: they must round-trip
 * through the encrypted vault, backups and file-based sync, which Android
 * Keystore keys cannot do. The vault key, not the passkey key, is what the
 * Keystore protects.
 *
 * Private keys are handled as PKCS#8 DER so the same bytes serve the Room
 * row, the encrypted backup, and the KeePassXC interchange format (which
 * carries the PEM form of exactly these bytes). The other algorithms live in
 * [PasskeyKeys]; this object stays ES256-specific.
 */
object Es256 {
    private const val CURVE = "secp256r1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private val secureRandom = SecureRandom()

    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE), secureRandom)
        return generator.generateKeyPair()
    }

    /** 32 random bytes; the WebAuthn credential ID (spec allows 16 to 1023). */
    fun newCredentialId(): ByteArray = ByteArray(32).also(secureRandom::nextBytes)

    fun publicKeyOf(keyPair: KeyPair): ECPublicKey = keyPair.public as ECPublicKey

    fun privateKeyPkcs8(keyPair: KeyPair): ByteArray = keyPair.private.encoded

    fun decodePrivateKey(pkcs8: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    /** DER-encoded ECDSA signature over [data], the form WebAuthn expects for ES256. */
    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey, secureRandom)
        signature.update(data)
        return signature.sign()
    }

    /** Assertion signature: ECDSA over `authenticatorData || clientDataHash`. */
    fun signAssertion(privateKeyPkcs8: ByteArray, authenticatorData: ByteArray, clientDataHash: ByteArray): ByteArray =
        sign(decodePrivateKey(privateKeyPkcs8), authenticatorData + clientDataHash)

    fun verify(publicKey: ECPublicKey, data: ByteArray, derSignature: ByteArray): Boolean {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initVerify(publicKey)
        signature.update(data)
        return signature.verify(derSignature)
    }

    /** Kept for callers that predate [Pkcs8Pem]; same bytes for every algorithm. */
    fun toPem(pkcs8: ByteArray): String = Pkcs8Pem.encode(pkcs8)

    fun fromPem(pem: String): ByteArray = Pkcs8Pem.decode(pem)
}
