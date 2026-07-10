package com.roufsyed.onekey.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Plain-JVM behavioural locks for [NotesDisplayPrefsRepositoryImpl].
 *
 * Spins up a real [androidx.datastore.core.DataStore] backed by a temp file - 
 * no Robolectric, no Android stub needed - so the test exercises the same
 * `edit`/`data.map` paths that production runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesDisplayPrefsRepositoryImplTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var appScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repo: NotesDisplayPrefsRepositoryImpl
    private lateinit var prefsFile: File

    @Before fun setup() {
        appScope = CoroutineScope(SupervisorJob() + testDispatcher)
        prefsFile = tempFolder.newFile("notes_display_test.preferences_pb")
        // The factory requires the file NOT to exist when first opened (it'll
        // be created on the first write). TemporaryFolder.newFile already
        // creates an empty file, so delete it - DataStore writes an empty
        // proto into a fresh file lazily on first read.
        prefsFile.delete()
        dataStore = PreferenceDataStoreFactory.create(
            scope = appScope,
            produceFile = { prefsFile },
        )
        repo = NotesDisplayPrefsRepositoryImpl(
            dataStore = dataStore,
            appScope = appScope,
        )
    }

    @After fun teardown() {
        appScope.cancel()
    }

    // ── initial state ────────────────────────────────────────────────────

    @Test fun observeIdsInPlainSourceMode_initially_emits_empty_set() = runTest(testDispatcher) {
        repo.observeIdsInPlainSourceMode().test {
            assertEquals(emptySet<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun observeAutoFlippedIds_initially_emits_empty_set() = runTest(testDispatcher) {
        repo.observeAutoFlippedIds().test {
            assertEquals(emptySet<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── setPlainSource ───────────────────────────────────────────────────

    @Test fun setPlainSource_true_adds_id_to_plain_source_set() = runTest(testDispatcher) {
        repo.observeIdsInPlainSourceMode().test {
            assertEquals(emptySet<String>(), awaitItem())

            repo.setPlainSource("cred-1", isPlainSource = true)
            assertEquals(setOf("cred-1"), awaitItem())

            repo.setPlainSource("cred-2", isPlainSource = true)
            assertEquals(setOf("cred-1", "cred-2"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun setPlainSource_false_removes_id_from_plain_source_set() = runTest(testDispatcher) {
        repo.setPlainSource("cred-1", isPlainSource = true)
        repo.setPlainSource("cred-2", isPlainSource = true)

        repo.observeIdsInPlainSourceMode().test {
            assertEquals(setOf("cred-1", "cred-2"), awaitItem())

            repo.setPlainSource("cred-1", isPlainSource = false)
            assertEquals(setOf("cred-2"), awaitItem())

            repo.setPlainSource("cred-2", isPlainSource = false)
            // Removing the last entry empties the set (and removes the key
            // from the underlying file, but the observed value is still the
            // empty set).
            assertEquals(emptySet<String>(), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun setPlainSource_false_for_id_not_in_set_is_noop() = runTest(testDispatcher) {
        repo.setPlainSource("cred-1", isPlainSource = true)

        repo.observeIdsInPlainSourceMode().test {
            assertEquals(setOf("cred-1"), awaitItem())

            // Removing a non-member should not change the observed value.
            // distinctUntilChanged() must collapse this duplicate emission.
            repo.setPlainSource("ghost", isPlainSource = false)
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun setPlainSource_true_for_already_present_id_is_noop() = runTest(testDispatcher) {
        repo.setPlainSource("cred-1", isPlainSource = true)

        repo.observeIdsInPlainSourceMode().test {
            assertEquals(setOf("cred-1"), awaitItem())

            repo.setPlainSource("cred-1", isPlainSource = true)
            // Same set -> distinctUntilChanged collapses; no further emission.
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── markAutoFlipped ──────────────────────────────────────────────────

    @Test fun markAutoFlipped_adds_id_to_auto_flipped_set() = runTest(testDispatcher) {
        repo.observeAutoFlippedIds().test {
            assertEquals(emptySet<String>(), awaitItem())

            repo.markAutoFlipped("cred-1")
            assertEquals(setOf("cred-1"), awaitItem())

            repo.markAutoFlipped("cred-2")
            assertEquals(setOf("cred-1", "cred-2"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun markAutoFlipped_for_already_present_id_is_noop() = runTest(testDispatcher) {
        repo.markAutoFlipped("cred-1")

        repo.observeAutoFlippedIds().test {
            assertEquals(setOf("cred-1"), awaitItem())

            repo.markAutoFlipped("cred-1")
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── independence of the two sets ─────────────────────────────────────

    @Test fun plain_source_and_auto_flipped_sets_are_independent() = runTest(testDispatcher) {
        repo.setPlainSource("cred-A", isPlainSource = true)
        repo.markAutoFlipped("cred-B")

        repo.observeIdsInPlainSourceMode().test {
            assertEquals(setOf("cred-A"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        repo.observeAutoFlippedIds().test {
            assertEquals(setOf("cred-B"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun same_id_can_appear_in_both_sets_simultaneously() = runTest(testDispatcher) {
        // The user might have manually toggled plain source AND the renderer
        // auto-flipped the same credential. Both sets must be able to hold
        // the same ID without interfering.
        repo.setPlainSource("cred-X", isPlainSource = true)
        repo.markAutoFlipped("cred-X")

        repo.observeIdsInPlainSourceMode().test {
            assertEquals(setOf("cred-X"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        repo.observeAutoFlippedIds().test {
            assertEquals(setOf("cred-X"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── distinct emissions ───────────────────────────────────────────────

    @Test fun flows_emit_distinct_values_on_change() = runTest(testDispatcher) {
        repo.observeIdsInPlainSourceMode().test {
            assertEquals(emptySet<String>(), awaitItem())
            repo.setPlainSource("a", true);  assertEquals(setOf("a"), awaitItem())
            repo.setPlainSource("a", true);  expectNoEvents() // duplicate
            repo.setPlainSource("b", true);  assertEquals(setOf("a", "b"), awaitItem())
            repo.setPlainSource("a", false); assertEquals(setOf("b"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── vault reset (DataStore clear) ────────────────────────────────────

    @Test fun vault_reset_clears_both_sets() = runTest(testDispatcher) {
        repo.setPlainSource("cred-1", isPlainSource = true)
        repo.setPlainSource("cred-2", isPlainSource = true)
        repo.markAutoFlipped("cred-3")

        // Sanity precondition.
        repo.observeIdsInPlainSourceMode().test {
            assertEquals(setOf("cred-1", "cred-2"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        repo.observeAutoFlippedIds().test {
            assertEquals(setOf("cred-3"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        // Simulate a vault reset by clearing the entire DataStore. This is
        // the operation a future "reset vault" flow would invoke on the
        // notes-display DataStore - exactly what we promise in the
        // class-level KDoc.
        dataStore.edit { it.clear() }

        repo.observeIdsInPlainSourceMode().test {
            assertEquals(emptySet<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        repo.observeAutoFlippedIds().test {
            assertEquals(emptySet<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

}
