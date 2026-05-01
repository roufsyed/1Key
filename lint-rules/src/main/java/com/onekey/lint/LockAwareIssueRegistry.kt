package com.onekey.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/**
 * Registry that AGP's lint runner discovers via the
 * `Lint-Registry-v2: com.onekey.lint.LockAwareIssueRegistry` manifest attribute
 * set in `lint-rules/build.gradle.kts`. Lists every custom issue this module
 * contributes; right now there is exactly one.
 *
 * `api = CURRENT_API` pins the registry to the lint API version it was compiled
 * against. AGP refuses to load registries built against a newer lint API to
 * prevent runtime ABI breakage. `minApi` lets us tolerate older lint runners
 * loading us — kept reasonably low so a developer running an older Studio
 * build doesn't get "registry too new" warnings.
 */
class LockAwareIssueRegistry : IssueRegistry() {

    override val issues: List<Issue> = listOf(
        UnsafeUnlockableSurfaceDetector.ISSUE,
    )

    override val api: Int = CURRENT_API

    override val minApi: Int = 8

    override val vendor: Vendor = Vendor(
        vendorName = "1Key",
        identifier = "com.onekey.lint",
    )
}
