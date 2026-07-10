package com.roufsyed.onekey.feature.autofill.domain

import android.view.autofill.AutofillId

/**
 * Pure data carrier holding everything the [FieldParser] needs about one
 * `AssistStructure.ViewNode`. Exists so the parser can be unit-tested on
 * the JVM - `ViewNode` is final and impossible to construct outside the
 * Android framework.
 *
 * `StructureWalker` is the only producer; tests build [RawNode] instances
 * directly. Keep this type free of Android dependencies beyond [AutofillId],
 * which is itself JVM-instantiable in tests.
 */
data class RawNode(
    val autofillId: AutofillId,
    /** `View.AUTOFILL_HINT_*` strings declared on the node. Lower-cased internally. */
    val autofillHints: List<String>,
    /** Resource entry name, e.g. `username_input`. Lower-cased internally. */
    val idEntry: String?,
    /** Concatenated `hint` string. Lower-cased internally. */
    val hint: String?,
    /** Raw `inputType` flags (bit mask). */
    val inputType: Int,
    /** HTML attributes `(name, value)` for nodes rendered inside a WebView. */
    val htmlAttributes: List<Pair<String, String>>,
    /** First non-null `webDomain` seen along the ancestor chain. */
    val webDomain: String?,
    /** `importantForAutofill` value as reported by the structure. */
    val importantForAutofill: Int,
    /** `className` reported by the framework. Used to drop non-input nodes. */
    val className: String?,
)
