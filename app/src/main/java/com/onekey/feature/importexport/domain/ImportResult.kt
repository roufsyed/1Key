package com.onekey.feature.importexport.domain

import com.onekey.core.domain.model.Credential

data class ImportResult(
    val imported: Int,
    val skipped: List<SkippedCredential>,
    val failed: List<FailedEntry>,
)

data class ParsedImport(
    val credentials: List<Credential>,
    val failed: List<FailedEntry>,
)

data class SkippedCredential(
    val title: String,
    val username: String,
    val reason: SkipReason,
)

enum class SkipReason {
    DUPLICATE_ID,
    DUPLICATE_TITLE_USERNAME,
}

data class FailedEntry(
    val rowIndex: Int,
    val reason: String,
)
