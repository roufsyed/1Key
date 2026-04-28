package com.onekey.feature.importexport.domain

import com.onekey.core.domain.model.Credential

data class ImportResult(
    /** Brand-new credentials inserted (no match in active or recycle bin). */
    val imported: Int,
    /** Existing credentials that were enriched by silently merging compatible fields. */
    val autoMerged: Int = 0,
    /** Conflicting credentials merged into existing per the user's choice. */
    val mergedOnConflict: Int = 0,
    /** Conflicting credentials inserted as separate items per the user's choice. */
    val addedSeparately: Int = 0,
    /** Items restored from the recycle bin as part of the merge. */
    val restoredFromBin: Int = 0,
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

/**
 * Pre-flight classification of an import. Built by [com.onekey.core.domain.usecase.ImportVaultUseCase.planImport]
 * and applied by [com.onekey.core.domain.usecase.ImportVaultUseCase.applyPlan] once the user
 * has chosen a [ConflictResolution].
 */
data class ImportPlan(
    val newItems: List<Credential>,
    val autoMerges: List<MergePair>,
    val conflicts: List<ConflictPair>,
    val skipped: List<SkippedCredential>,
    val failed: List<FailedEntry>,
) {
    /** True when the plan can be applied silently — no user decision needed. */
    val needsConflictResolution: Boolean get() = conflicts.isNotEmpty()
}

/** A clean (no-conflict) merge: fill compatible fields from [incoming] into [existing]. */
data class MergePair(
    val existing: Credential,
    val incoming: Credential,
    /** True if [existing] currently lives in the recycle bin and will be restored on apply. */
    val restoreFromBin: Boolean,
)

/** A merge with at least one field clash — needs a user decision. */
data class ConflictPair(
    val existing: Credential,
    val incoming: Credential,
    /** Field names with non-empty differing values, e.g. ["password", "notes"]. */
    val conflictingFields: List<String>,
    val restoreFromBin: Boolean,
)

/** What to do with conflicts: merge into existing (existing wins), or add as a separate item. */
enum class ConflictResolution { MERGE, ADD_AS_SEPARATE }

