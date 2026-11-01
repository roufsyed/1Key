package com.roufsyed.onekey.feature.passkey.presentation

import com.roufsyed.onekey.core.domain.passkey.CallerKind

/**
 * What the explanatory screen talks about when 1Key cannot serve a request.
 * A superset of the caller-policy refusals: the third case is about the
 * site's request, not the caller.
 */
enum class ExplainTopic {
    /** The caller claimed a web origin but is on no privileged-browser list. */
    UNKNOWN_BROWSER,
    /** APK signed by several keys at once; no single identity to pin. */
    MULTIPLE_SIGNERS,
    /** The site's accepted key types leave out ES256, the only type 1Key makes. */
    UNSUPPORTED_ALGORITHM;

    companion object {
        fun of(reason: CallerKind.Reason): ExplainTopic = when (reason) {
            CallerKind.Reason.UNKNOWN_BROWSER -> UNKNOWN_BROWSER
            CallerKind.Reason.MULTIPLE_SIGNERS -> MULTIPLE_SIGNERS
        }

        /** Lenient parse of an intent extra; an unknown value explains the most common case. */
        fun parse(name: String?): ExplainTopic = entries.firstOrNull { it.name == name } ?: UNKNOWN_BROWSER
    }
}
