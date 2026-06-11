package com.onekey.feature.importexport.presentation.screen

import com.onekey.core.domain.usecase.ExportFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the default filename suggested by the SAF document-create picker for a manual
 * Backup -> Export action. The timestamp segment prevents a fresh manual export from
 * silently overwriting (or merely visually duplicating) the sync-engine's
 * `vault-backup.1key` when the user picks the same destination folder for both.
 *
 * Format: `1key_backup_yyyy-MM-dd_HHmm.<ext>`
 *  - `<ext>` is `1key` when [encrypted] is true (encrypted V5 envelope)
 *  - `<ext>` is the lowercase [format] name otherwise (`json`, `csv`)
 *
 * The clock is passed in as [nowMillis] so unit tests can assert deterministic output;
 * production callers should pass `System.currentTimeMillis()`.
 */
internal fun defaultExportFilename(
    encrypted: Boolean,
    format: ExportFormat,
    nowMillis: Long,
): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(nowMillis))
    val extension = if (encrypted) "1key" else format.name.lowercase(Locale.US)
    return "1key_backup_${stamp}.${extension}"
}
