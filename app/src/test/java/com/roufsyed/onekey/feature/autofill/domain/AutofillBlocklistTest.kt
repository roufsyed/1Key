package com.roufsyed.onekey.feature.autofill.domain

import android.app.Application
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifies the deny-set hardcoded into [AutofillBlocklist] - system-UI surfaces,
 * setup wizard, providers, launcher, plus our own package.
 *
 * Robolectric supplies the application Context so the blocklist can read its
 * own package (which is set to the test target - anything stable will do).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutofillBlocklistTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val blocklist = AutofillBlocklist(context)

    @Test fun own_package_is_blocked() {
        assertTrue(blocklist.isBlocked(context.packageName))
    }

    @Test fun system_settings_is_blocked() {
        assertTrue(blocklist.isBlocked("com.android.settings"))
    }

    @Test fun system_ui_is_blocked() {
        assertTrue(blocklist.isBlocked("com.android.systemui"))
    }

    @Test fun google_setup_wizard_is_blocked() {
        assertTrue(blocklist.isBlocked("com.google.android.setupwizard"))
    }

    @Test fun stock_setup_wizard_is_blocked() {
        assertTrue(blocklist.isBlocked("com.android.setupwizard"))
    }

    @Test fun system_provider_prefix_is_blocked() {
        assertTrue(blocklist.isBlocked("com.android.providers.contacts"))
        assertTrue(blocklist.isBlocked("com.android.providers.media"))
    }

    @Test fun launcher_prefix_is_blocked() {
        assertTrue(blocklist.isBlocked("com.android.launcher3"))
    }

    @Test fun chrome_is_not_blocked() {
        // Browsers must not be in the blocklist - they are the dominant source
        // of web logins.
        assertFalse(blocklist.isBlocked("com.android.chrome"))
        assertFalse(blocklist.isBlocked("com.google.android.apps.chrome"))
    }

    @Test fun arbitrary_user_app_is_not_blocked() {
        assertFalse(blocklist.isBlocked("com.example.someapp"))
        assertFalse(blocklist.isBlocked("com.facebook.katana"))
    }
}
