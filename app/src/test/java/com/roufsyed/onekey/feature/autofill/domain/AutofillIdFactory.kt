package com.roufsyed.onekey.feature.autofill.domain

import android.view.autofill.AutofillId

/**
 * Test-only factory that reaches for the `AutofillId(int)` constructor via
 * reflection. The Android framework marks it as `@TestApi`, so it is present
 * in the Robolectric-provided runtime but not exposed by the public android.jar
 * stub used at compile time. Reflection lets the tests build fixtures without
 * dragging in an AssistStructure mock for every parser case.
 *
 * IDs supplied by callers are an internal sequence - the parser does not care
 * about specific values, only equality.
 */
internal fun newAutofillId(id: Int): AutofillId {
    val constructor = AutofillId::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
    constructor.isAccessible = true
    return constructor.newInstance(id)
}
