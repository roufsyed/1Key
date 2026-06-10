package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CustomField
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.feature.importexport.domain.ConflictPair
import com.onekey.feature.importexport.domain.ConflictResolution
import com.onekey.feature.importexport.domain.ImportFieldOptions
import com.onekey.feature.importexport.domain.ImportPlan
import com.onekey.feature.importexport.domain.ImportResult
import com.onekey.feature.importexport.domain.MergePair
import com.onekey.feature.importexport.domain.ParsedImport
import com.onekey.feature.importexport.domain.SkipReason
import com.onekey.feature.importexport.domain.SkippedCredential
import com.onekey.feature.importexport.domain.UrlTitleExtractor
import com.onekey.feature.importexport.domain.VaultImporter
import javax.inject.Inject

class ImportVaultUseCase @Inject constructor(
    private val repository: CredentialRepository,
    private val importer: VaultImporter,
) {
    suspend fun isEncrypted(filePath: String): Boolean = importer.isEncrypted(filePath)

    suspend fun parseOnly(filePath: String): AppResult<ParsedImport> = importer.parse(filePath)

    suspend fun parseOnlyEncrypted(filePath: String, password: CharArray): AppResult<ParsedImport> =
        importer.parseEncrypted(filePath, password)

    /**
     * Classifies each parsed credential against the vault (active + recycle bin):
     * new, auto-mergeable (compatible fields), or conflict (needs user decision).
     * No DB writes happen here - call [applyPlan] once the user has decided how to
     * handle any conflicts.
     */
    suspend fun planImport(
        parsed: ParsedImport,
        fieldOptions: ImportFieldOptions,
    ): AppResult<ImportPlan> {
        val activeResult = repository.getAllCredentials()
        if (activeResult is AppResult.Error) return activeResult
        val binResult = repository.getAllInRecycleBin()
        if (binResult is AppResult.Error) return binResult

        val active = (activeResult as AppResult.Success).data
        val bin = (binResult as AppResult.Success).data
        val activeIds = active.mapTo(HashSet()) { it.id }

        // (title.trim() to username.trim()) -> matched existing credential. Active beats bin
        // when both contain the same key (active is the source of truth); a bin-only match
        // gets restored on apply.
        val byKey = HashMap<Pair<String, String>, MatchedExisting>()
        for (c in bin) byKey[c.title.trim() to c.username.trim()] = MatchedExisting(c, fromBin = true)
        for (c in active) byKey[c.title.trim() to c.username.trim()] = MatchedExisting(c, fromBin = false)

        val filtered = parsed.credentials.map { it.applyFieldOptions(fieldOptions) }

        val newItems = mutableListOf<Credential>()
        val autoMerges = mutableListOf<MergePair>()
        val conflicts = mutableListOf<ConflictPair>()
        val skipped = mutableListOf<SkippedCredential>()

        for (incoming in filtered) {
            if (incoming.id.isNotBlank() && incoming.id in activeIds) {
                skipped.add(SkippedCredential(incoming.title, incoming.username, SkipReason.DUPLICATE_ID))
                continue
            }
            val match = byKey[incoming.title.trim() to incoming.username.trim()]
            if (match == null) {
                newItems.add(incoming)
                continue
            }
            val clashing = conflictingFields(match.credential, incoming)
            if (clashing.isEmpty()) {
                autoMerges.add(MergePair(existing = match.credential, incoming = incoming, restoreFromBin = match.fromBin))
            } else {
                conflicts.add(
                    ConflictPair(
                        existing = match.credential,
                        incoming = incoming,
                        conflictingFields = clashing,
                        restoreFromBin = match.fromBin,
                    )
                )
            }
        }

        return AppResult.Success(
            ImportPlan(
                newItems = newItems,
                autoMerges = autoMerges,
                conflicts = conflicts,
                skipped = skipped,
                failed = parsed.failed,
            )
        )
    }

    /**
     * Executes a previously-built [ImportPlan] using [conflictResolution] for any conflicts.
     * Auto-merges always run silently (no conflicts -> no user decision needed). Items that
     * matched a recycle-bin entry are restored as part of the merge.
     */
    suspend fun applyPlan(
        plan: ImportPlan,
        conflictResolution: ConflictResolution,
    ): AppResult<ImportResult> {
        val toUpsert = mutableListOf<Credential>()
        var restoredFromBin = 0
        var mergedOnConflict = 0
        var addedSeparately = 0

        toUpsert.addAll(plan.newItems)

        for (merge in plan.autoMerges) {
            toUpsert.add(merged(merge.existing, merge.incoming))
            if (merge.restoreFromBin) restoredFromBin++
        }

        for (conflict in plan.conflicts) {
            when (conflictResolution) {
                ConflictResolution.MERGE -> {
                    // Existing wins on clashing fields; compatible (one-side-empty) fields still fill.
                    toUpsert.add(merged(conflict.existing, conflict.incoming))
                    if (conflict.restoreFromBin) restoredFromBin++
                    mergedOnConflict++
                }
                ConflictResolution.ADD_AS_SEPARATE -> {
                    // Insert incoming as a brand-new row; existing is untouched.
                    toUpsert.add(conflict.incoming.copy(id = ""))
                    addedSeparately++
                }
            }
        }

        if (toUpsert.isNotEmpty()) {
            val importResult = repository.importCredentials(toUpsert)
            if (importResult is AppResult.Error) return importResult
        }

        return AppResult.Success(
            ImportResult(
                imported = plan.newItems.size + addedSeparately,
                autoMerged = plan.autoMerges.size,
                mergedOnConflict = mergedOnConflict,
                addedSeparately = addedSeparately,
                restoredFromBin = restoredFromBin,
                skipped = plan.skipped,
                failed = plan.failed,
            )
        )
    }

    /**
     * Convenience: plan + apply with default MERGE for any conflicts. Kept for callers
     * that don't surface a Review screen and just want the safest behavior.
     */
    suspend fun saveImport(
        parsed: ParsedImport,
        fieldOptions: ImportFieldOptions,
    ): AppResult<ImportResult> {
        val plan = planImport(parsed, fieldOptions)
        if (plan is AppResult.Error) return plan
        return applyPlan((plan as AppResult.Success).data, ConflictResolution.MERGE)
    }

    private data class MatchedExisting(val credential: Credential, val fromBin: Boolean)

    private fun Credential.applyFieldOptions(opts: ImportFieldOptions): Credential {
        // Derive against the source `url` (this.url) before the url toggle below
        // potentially blanks it - the rescue must run on the parsed value, not
        // the post-filter one.
        val derivedTitle = if (opts.deriveTitleFromUrl && title.isBlank()) {
            UrlTitleExtractor.extractTitle(url) ?: title
        } else title
        return copy(
            title = derivedTitle,
            username = if (opts.username) username else "",
            password = if (opts.password) password else "",
            url = if (opts.url) url else "",
            notes = if (opts.notes) notes else "",
            otpParams = if (opts.totp) otpParams else null,
            tags = if (opts.tags) tags else emptyList(),
            customFields = customFields.filter { it.key in opts.customFieldKeys },
            isFavorite = if (opts.isFavorite) isFavorite else false,
        )
    }

    /**
     * Returns the names of fields that contain non-empty differing values between
     * [existing] and [incoming]. Empty list means a clean merge is possible.
     *
     * Single-value fields: equal-or-one-side-empty is compatible. Both non-empty + differ = conflict.
     * Tags / custom fields: must match exactly to be compatible (no auto-union - too ambiguous).
     */
    private fun conflictingFields(existing: Credential, incoming: Credential): List<String> {
        val clashes = mutableListOf<String>()
        if (clashesString(existing.password, incoming.password)) clashes.add("password")
        if (clashesString(existing.url, incoming.url)) clashes.add("url")
        if (clashesString(existing.notes, incoming.notes)) clashes.add("notes")
        if (clashesNullableString(existing.otpParams?.secret, incoming.otpParams?.secret)) clashes.add("2FA secret")
        if (clashesList(existing.tags, incoming.tags)) clashes.add("tags")
        if (clashesCustomFields(existing.customFields, incoming.customFields)) clashes.add("custom fields")
        return clashes
    }

    private fun clashesString(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        return a != b
    }

    private fun clashesNullableString(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return a != b
    }

    private fun clashesList(a: List<String>, b: List<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return a.toSet() != b.toSet()
    }

    private fun clashesCustomFields(a: List<CustomField>, b: List<CustomField>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return a.map { it.key to it.value }.toSet() != b.map { it.key to it.value }.toSet()
    }

    /**
     * Existing wins on every set field; incoming fills only fields that existing left empty.
     * If [existing] was soft-deleted, the result is automatically active (deletedAt = null).
     */
    private fun merged(existing: Credential, incoming: Credential): Credential = existing.copy(
        username = existing.username.takeIf { it.isNotBlank() } ?: incoming.username,
        password = existing.password.takeIf { it.isNotBlank() } ?: incoming.password,
        url = existing.url.takeIf { it.isNotBlank() } ?: incoming.url,
        notes = existing.notes.takeIf { it.isNotBlank() } ?: incoming.notes,
        // Existing OTP enrolment wins as a unit - algorithm/digits/period/counter
        // are tied to the secret, so we never mix one credential's secret with
        // another's params.
        otpParams = existing.otpParams ?: incoming.otpParams,
        tags = if (existing.tags.isNotEmpty()) existing.tags else incoming.tags,
        customFields = if (existing.customFields.isNotEmpty()) existing.customFields else incoming.customFields,
        isFavorite = existing.isFavorite || incoming.isFavorite,
        deletedAt = null,
    )
}
