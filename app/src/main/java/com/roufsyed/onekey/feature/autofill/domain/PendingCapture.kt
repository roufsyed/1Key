package com.roufsyed.onekey.feature.autofill.domain

/**
 * One captured save submission held in [AutofillCaptureBuffer] while the user
 * confirms in [com.roufsyed.onekey.feature.autofill.presentation.AutofillSaveActivity].
 *
 * Plaintext credential bytes intentionally never ride Intent extras or
 * SavedState bundles - both can leak through `ActivityManagerService` IPC
 * snapshots and the system Recent Apps thumbnail pipeline. The opaque
 * [token] is the only thing the save Intent carries; the activity reads the
 * buffer by token and clears it on consume.
 */
data class PendingCapture(
    val token: String,
    val username: String?,
    val password: String?,
    val packageName: String,
    val webDomain: String?,
)
