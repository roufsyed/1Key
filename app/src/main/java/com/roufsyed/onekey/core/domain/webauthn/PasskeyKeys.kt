package com.roufsyed.onekey.core.domain.webauthn

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec

/**
 * Key generation and signing for every [PasskeyAlgorithm], behind one API so
 * the authenticator core never branches on key type itself.
 *
 * ES256 and RS256 use the platform JCA provider. Ed25519 uses BouncyCastle's
 * lightweight classes directly, because Android's own provider does not offer
 * Ed25519 (its `OpenSSLProvider` registers no such algorithm as of Android 14)
 * and the app already bundles BouncyCastle for ASN.1 parsing. No JCE provider
 * is ever registered.
 *
 * Every private key is handled as PKCS#8 DER, which names its own algorithm,
 * so the Room row and the backup format carry no separate type field and
 * [sign] can dispatch on the key alone.
 */
object PasskeyKeys {
    private const val RSA_BITS = 2048
    private val secureRandom = SecureRandom()

    /** A freshly generated key pair in the three encodings a registration needs. [privateKeyPkcs8] is the only secret. */
    class Generated(
        val algorithm: PasskeyAlgorithm,
        val privateKeyPkcs8: ByteArray,
        /** X.509 SubjectPublicKeyInfo DER, the `response.publicKey` field of RegistrationResponseJSON. */
        val publicKeySpki: ByteArray,
        val cosePublicKey: ByteArray,
    )

    fun generate(algorithm: PasskeyAlgorithm): Generated = when (algorithm) {
        PasskeyAlgorithm.ES256 -> {
            val keyPair = Es256.generateKeyPair()
            Generated(algorithm, keyPair.private.encoded, keyPair.public.encoded, CoseKey.es256(keyPair.public as ECPublicKey))
        }
        PasskeyAlgorithm.RS256 -> {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(RSAKeyGenParameterSpec(RSA_BITS, RSAKeyGenParameterSpec.F4), secureRandom)
            val keyPair = generator.generateKeyPair()
            Generated(algorithm, keyPair.private.encoded, keyPair.public.encoded, CoseKey.rs256(keyPair.public as RSAPublicKey))
        }
        PasskeyAlgorithm.EDDSA -> {
            val generator = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(secureRandom)) }
            val pair = generator.generateKeyPair()
            val privateKey = pair.private as Ed25519PrivateKeyParameters
            val publicKey = pair.public as Ed25519PublicKeyParameters
            Generated(
                algorithm,
                PrivateKeyInfoFactory.createPrivateKeyInfo(privateKey).encoded,
                SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(publicKey).encoded,
                CoseKey.ed25519(publicKey.encoded),
            )
        }
    }

    /**
     * Signs [data] with a stored key, in the form WebAuthn expects for its
     * algorithm: ASN.1 DER for ECDSA, PKCS#1 v1.5 for RSA, 64 raw bytes for
     * Ed25519. Throws for a key 1Key did not make.
     */
    fun sign(privateKeyPkcs8: ByteArray, data: ByteArray): ByteArray {
        val algorithm = PasskeyAlgorithm.ofPkcs8(privateKeyPkcs8)
            ?: throw IllegalArgumentException("Private key is not one of the supported passkey algorithms")
        return when (algorithm) {
            PasskeyAlgorithm.ES256 -> Es256.sign(Es256.decodePrivateKey(privateKeyPkcs8), data)
            PasskeyAlgorithm.RS256 -> {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8))
                Signature.getInstance("SHA256withRSA").run {
                    initSign(key, secureRandom)
                    update(data)
                    sign()
                }
            }
            PasskeyAlgorithm.EDDSA -> {
                val key = PrivateKeyFactory.createKey(privateKeyPkcs8) as Ed25519PrivateKeyParameters
                Ed25519Signer().run {
                    init(true, key)
                    update(data, 0, data.size)
                    generateSignature()
                }
            }
        }
    }

    /** Assertion signature over `authenticatorData || clientDataHash`. */
    fun signAssertion(privateKeyPkcs8: ByteArray, authenticatorData: ByteArray, clientDataHash: ByteArray): ByteArray =
        sign(privateKeyPkcs8, authenticatorData + clientDataHash)
}
