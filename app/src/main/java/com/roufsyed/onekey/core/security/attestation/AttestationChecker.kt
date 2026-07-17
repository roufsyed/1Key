package com.roufsyed.onekey.core.security.attestation

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Advisory (Path A) device boot-state check via Android Key Attestation.
 *
 * Generates a throwaway hardware key with a fresh random attestation challenge,
 * verifies the returned certificate chain against a pinned Google root, binds it
 * to this request (leaf public key == generated key, challenge matches), parses
 * the boot state, and applies [AttestationPolicy]. The result NEVER hard-blocks
 * the app; it only drives a dismissible warning (see the UI wiring).
 *
 * Hardening (per the security roadmap's red-team review):
 *  - 32-byte SecureRandom challenge, freshly generated each call.
 *  - A RANDOM per-invocation key alias, so the separate-process AutofillService
 *    sharing AndroidKeyStore can never collide with a fixed alias, plus a
 *    single-flight [mutex] to serialise keystore work within this process.
 *  - StrongBox preferred, transparent fall back to the TEE.
 *  - The throwaway key is always deleted in a `finally`.
 *  - Constant-time, exact-length challenge comparison ([MessageDigest.isEqual]).
 *  - Total catch-all: any failure yields [AttestationResult.Unavailable]; this
 *    runs on the cold-start path and must never crash the app.
 */
@Singleton
class AttestationChecker @Inject constructor() {

    private val mutex = Mutex()
    private val secureRandom = SecureRandom()

    /**
     * Runs the full attestation check off the main thread. Returns
     * [AttestationResult.Unavailable] on any internal failure rather than
     * throwing; only coroutine cancellation propagates.
     */
    suspend fun check(): AttestationResult = withContext(Dispatchers.IO) {
        mutex.withLock { runAttestation() }
    }

    private fun runAttestation(): AttestationResult = runCatching {
        val challenge = ByteArray(CHALLENGE_SIZE_BYTES).also(secureRandom::nextBytes)
        val alias = newAlias()
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        try {
            val publicKey = generateAttestedKey(alias, challenge)
                ?: return@runCatching AttestationResult.Unavailable("hardware attestation unavailable")
            val chain = keyStore.getCertificateChain(alias)
                ?.filterIsInstance<X509Certificate>()
                .orEmpty()
            evaluate(chain, publicKey, challenge)
        } finally {
            // Best-effort cleanup; the key is single-use and must not linger.
            runCatching { keyStore.deleteEntry(alias) }
        }
    }.getOrElse {
        AttestationResult.Unavailable("attestation check failed: ${it.javaClass.simpleName}")
    }

    /**
     * Pure decision core (no Android keystore I/O), exposed for host-JVM tests.
     * Applies, in order: chain trust -> generated-key binding -> challenge
     * binding -> [AttestationPolicy]. Any failed gate yields
     * [AttestationResult.Unavailable] (silent under Path A); it never throws.
     */
    fun evaluate(
        chain: List<X509Certificate>,
        expectedPublicKey: PublicKey,
        expectedChallenge: ByteArray,
    ): AttestationResult {
        val leaf = AttestationChainVerifier.verifyToPinnedRoot(chain)
            ?: return AttestationResult.Unavailable("chain did not verify to a pinned Google root")

        if (!leaf.publicKey.encoded.contentEquals(expectedPublicKey.encoded)) {
            return AttestationResult.Unavailable("leaf public key does not match the generated key")
        }

        val parsed = AttestationExtensionParser.parse(leaf)
            ?: return AttestationResult.Unavailable("attestation extension missing or unparseable")

        if (!MessageDigest.isEqual(parsed.attestationChallenge, expectedChallenge)) {
            return AttestationResult.Unavailable("attestation challenge mismatch")
        }

        return AttestationPolicy.evaluate(parsed)
    }

    private fun generateAttestedKey(alias: String, challenge: ByteArray): PublicKey? =
        generateInternal(alias, challenge, strongBox = true)
            ?: generateInternal(alias, challenge, strongBox = false)

    private fun generateInternal(alias: String, challenge: ByteArray, strongBox: Boolean): PublicKey? =
        runCatching {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .apply {
                    if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setIsStrongBoxBacked(true)
                    }
                }
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
                .apply { initialize(spec) }
                .generateKeyPair()
                .public
        }.getOrNull()

    private fun newAlias(): String {
        val suffix = ByteArray(ALIAS_RANDOM_BYTES).also(secureRandom::nextBytes)
        return ALIAS_PREFIX + suffix.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val EC_CURVE = "secp256r1"
        const val CHALLENGE_SIZE_BYTES = 32
        const val ALIAS_RANDOM_BYTES = 16
        const val ALIAS_PREFIX = "onekey_attest_"
    }
}
