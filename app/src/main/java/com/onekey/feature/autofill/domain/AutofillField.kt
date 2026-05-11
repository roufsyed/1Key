package com.onekey.feature.autofill.domain

import android.os.Parcelable
import android.view.autofill.AutofillId
import kotlinx.parcelize.Parcelize

/**
 * One field recognised by the parser. Carries the [AutofillId] needed to
 * write a value back through the framework, plus the contextual metadata
 * the matcher consumes (package and optional web domain). Pure data — no
 * Android-side behaviour beyond Parcel-ability.
 *
 * Parcelable so [ParsedFields] can ride in a `PendingIntent` extra without
 * losing its `AutofillId` references across the process boundary the OS
 * imposes between `onFillRequest` and the unlock activity.
 */
@Parcelize
data class AutofillField(
    val autofillId: AutofillId,
    val type: Type,
) : Parcelable {
    enum class Type { USERNAME, PASSWORD, EMAIL }
}
