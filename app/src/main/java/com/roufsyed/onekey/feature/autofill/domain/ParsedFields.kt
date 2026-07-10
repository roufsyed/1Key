package com.roufsyed.onekey.feature.autofill.domain

import android.os.Parcelable
import android.view.autofill.AutofillId
import kotlinx.parcelize.Parcelize

/**
 * Result of walking a focused activity's `AssistStructure` and classifying its
 * fillable views. The matcher consumes [packageName] and [webDomain] to find
 * candidate credentials; the service consumes the field references to build a
 * [android.service.autofill.Dataset]. Parcelable end-to-end so the unlock
 * activity can rehydrate the same field set the service originally parsed.
 *
 * Invariants enforced by the parser:
 *  - [scenario] is `LOGIN` iff [isFillable] returns true.
 *  - [packageName] is the call-site package (`AssistStructure.activityComponent`),
 *    never the autofill-service host's own package.
 *  - [webDomain] is lower-cased and stripped of a leading `www.` if present.
 *    Never `null` for fills originating in a WebView with a resolvable host.
 */
@Parcelize
data class ParsedFields(
    val username: AutofillField?,
    val password: AutofillField?,
    val email: AutofillField?,
    val scenario: AutofillScenario,
    val packageName: String,
    val webDomain: String?,
) : Parcelable {

    /** True when at least one credential-shaped field was recognised. */
    fun isFillable(): Boolean = username != null || password != null || email != null

    /**
     * AutofillIds we'll attach to a Dataset. For the locked-vault chip the
     * framework expects a placeholder bound to each ID so the chip is offered
     * on whichever field the user is focused into.
     */
    fun targetIds(): List<AutofillId> = buildList {
        username?.let { add(it.autofillId) }
        password?.let { add(it.autofillId) }
        email?.let { add(it.autofillId) }
    }
}
