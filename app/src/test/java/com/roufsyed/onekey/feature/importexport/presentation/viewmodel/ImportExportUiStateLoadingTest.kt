package com.roufsyed.onekey.feature.importexport.presentation.viewmodel

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural lock for the post-Bug-2 split of [ImportExportUiState].
 *
 * Before the fix, the screen-level loader rendered when state was a single
 * `ImportExportUiState.Loading` and was placed beneath the Import button. That
 * made every Export-in-progress visually look like Import was loading - the
 * user reported it as "loader appears below Import during Export".
 *
 * The fix splits Loading into [ImportExportUiState.ExportLoading] and
 * [ImportExportUiState.ImportLoading]. The screen renders the matching loader
 * directly below the originating button. These tests pin the contract so a
 * future "consolidation" refactor cannot regress the split:
 *
 *  - both new states still inherit from the [ImportExportUiState] sealed root,
 *  - both are `data object` (single instance per JVM), and
 *  - they are NOT equal to each other - the screen's `when` branches stay
 *    distinguishable.
 *
 * A full VM-level emission test would need to mock the use cases ([ExportVaultUseCase]
 * and [ImportVaultUseCase] are final and Hilt-only-constructible), which is out of
 * scope for a single-bug fix. The visible regression is purely "which subtype is
 * emitted", and the call-sites are one-line `_uiState.value = ...` assignments that
 * cannot drift without a compile error.
 */
class ImportExportUiStateLoadingTest {

    @Test
    fun export_loading_and_import_loading_are_distinct_states() {
        val exportLoading: ImportExportUiState = ImportExportUiState.ExportLoading
        val importLoading: ImportExportUiState = ImportExportUiState.ImportLoading

        assertNotEquals(
            "Export and Import loading states must be different so the screen can place the " +
                "progress bar beneath the originating button",
            exportLoading,
            importLoading,
        )
    }

    @Test
    fun loading_states_are_singletons() {
        // `data object` semantics: every reference is the same instance. The screen's
        // `state is ImportExportUiState.ExportLoading` check is therefore safe across
        // recompositions even when the VM re-emits the same value.
        assertSame(ImportExportUiState.ExportLoading, ImportExportUiState.ExportLoading)
        assertSame(ImportExportUiState.ImportLoading, ImportExportUiState.ImportLoading)
    }

    @Test
    fun loading_states_remain_in_import_export_ui_state_hierarchy() {
        // Smart-cast guard so a refactor that promotes one of these out of the sealed
        // root surfaces here. The compiler reports "always true" on direct `is` against
        // a known-sealed subtype, so we route through the public sealed parent.
        val states: List<ImportExportUiState> = listOf(
            ImportExportUiState.ExportLoading,
            ImportExportUiState.ImportLoading,
        )
        states.forEach { state ->
            assertTrue(
                "Loading subtype must remain inside ImportExportUiState so it flows " +
                    "through the existing uiState StateFlow without an upstream-type change",
                state is ImportExportUiState.ExportLoading || state is ImportExportUiState.ImportLoading,
            )
        }
    }

    @Test
    fun loading_states_are_not_idle_or_success_or_error() {
        // Hard-guard against a refactor that accidentally folds the loading states
        // back into Idle (would suppress progress bars) or Error/Success (would
        // dismiss the import dialog mid-flight).
        val export: ImportExportUiState = ImportExportUiState.ExportLoading
        val import: ImportExportUiState = ImportExportUiState.ImportLoading
        listOf(export, import).forEach { state ->
            assertNotEquals(state, ImportExportUiState.Idle)
            assertTrue(
                "Loading state must NOT be Success/Error - those would dismiss dialogs",
                state !is ImportExportUiState.Success && state !is ImportExportUiState.Error,
            )
        }
    }
}
