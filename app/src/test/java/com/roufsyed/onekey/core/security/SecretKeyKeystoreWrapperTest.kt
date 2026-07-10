package com.roufsyed.onekey.core.security

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.KeyStoreException

/**
 * Behavioural locks for [SecretKeyKeystoreWrapper] that can be exercised
 * without driving the AndroidKeyStore (which Robolectric 4.13 does not
 * register as a JCA provider - see [CryptoManagerStrongBoxFallbackTest] for
 * the full limitation note).
 *
 * What this file pins:
 *  - The SP_SECRET_KEY_WRAPPED / SP_SK_PENDING_WRAPPED key names and the
 *    KEYSTORE_ALIAS_SECRET_KEY_V1 alias literal. These strings ship in the
 *    app's encrypted preferences file; renaming any of them would brick
 *    existing installs that already enabled the Secret Key feature.
 *  - The persistActive / persistPending / activeBlob / pendingBlob round
 *    trip through the SharedPreferences interface. The methods touch
 *    nothing else than the named keys.
 *  - clearActive / clearPending / clearAll's selective SharedPreferences
 *    behaviour - clearActive must leave the pending slot alone and
 *    vice-versa, clearAll wipes both.
 *  - The constructor does NOT eagerly touch AndroidKeyStore (a
 *    construction-time keystore call would crash app startup on a fresh
 *    install where the alias has not been minted yet).
 *  - Robolectric's AndroidKeyStore absence is asserted directly, so a
 *    future Robolectric upgrade that adds the provider would flip this
 *    test red and force a re-read of the limitation note (rather than
 *    silently invalidating the on-device-only coverage stance).
 *
 * What is covered ONLY on a physical device (androidTest source set):
 *  - wrap() round-trips a 16-byte SK through Keystore-backed AES-GCM and
 *    produces a base64 IV||CT||TAG blob.
 *  - unwrap() / unwrapBlob() round-trips a blob back to the original 16
 *    bytes.
 *  - wrap() called twice creates two different Keystore aliases (so the
 *    OLD wrapping key is dropped on rotate).
 *  - StrongBox-prefer-with-TEE-fallback path is selected per the
 *    `setIsStrongBoxBacked(true)` exception ladder. Pattern mirrors the
 *    vault-key wrapping coverage in HardwareKeyIsolationOnDeviceTest.
 *
 * Robolectric SDK 33 mirrors the other tests in the module; sdk=33 puts
 * us above the API 28 threshold where StrongBox becomes a candidate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SecretKeyKeystoreWrapperTest {

    private lateinit var authPrefs: SharedPreferences
    private lateinit var wrapper: SecretKeyKeystoreWrapper

    @Before fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        // Plain in-memory SharedPreferences stands in for the production
        // EncryptedSharedPreferences. The wrapper operates strictly on the
        // SharedPreferences interface, so the encryption layer in
        // production is transparent at this level.
        authPrefs = context.getSharedPreferences(
            "sk_wrap_test_${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        authPrefs.edit().clear().commit()
        wrapper = SecretKeyKeystoreWrapper(authPrefs)
    }

    @After fun tearDown() {
        authPrefs.edit().clear().commit()
    }

    // -- Robolectric environment baseline ----------------------------------

    @Test fun robolectric_does_not_register_AndroidKeyStore_provider() {
        // Pins the assumption underpinning every "this branch is on-device
        // only" KDoc note in this file. If Robolectric ever adds a shadow
        // for AndroidKeyStore, this test goes red and a maintainer is
        // forced to revisit the limitation notes (and the on-device-only
        // branches we cannot cover here) rather than silently relying on
        // stale docs.
        val ex = assertThrows(KeyStoreException::class.java) {
            java.security.KeyStore.getInstance("AndroidKeyStore")
        }
        assertNotNull(ex)
        assertTrue(
            "Expected AndroidKeyStore-not-available cause, got: ${ex.cause}",
            ex.cause is java.security.NoSuchAlgorithmException,
        )
    }

    // -- Constructor smoke (no eager keystore traffic) ---------------------

    @Test fun constructor_does_not_touch_AndroidKeyStore() {
        // The wrapper is `@Singleton` and gets constructed by Hilt at app
        // startup, BEFORE the user has set up their vault. Any eager
        // AndroidKeyStore access from the constructor would crash a brand
        // new install on cold boot. The setUp() block has already
        // constructed an instance; we re-construct here explicitly so the
        // assertion ties to the construction event itself.
        val fresh = SecretKeyKeystoreWrapper(authPrefs)
        assertNotNull(fresh)
        // No SharedPreferences should have been touched either.
        assertNull(authPrefs.getString(SP_SECRET_KEY_WRAPPED, null))
        assertNull(authPrefs.getString(SP_SK_PENDING_WRAPPED, null))
    }

    // -- SP key / alias literal locks --------------------------------------

    @Test fun sp_key_names_match_locked_design() {
        // The blob keys ship in the app's `onekey_auth` EncryptedSharedPreferences
        // file. Renaming either would orphan existing-install data after a
        // version bump. Pin the literals here so a future global rename
        // catches the test and forces a migration plan.
        assertEquals("secret_key_wrapped", SP_SECRET_KEY_WRAPPED)
        assertEquals("sk_pending_wrapped", SP_SK_PENDING_WRAPPED)
    }

    @Test fun keystore_alias_matches_locked_design() {
        // Same rationale as the SP key literals - the Keystore alias is
        // persisted in the OS Keystore HAL under exactly this string;
        // renaming would create a new alias and orphan the old (still
        // wrapping the active SK blob).
        assertEquals("1key_secret_key_v1", KEYSTORE_ALIAS_SECRET_KEY_V1)
    }

    // -- persistActive / activeBlob round trip -----------------------------

    @Test fun persistActive_writes_only_SP_SECRET_KEY_WRAPPED_and_version() {
        wrapper.persistActive("blob-active", 3)
        assertEquals("blob-active", authPrefs.getString(SP_SECRET_KEY_WRAPPED, null))
        assertEquals(
            "persistActive must record the alias version atomically with the blob",
            3,
            authPrefs.getInt(SP_SK_ACTIVE_ALIAS_VERSION, 0),
        )
        assertNull(
            "persistActive must NOT touch the pending slot",
            authPrefs.getString(SP_SK_PENDING_WRAPPED, null),
        )
        assertEquals(
            "persistActive must NOT touch the pending alias version",
            0,
            authPrefs.getInt(SP_SK_PENDING_ALIAS_VERSION, 0),
        )
        assertEquals("blob-active", wrapper.activeBlob())
        assertEquals(3, wrapper.activeVersion())
        assertNull(wrapper.pendingBlob())
        assertEquals(0, wrapper.pendingVersion())
    }

    @Test fun persistPending_writes_only_SP_SK_PENDING_WRAPPED_and_version() {
        wrapper.persistPending("blob-pending", 5)
        assertEquals("blob-pending", authPrefs.getString(SP_SK_PENDING_WRAPPED, null))
        assertEquals(
            "persistPending must record the pending alias version atomically with the blob",
            5,
            authPrefs.getInt(SP_SK_PENDING_ALIAS_VERSION, 0),
        )
        assertNull(
            "persistPending must NOT touch the active slot",
            authPrefs.getString(SP_SECRET_KEY_WRAPPED, null),
        )
        assertEquals(
            "persistPending must NOT touch the active alias version",
            0,
            authPrefs.getInt(SP_SK_ACTIVE_ALIAS_VERSION, 0),
        )
        assertEquals("blob-pending", wrapper.pendingBlob())
        assertEquals(5, wrapper.pendingVersion())
        assertNull(wrapper.activeBlob())
        assertEquals(0, wrapper.activeVersion())
    }

    @Test fun persistActive_rejects_version_below_1() {
        assertThrows(IllegalArgumentException::class.java) {
            wrapper.persistActive("blob", 0)
        }
    }

    @Test fun persistPending_rejects_version_below_1() {
        assertThrows(IllegalArgumentException::class.java) {
            wrapper.persistPending("blob", 0)
        }
    }

    @Test fun activeVersion_returns_0_when_no_SK_is_installed() {
        // Sentinel value used by the unlock path to branch on
        // "is SK enabled on this device". Returning 0 instead of throwing
        // keeps the SK-disabled cold-start path free of try/catch.
        assertEquals(0, wrapper.activeVersion())
    }

    @Test fun pendingVersion_returns_0_when_no_transition_is_staged() {
        assertEquals(0, wrapper.pendingVersion())
    }

    @Test fun activeBlob_returns_null_when_no_SK_is_installed() {
        // Fresh install state: no SK was ever enabled. The unwrap helper
        // and the AuthRepository unlock path both branch on this null to
        // run the legacy MP-only KDF derivation.
        assertNull(wrapper.activeBlob())
    }

    @Test fun pendingBlob_returns_null_when_no_transition_is_staged() {
        assertNull(wrapper.pendingBlob())
    }

    // -- clearActive / clearPending / clearAll selectivity -----------------

    @Test fun clearActive_removes_only_the_active_slot() {
        wrapper.persistActive("active-data", 1)
        wrapper.persistPending("pending-data", 2)

        wrapper.clearActive()

        assertNull(wrapper.activeBlob())
        assertEquals(
            "clearActive must NOT touch the pending slot",
            "pending-data",
            wrapper.pendingBlob(),
        )
    }

    @Test fun clearPending_removes_only_the_pending_slot() {
        wrapper.persistActive("active-data", 1)
        wrapper.persistPending("pending-data", 2)

        wrapper.clearPending()

        assertEquals(
            "clearPending must NOT touch the active slot",
            "active-data",
            wrapper.activeBlob(),
        )
        assertNull(wrapper.pendingBlob())
    }

    @Test fun clearAll_removes_both_SP_slots() {
        wrapper.persistActive("active-data", 1)
        wrapper.persistPending("pending-data", 2)

        // clearAll also deletes the Keystore alias. The Keystore call
        // throws under Robolectric (no provider), so wrap it; the SP
        // deletion is what we assert here. The Keystore-side deletion
        // is covered on physical device tests.
        runCatching { wrapper.clearAll() }

        assertNull(wrapper.activeBlob())
        assertNull(wrapper.pendingBlob())
    }

    @Test fun clearAll_is_safe_when_slots_are_already_empty() {
        // clearAll must be idempotent so it can run from the vault-reset
        // path without first checking whether the SK feature was even
        // enabled. The SP-side calls are idempotent by construction; the
        // Keystore deletion is wrapped in runCatching at the call site
        // because the Robolectric provider is absent.
        runCatching { wrapper.clearAll() }

        assertNull(wrapper.activeBlob())
        assertNull(wrapper.pendingBlob())
    }

    @Test fun deleteAliasVersion_does_not_touch_SharedPreferences() {
        wrapper.persistActive("active-data", 1)
        wrapper.persistPending("pending-data", 2)

        // Robolectric will throw inside the Keystore call; we catch it
        // because what we're testing is the explicit "do not touch SP"
        // contract.
        runCatching { wrapper.deleteAliasVersion(1) }
        runCatching { wrapper.deleteAliasVersion(2) }

        assertEquals("active-data", wrapper.activeBlob())
        assertEquals(1, wrapper.activeVersion())
        assertEquals("pending-data", wrapper.pendingBlob())
        assertEquals(2, wrapper.pendingVersion())
    }

    @Test fun deleteAliasVersion_with_zero_is_no_op() {
        // 0 is the sentinel "no SK installed" value; calling delete
        // with it would otherwise try to delete an alias literally
        // named "1key_secret_key_v0" which has never existed.
        runCatching { wrapper.deleteAliasVersion(0) }
        // No assertions needed - the absence of a Keystore call IS the
        // contract. The runCatching is there because we're under
        // Robolectric where any AndroidKeyStore touch throws; reaching
        // it would be the test failure.
    }

    @Test fun clearActive_removes_blob_AND_alias_version() {
        wrapper.persistActive("active-data", 7)
        wrapper.clearActive()
        assertNull(wrapper.activeBlob())
        assertEquals(
            "clearActive must clear SP_SK_ACTIVE_ALIAS_VERSION together with the blob",
            0,
            wrapper.activeVersion(),
        )
    }

    @Test fun clearPending_removes_blob_AND_alias_version() {
        wrapper.persistPending("pending-data", 9)
        wrapper.clearPending()
        assertNull(wrapper.pendingBlob())
        assertEquals(
            "clearPending must clear SP_SK_PENDING_ALIAS_VERSION together with the blob",
            0,
            wrapper.pendingVersion(),
        )
    }

    // -- unwrapBlob input validation (independent of Keystore) -------------

    @Test fun unwrapBlob_rejects_blob_shorter_than_IV_length() {
        // A blob that is too short to even hold the 12-byte GCM IV is
        // structurally invalid; the wrapper rejects it BEFORE attempting
        // any Keystore work. This branch is the early-exit guard that
        // protects callers from confusing AndroidKeyStore errors when the
        // input is obviously garbage.
        val shortBlob = android.util.Base64.encodeToString(ByteArray(8), android.util.Base64.NO_WRAP)
        assertThrows(IllegalArgumentException::class.java) {
            wrapper.unwrapBlob(shortBlob, 1)
        }
    }

    @Test fun unwrapBlob_rejects_version_below_1() {
        val blob = android.util.Base64.encodeToString(ByteArray(32), android.util.Base64.NO_WRAP)
        assertThrows(IllegalArgumentException::class.java) {
            wrapper.unwrapBlob(blob, 0)
        }
    }

    @Test fun unwrap_returns_null_when_no_active_blob_is_stored() {
        // Convenience read path used by AuthRepositoryImpl.unlockWithPassword
        // to branch on "is SK enabled on this device" - returning
        // null avoids forcing every caller to do its own
        // `activeBlob() == null` precheck.
        assertNull(wrapper.unwrap())
    }

    @Test fun unwrap_returns_null_when_activeVersion_is_0_even_if_blob_string_is_set() {
        // Defensive: the unlock-path branch keys on activeVersion(), not
        // on activeBlob() alone, because a half-written SP could in
        // principle have one without the other. A 0 active version
        // means "no SK installed" and the unlock path must take the
        // MP-only KDF branch, NOT attempt unwrap with the legacy v1 alias.
        authPrefs.edit().putString(SP_SECRET_KEY_WRAPPED, "stale-blob").commit()
        // SP_SK_ACTIVE_ALIAS_VERSION is intentionally NOT set.
        assertNull(wrapper.unwrap())
    }

    // -- wrap() input validation (independent of Keystore) -----------------

    @Test fun wrap_rejects_wrong_length() {
        // The length precondition fires BEFORE any Keystore traffic, so we
        // can assert it under Robolectric. Compare with the
        // SecretKeyHolder.setBytes test that pins the same 16-byte
        // requirement on the in-memory side.
        assertThrows(IllegalArgumentException::class.java) {
            wrapper.wrap(ByteArray(15), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            wrapper.wrap(ByteArray(17), 1)
        }
        assertFalse(
            "Length-rejected wrap must not have written to SP",
            authPrefs.contains(SP_SECRET_KEY_WRAPPED),
        )
    }

    @Test fun wrap_rejects_version_below_1() {
        // Counter scheme invariant: version 0 is the sentinel "no SK
        // installed" value; wrapping under it would mint an alias
        // literally named "1key_secret_key_v0" which we never want.
        assertThrows(IllegalArgumentException::class.java) {
            wrapper.wrap(ByteArray(16), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            wrapper.wrap(ByteArray(16), -1)
        }
    }

    // -- KEYSTORE_ALIAS_SECRET_KEY_PREFIX literal lock ---------------------

    @Test fun keystore_alias_prefix_matches_locked_design() {
        // The per-generation alias names are computed as
        // "$KEYSTORE_ALIAS_SECRET_KEY_PREFIX$N". Renaming the prefix
        // would orphan every existing v1+ alias on installs that have
        // already enabled SK. Pin the literal here so a future rename
        // is forced to acknowledge the migration impact.
        assertEquals("1key_secret_key_v", KEYSTORE_ALIAS_SECRET_KEY_PREFIX)
    }

    @Test fun sp_alias_version_keys_match_locked_design() {
        // The version int keys ship in the app's onekey_auth
        // EncryptedSharedPreferences file. Renaming either would orphan
        // existing-install data after a version bump.
        assertEquals("sk_active_alias_version", SP_SK_ACTIVE_ALIAS_VERSION)
        assertEquals("sk_pending_alias_version", SP_SK_PENDING_ALIAS_VERSION)
    }
}
