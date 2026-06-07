package com.onekey.feature.autofill.domain

/**
 * Coarse classification of a parsed Autofill request. v1 detects only LOGIN
 * vs UNKNOWN - sign-up detection ships in v1.1 (see project memory
 * `project_autofill.md` for the deferred-scope list).
 */
enum class AutofillScenario {
    /** A credential-fill form: at least one of username / email / password is present. */
    LOGIN,
    /** No fillable fields recognised, or the structure is non-credential (search, etc.). */
    UNKNOWN,
}
