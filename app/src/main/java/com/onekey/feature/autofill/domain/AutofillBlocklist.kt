package com.onekey.feature.autofill.domain

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Package allow/deny filter for fill requests. Two reasons a package belongs
 * here:
 *
 *  1. System surfaces — Settings, SystemUI, providers, setup wizard, launchers
 *     — where credentials are never wanted and where filling could leak
 *     plaintext into the wrong context.
 *  2. Our own package — we never want to fill 1Key itself with itself.
 *
 * Browsers are deliberately not blocked. They are the largest single source of
 * credential fills.
 *
 * The list is intentionally short; the OS already pre-filters most edge cases
 * via `importantForAutofill="no"`. This is defence-in-depth.
 */
@Singleton
class AutofillBlocklist @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val ownPackage: String = context.packageName

    /** True when fills should be suppressed for [packageName]. */
    fun isBlocked(packageName: String): Boolean {
        if (packageName == ownPackage) return true
        if (packageName in STATIC_BLOCK) return true
        if (STATIC_PREFIX_BLOCK.any { packageName.startsWith(it) }) return true
        return false
    }

    private companion object {
        // Exact package names that should never receive fills.
        val STATIC_BLOCK: Set<String> = setOf(
            "android",
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.gms",
            "com.google.android.googlequicksearchbox",
            "com.android.setupwizard",
            "com.google.android.setupwizard",
        )
        // Prefix matches catch families like `com.android.providers.*` and
        // `com.android.launcher*` without hard-coding every variant.
        val STATIC_PREFIX_BLOCK: List<String> = listOf(
            "com.android.providers.",
            "com.android.launcher",
        )
    }
}
