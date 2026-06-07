package com.onekey.feature.autofill.domain

import android.service.autofill.FillRequest
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes the `SaveInfo` declaration to attach to a `FillResponse` so the
 * framework knows to fire `onSaveRequest` after the user submits the form.
 *
 * Honest behaviour notes for v1:
 *
 *  - `SAVE_DATA_TYPE_*` bits are computed dynamically from what the parser
 *    found. A password-reset form with only password fields ships
 *    `SAVE_DATA_TYPE_PASSWORD` alone - combining with `_USERNAME` would
 *    cause the framework to suppress the save prompt when no username is
 *    present (the required-IDs contract is "all listed must change").
 *
 *  - **Compat-mode short-circuit:** when the framework sets
 *    `FLAG_COMPATIBILITY_MODE_REQUEST`, password values arrive masked
 *    (asterisks) at save time. Persisting those would write garbage into
 *    the vault. We return `null` so no save prompt fires for that flow.
 *    Fill still works in compat mode - only save is suppressed.
 *
 *  - `FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE` is always set so multi-screen
 *    sign-up flows still produce one consolidated save prompt.
 */
@Singleton
class SaveInfoBuilder @Inject constructor() {

    /**
     * @return a `SaveInfo` describing the credential save target, or `null`
     *   when no save prompt should be triggered for this request.
     */
    fun build(parsed: ParsedFields, requestFlags: Int): SaveInfo? {
        if (!parsed.isFillable()) return null

        val passwordId = parsed.password?.autofillId
        val usernameId = parsed.username?.autofillId
        val emailId = parsed.email?.autofillId
        val identityId = usernameId ?: emailId

        // Compat mode + a password partition means the password we'd save is
        // masked. Better to drop the prompt than to write asterisks.
        val isCompat = (requestFlags and FillRequest.FLAG_COMPATIBILITY_MODE_REQUEST) != 0
        if (isCompat && passwordId != null) return null

        var typeBits = 0
        val required = mutableListOf<AutofillId>()
        if (passwordId != null) {
            typeBits = typeBits or SaveInfo.SAVE_DATA_TYPE_PASSWORD
            required += passwordId
        }
        if (identityId != null) {
            // Both username and email collapse to the SAVE_DATA_TYPE_USERNAME bit
            // when present alongside a password (Android does not have a
            // SAVE_DATA_TYPE_EMAIL - emails ride the username bit).
            typeBits = typeBits or SaveInfo.SAVE_DATA_TYPE_USERNAME
            required += identityId
        }

        if (typeBits == 0 || required.isEmpty()) return null

        return SaveInfo.Builder(typeBits, required.toTypedArray())
            .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            .build()
    }
}
