package com.roufsyed.onekey.feature.twofa.presentation.screen

import com.roufsyed.onekey.core.domain.model.OtpParams
import com.roufsyed.onekey.core.domain.model.OtpType

/**
 * Display-side formatter that splits an OTP code into readable groups.
 *
 * - Steam Guard: 5 alphanumeric characters rendered as a single contiguous block.
 *   Steam's own client renders codes this way; chunking would mis-cue typing
 *   rhythm because the alphabet and length are deliberately fixed.
 * - 6-digit codes (the universal default): three-digit groups, e.g. `123 456`.
 *   Matches every other authenticator app's convention and the rhythm digits
 *   are read aloud at.
 * - 7- and 8-digit codes: four-digit-first groups, e.g. `1234 5678` or `1234 567`.
 *   Three-digit chunking would leave `1234567` as `123 456 7` which trails off
 *   awkwardly; four-first is the convention Aegis / 1Password / Bitwarden use.
 *
 * The function is intentionally pure and stateless so both [RotatingOtpRow]
 * and [TotpWidget] (and any future surface that displays an OTP) share one
 * source of truth for grouping.
 */
internal fun formatOtpCode(params: OtpParams, code: String): String = when {
    params.type == OtpType.STEAM -> code
    code.length >= 7 -> code.chunked(4).joinToString(" ")
    else -> code.chunked(3).joinToString(" ")
}
