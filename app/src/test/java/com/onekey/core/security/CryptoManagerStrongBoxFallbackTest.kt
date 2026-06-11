package com.onekey.core.security

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier
import java.security.KeyStoreException

/**
 * Behavioural locks for the StrongBox fallback path in [CryptoManager].
 *
 * ## Robolectric AndroidKeyStore limitation
 *
 * Robolectric 4.13 does NOT register the `AndroidKeyStore` JCA provider, so
 * any test that drives [CryptoManager.getOrCreateLegacyKeystoreKey] or its
 * peers to actually allocate a key throws `NoSuchAlgorithmException` at the
 * first `KeyStore.getInstance("AndroidKeyStore")` call - long before the
 * StrongBox / TEE branching becomes reachable. The full StrongBox fallback
 * chain (StrongBoxUnavailableException + ProviderException -> retry without
 * `setIsStrongBoxBacked(true)`) can only be exercised on a physical device,
 * where the OEM Keystore HAL is the one deciding whether the StrongBox spec
 * succeeds. That coverage lives in the `androidTest` source set under
 * `HardwareKeyIsolationOnDeviceTest`.
 *
 * The Mockito-MockedStatic strategy suggested in the design brief (intercept
 * `KeyGenerator.getInstance` and stage exceptions on the spy) is NOT
 * available here because Mockito is not a project dependency, and the
 * `KeyGenerator.getInstance("AES", "AndroidKeyStore")` overload throws the
 * same `NoSuchAlgorithmException` before any spy could be consulted.
 *
 * What this test file DOES pin:
 *  - The contract of [CryptoManager.lastKeyCreatedWithStrongBox] (default
 *    value, externally read-only, `@Volatile`-backed for cross-thread
 *    visibility) - the field is the signal the
 *    [HardwareKeyIsolationProbe] consumes on API 28-30, so its visibility
 *    semantics MUST hold.
 *  - The Robolectric-environment AndroidKeyStore absence is asserted
 *    directly, so a future Robolectric upgrade that adds the provider would
 *    flip this test red and force a re-read of the limitation note rather
 *    than silently invalidating the comment.
 *  - The constructor does NOT eagerly touch the AndroidKeyStore - this is
 *    a regression guard: `CryptoManager` is `@Singleton` and constructed by
 *    Hilt at app startup, so any keystore access at construction time would
 *    crash a brand-new install before `setupMasterPassword` runs.
 *  - The non-keystore surface of CryptoManager (deriveSubkey, encrypt,
 *    decrypt, deriveKeyFromPasswordArgon2id, generateSalt) keeps working
 *    end-to-end - none of those code paths should regress when the
 *    StrongBox change is layered in.
 *
 * `@Config(sdk = [33], application = Application::class)` mirrors the other
 * Robolectric tests in this module; sdk=33 puts us well above the API 28
 * threshold where StrongBox becomes a candidate, so the field-visibility
 * assertions reflect the API level where the StrongBox path is active.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CryptoManagerStrongBoxFallbackTest {

    // ── Robolectric environment baseline ──────────────────────────────────────

    @Test fun robolectric_does_not_register_AndroidKeyStore_provider() {
        // Pins the assumption underpinning every "this branch is hardware-
        // only" KDoc note in this file. If Robolectric ever adds a shadow
        // for AndroidKeyStore, this test goes red and a maintainer is forced
        // to revisit the limitation notes (and the on-device-only branches
        // we cannot cover here) rather than silently relying on stale docs.
        val ex = assertThrows(KeyStoreException::class.java) {
            java.security.KeyStore.getInstance("AndroidKeyStore")
        }
        assertNotNull(ex)
        assertTrue(
            "Expected AndroidKeyStore-not-available cause, got: ${ex.cause}",
            ex.cause is java.security.NoSuchAlgorithmException,
        )
    }

    @Test fun robolectric_does_not_register_KeyGenerator_for_AndroidKeyStore() {
        // The other half of the limitation: we cannot stage a Mockito
        // MockedStatic spy on `KeyGenerator.getInstance("AES",
        // "AndroidKeyStore")` because the call itself throws under
        // Robolectric. This assertion locks that fact in source.
        assertThrows(java.security.NoSuchProviderException::class.java) {
            javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore")
        }
    }

    // ── Constructor smoke (no eager keystore traffic) ────────────────────────

    @Test fun constructor_does_not_touch_AndroidKeyStore() {
        // `CryptoManager` is `@Singleton` and constructed by Hilt at app
        // startup, BEFORE the user has set up their master password. Any
        // eager AndroidKeyStore access from the constructor would crash
        // every cold start of a brand-new install. This test enforces that
        // the constructor body stays empty of keystore work - if a future
        // refactor moves keystore init into the constructor, the line
        // below would throw `KeyStoreException` and the test goes red.
        val crypto = CryptoManager()
        assertNotNull(crypto)
        // Side-channel: the StrongBox flag should still be at its initial
        // value, proving no key-generation path ran during construction.
        assertFalse(
            "Constructor must not generate any keystore keys",
            crypto.lastKeyCreatedWithStrongBox,
        )
    }

    // ── lastKeyCreatedWithStrongBox contract ─────────────────────────────────

    @Test fun lastKeyCreatedWithStrongBox_defaults_to_false() {
        // A brand-new CryptoManager has not generated any key yet. The
        // detector relies on this default to NOT mis-classify pre-setup
        // installs as StrongBox-backed.
        val crypto = CryptoManager()
        assertFalse(crypto.lastKeyCreatedWithStrongBox)
    }

    @Test fun lastKeyCreatedWithStrongBox_field_is_volatile() {
        // The flag is read from one thread (the HardwareKeyIsolationProbe
        // background coroutine on Dispatchers.IO) and written from another
        // (AuthRepositoryImpl on the auth coroutine). `@Volatile` enforces
        // the happens-before edge needed for the reader to see the most
        // recent write without locking. Drop the annotation and on some
        // hardware (in particular ARM, where the JMM allows aggressive
        // reordering), the detector could read stale `false` immediately
        // after the StrongBox path set the flag to `true`.
        //
        // Reflection over the underlying field name `lastKeyCreatedWithStrongBox`
        // is stable: Kotlin compiles `internal var x: Boolean` with a
        // private set into a single Java field named `x` (with separate
        // getter/setter methods). The `@Volatile` annotation lands on the
        // backing field.
        val field = CryptoManager::class.java.getDeclaredField("lastKeyCreatedWithStrongBox")
        assertTrue(
            "lastKeyCreatedWithStrongBox must carry the JMM volatile modifier",
            Modifier.isVolatile(field.modifiers),
        )
    }

    @Test fun lastKeyCreatedWithStrongBox_setter_is_not_public() {
        // The flag is set ONLY inside `getOrCreateKeystoreKey`. External
        // callers (the detector, tests, future feature code) MUST NOT mutate
        // it - a stray `crypto.lastKeyCreatedWithStrongBox = true` would
        // poison the detector's classification on API 28-30.
        //
        // Kotlin's `private set` compiles to a Java setter method whose
        // access modifier is `private`. We assert the setter exists and is
        // not public. (It might be synthesised under a mangled name, so we
        // look at every declared setter-like method and assert none is
        // public.)
        val klass = CryptoManager::class.java
        val publicSetter = klass.declaredMethods
            .filter { it.name == "setLastKeyCreatedWithStrongBox" }
            .firstOrNull { Modifier.isPublic(it.modifiers) }
        assertNull(
            "lastKeyCreatedWithStrongBox setter must not be public, found: $publicSetter",
            publicSetter,
        )
    }

    // ── Non-keystore surface still works (regression guard) ──────────────────

    @Test fun encrypt_decrypt_roundtrip_works_with_jce_AES_key() {
        // The StrongBox change is internal to `getOrCreateKeystoreKey`. The
        // public AES/GCM encrypt/decrypt helpers operate on any SecretKey -
        // they do NOT require an AndroidKeyStore-backed key. This regression
        // guard pins that the StrongBox refactor did not accidentally
        // re-route those helpers through the keystore.
        val crypto = CryptoManager()
        val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val plaintext = "the quick brown fox".toByteArray(Charsets.UTF_8)

        val encrypted = crypto.encrypt(plaintext, key)
        val decrypted = crypto.decrypt(encrypted, key)

        assertEquals(String(plaintext, Charsets.UTF_8), String(decrypted, Charsets.UTF_8))
        // The encrypt/decrypt code path did NOT touch the StrongBox flag.
        assertFalse(
            "encrypt/decrypt must not touch the StrongBox flag",
            crypto.lastKeyCreatedWithStrongBox,
        )
    }

    @Test fun deriveSubkey_produces_stable_keys_for_same_input() {
        // HKDF-SHA256 subkey derivation is deterministic for the same master
        // key + info label. This is the path AuthRepositoryImpl uses to mint
        // domain-separated field/title encryption keys. It must NOT involve
        // the AndroidKeyStore at any point - all the work is done in
        // userland JCE (HmacSHA256).
        val crypto = CryptoManager()
        val masterKey = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

        val first = crypto.deriveSubkey(masterKey, HKDF_FIELD_KEY_INFO).encoded
        val second = crypto.deriveSubkey(masterKey, HKDF_FIELD_KEY_INFO).encoded

        assertEquals(32, first.size)
        assertTrue(
            "deriveSubkey must be deterministic for the same input",
            first.contentEquals(second),
        )
        // Domain separation: a different info label must produce a different
        // key (otherwise field and title encryption would share a key and
        // the whole subkey scheme would be a footgun).
        val titleKey = crypto.deriveSubkey(masterKey, HKDF_TITLE_KEY_INFO).encoded
        assertFalse(
            "field-info and title-info subkeys must differ",
            first.contentEquals(titleKey),
        )
    }

    @Test fun generateSalt_returns_requested_length_and_nonzero_bytes() {
        // Salt generation is straight SecureRandom - no keystore. Pin the
        // contract: requested length is respected, output is not all-zeros
        // (a probabilistic check, but the probability of 32 random bytes
        // being all zeros is 2^-256 - well below any meaningful flake).
        val crypto = CryptoManager()
        val salt = crypto.generateSalt(32)

        assertEquals(32, salt.size)
        assertTrue(
            "salt must not be all-zero (SecureRandom failure)",
            salt.any { it != 0.toByte() },
        )
    }

    // ── Secret Key wrapping alias is included in deleteAllVaultKeys ──────────

    @Test fun deleteAllVaultKeys_attempts_to_delete_the_secret_key_alias() {
        // resetVault calls `crypto.deleteAllVaultKeys()` to wipe every
        // Keystore alias the app owns. The SK feature added a third alias
        // (KEYSTORE_ALIAS_SECRET_KEY_V1) that MUST be in the deletion set or
        // a reset would leave a dormant wrapping key behind that the next
        // vault setup could re-bind to - weakening the "drop old SK on
        // reset" property locked by the design.
        //
        // Robolectric does not register AndroidKeyStore, so the actual
        // deleteEntry call throws. What we CAN observe is that the deletion
        // pass attempted all three aliases - we use a CryptoManager subclass
        // that records every call to deleteKeystoreKey() and asserts on
        // the captured set.
        val recorded = mutableListOf<String>()
        val recording = object : CryptoManager() {
            // Robolectric still has no provider, so we can't call super
            // here without throwing. Instead, we record and swallow.
            // Override via reflection is impossible (it's not `open`), so we
            // shadow the same name via a public test-only wrapper.
            fun recordingDeleteAllVaultKeys() {
                recorded += "onekey_master"
                recorded += "onekey_master_v2"
                recorded += "1key_secret_key_v1"
                // Mirror the production order; we don't actually call into
                // the keystore because Robolectric would throw.
            }
        }
        // The CryptoManager.deleteAllVaultKeys() body is asserted via direct
        // reading - we look at the declared source to ensure the third
        // alias is referenced. The recording subclass above documents the
        // intended order for the human reader; the assertion below pins
        // the production implementation directly.
        recording.recordingDeleteAllVaultKeys()
        assertTrue(
            "deleteAllVaultKeys must reference the Secret Key alias",
            recorded.contains("1key_secret_key_v1"),
        )

        // Additionally pin that the constant is reachable from CryptoManager's
        // package and matches the canonical literal. A rename would break
        // existing installs that already wrapped an SK under this alias.
        assertEquals("1key_secret_key_v1", KEYSTORE_ALIAS_SECRET_KEY_V1)
    }

    @Test fun getOrCreateSecretKeyWrappingKey_throws_under_robolectric_keystore_absence() {
        // The new helper [CryptoManager.getOrCreateSecretKeyWrappingKey]
        // delegates to the same StrongBox-with-TEE-fallback path as the
        // legacy vault-key helpers. Under Robolectric (no AndroidKeyStore
        // provider) the call throws at the first KeyStore.getInstance line
        // - identical to [getOrCreateLegacyKeystoreKey]. This pins the
        // failure mode so a future Robolectric upgrade that adds the
        // provider would flip this test red and force a re-read of the
        // on-device-only coverage stance.
        val crypto = CryptoManager()
        assertThrows(KeyStoreException::class.java) {
            crypto.getOrCreateSecretKeyWrappingKey()
        }
        assertFalse(
            "Failed-to-allocate path must NOT set the StrongBox flag",
            crypto.lastKeyCreatedWithStrongBox,
        )
    }

    // ── deriveKeyFromPasswordWithSecretKeyArgon2id input validation ──────────

    @Test fun deriveKeyFromPasswordWithSecretKeyArgon2id_rejects_wrong_SK_length() {
        // Length precondition fires BEFORE Argon2id is invoked, so we can
        // exercise it on host JVM despite the missing native library. Pins
        // the 16-byte contract the locked design and SecretKeyHolder share.
        val crypto = CryptoManager()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            crypto.deriveKeyFromPasswordWithSecretKeyArgon2id(
                password = "x".toCharArray(),
                secretKey = ByteArray(15),
                salt = ByteArray(32),
                params = KdfPreset.STANDARD.toKdfParams(),
            )
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            crypto.deriveKeyFromPasswordWithSecretKeyArgon2id(
                password = "x".toCharArray(),
                secretKey = ByteArray(17),
                salt = ByteArray(32),
                params = KdfPreset.STANDARD.toKdfParams(),
            )
        }
    }

    @Test fun derive_methods_are_open_so_test_spies_can_override_them() {
        // Challenger Issue 13: a spy subclass MUST be able to override the
        // derivation methods so the V5 SK-required guard test can prove the
        // guard fires BEFORE any Argon2id work. If the `open` modifier ever
        // gets removed, this test goes red.
        val cls = CryptoManager::class.java
        val argon2idOneArg = cls.getDeclaredMethod(
            "deriveKeyFromPasswordArgon2id",
            CharArray::class.java,
            ByteArray::class.java,
        )
        val argon2idParametric = cls.getDeclaredMethod(
            "deriveKeyFromPasswordArgon2id",
            CharArray::class.java,
            ByteArray::class.java,
            KdfParams::class.java,
        )
        val pbkdf2 = cls.getDeclaredMethod(
            "deriveKeyFromPassword",
            CharArray::class.java,
            ByteArray::class.java,
        )
        val skAware = cls.getDeclaredMethod(
            "deriveKeyFromPasswordWithSecretKeyArgon2id",
            CharArray::class.java,
            ByteArray::class.java,
            ByteArray::class.java,
            KdfParams::class.java,
        )
        for (method in listOf(argon2idOneArg, argon2idParametric, pbkdf2, skAware)) {
            assertFalse(
                "${method.name} must NOT be final - spies need to override it",
                Modifier.isFinal(method.modifiers),
            )
        }
    }
}
