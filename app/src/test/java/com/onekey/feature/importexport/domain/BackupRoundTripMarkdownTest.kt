package com.onekey.feature.importexport.domain

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.usecase.ExportFormat
import com.onekey.core.security.CryptoManager
import com.onekey.core.security.SecretKeyHolder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Stability guarantee for markdown notes across the CSV export -> import -> export
 * cycle.
 *
 * The notesNormalize pipeline collapses CRLF / lone CR to LF and strips a leading
 * BOM. Once a credential has been through one export-import round, its notes are
 * already canonicalised. A SECOND round must therefore produce a byte-identical
 * file to the first - if it does not, normalisation is non-idempotent and every
 * backup-restore cycle drifts.
 *
 * The fixture is deliberately gnarly:
 *   - a fenced code block with embedded newlines (LF round-trip)
 *   - a GFM table with pipe-delimited cells (CSV writer must escape its
 *     pipe / comma / quote without corrupting markdown structure)
 *   - a link whose title carries embedded double quotes ("smart" quotes round-trip)
 *
 * Comparison uses SHA-256 over the on-disk bytes - the only signal that catches
 * every flavour of drift (trailing-newline, BOM re-introduction, escape rule
 * change). String equality would mask file-system newline mangling on Windows
 * CI, hashing the bytes does not.
 *
 * The expected steady state is reached after the FIRST round. Round 1 may differ
 * from the pre-export source if the source carried CR / CRLF / BOM - that is the
 * whole point of normalisation. Rounds 2..N must equal round 1 exactly.
 */
class BackupRoundTripMarkdownTest {

    @get:Rule val tmp = TemporaryFolder()

    private val crypto = CryptoManager()
    // The exporter pulls in SecretKeyHolder for the encrypted V5 path;
    // this test only exercises plaintext export, so a fresh holder is
    // sufficient. The KDF params and SK-enabled flag are passed via
    // EncryptedExportContext at call time - no auth repo dependency here.
    private val exporter = VaultExporterImpl(crypto, SecretKeyHolder())
    private val importer = VaultImporterImpl(crypto)

    @Test fun csv_round_trip_of_markdown_notes_is_byte_identical_after_first_pass() = runBlocking {
        // Markdown content that exercises every CSV-escape edge:
        //   - fenced code blocks with embedded newlines (notes survive
        //     multi-line content)
        //   - GFM table rows with literal pipes (CSV writer must NOT
        //     mistake pipes for delimiters; we use comma-delimited CSV)
        //   - link with embedded double quotes (CSV writer must escape
        //     "" without breaking the markdown link syntax)
        // The string is built with raw Kotlin escapes so the LF separators
        // are real newlines, not the two-character backslash-n sequence.
        val markdownNotes = """
            |# Recovery codes
            |
            |Use any one of these once:
            |
            |```
            |alpha-bravo-charlie
            |delta-echo-foxtrot
            |```
            |
            || Code   | Used |
            ||--------|------|
            || a1b2c3 | no   |
            || d4e5f6 | yes  |
            |
            |See [the "official" guide](https://example.com/path?q="x") for more.
        """.trimMargin()

        val originalCredential = Credential(
            id = "fixed-id-for-determinism",
            title = "Bank",
            username = "alice",
            password = "p@ss\"word",
            url = "https://bank.example.com/login",
            notes = markdownNotes,
            otpParams = null,
            tags = listOf("finance", "primary"),
            customFields = emptyList(),
            isFavorite = false,
            // Fixed timestamps so two round-trips produce byte-identical files
            // (timestamp columns are emitted verbatim from the Credential).
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_001_000L,
            accessedAt = 1_700_000_002_000L,
            importedAt = null,
        )

        // Round 1: write the original to CSV.
        val csvPath1 = tmp.newFile("round1.csv").absolutePath
        assertSuccess(exporter.export(listOf(originalCredential), ExportFormat.CSV, csvPath1))
        val bytes1 = File(csvPath1).readBytes()
        val hash1 = sha256(bytes1)

        // Re-import the round-1 CSV. The notes column passes through
        // notesNormalize at the importer ingress; everything else returns
        // verbatim. The imported credential is the steady-state shape.
        val parsed1 = parseOrThrow(csvPath1)
        assertEquals("round 1 should re-import cleanly with no failures", 0, parsed1.failed.size)
        assertEquals(1, parsed1.credentials.size)
        val reimported = parsed1.credentials[0]

        // Round 2: re-export the imported credential. If notesNormalize is
        // idempotent and the CSV writer is deterministic for identical input,
        // the bytes on disk must match round 1 verbatim.
        val csvPath2 = tmp.newFile("round2.csv").absolutePath
        assertSuccess(exporter.export(listOf(reimported), ExportFormat.CSV, csvPath2))
        val bytes2 = File(csvPath2).readBytes()
        val hash2 = sha256(bytes2)

        assertEquals(
            "round-2 CSV must be byte-identical to round-1 (idempotent normalisation + deterministic writer). " +
                "round-1 hash=$hash1 round-2 hash=$hash2",
            hash1,
            hash2,
        )

        // Defensive: confirm the bytes are actually equal, not just hashing
        // to the same value (a hash collision would be staggering but the
        // test cost is one extra equality).
        assertEquals(bytes1.toList(), bytes2.toList())

        // Sanity: the re-imported notes must equal the post-normalisation
        // form of the original. Since the original was constructed with bare
        // LF (Kotlin trimMargin produces \n only) and no BOM, normalisation
        // is the identity here - the imported notes equal the original notes.
        // Any drift between Credential.notes round-1 and round-2 would also
        // produce a hash mismatch above, but pinning this explicitly catches
        // a class of bugs where the export side drops/adds whitespace.
        assertEquals(markdownNotes, reimported.notes)
    }

    @Test fun csv_round_trip_collapses_crlf_then_stabilises() = runBlocking {
        // Distinct from the test above: this one starts with a source that
        // carries CRLF line endings inside the notes field. The FIRST round
        // is expected to differ from the raw source (CRLF -> LF). The
        // SECOND round must equal the first - that is the idempotency
        // guarantee under non-trivial normalisation input.
        val crlfNotes = "line1\r\nline2\r\nline3"
        val source = Credential(
            id = "fixed-id-crlf",
            title = "Bank",
            username = "alice",
            password = "p",
            url = "https://example.com",
            notes = crlfNotes,
            otpParams = null,
            tags = emptyList(),
            customFields = emptyList(),
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            accessedAt = null,
            importedAt = null,
        )

        // Round 1 export: CSV writer will quote the cell and emit CR/LF
        // literally (the writer is not part of the normalisation pipeline -
        // it just passes Credential.notes through).
        val csvPath1 = tmp.newFile("crlf-round1.csv").absolutePath
        assertSuccess(exporter.export(listOf(source), ExportFormat.CSV, csvPath1))
        val hash1Raw = sha256(File(csvPath1).readBytes())

        // Re-import. The importer normalises CRLF -> LF on the notes column,
        // so the imported credential's notes carry only LF separators.
        val parsed = parseOrThrow(csvPath1)
        assertEquals(1, parsed.credentials.size)
        val imported = parsed.credentials[0]
        assertEquals("line1\nline2\nline3", imported.notes)

        // Round 2 export: same credential whose notes are now LF-only.
        val csvPath2 = tmp.newFile("crlf-round2.csv").absolutePath
        assertSuccess(exporter.export(listOf(imported), ExportFormat.CSV, csvPath2))
        val hash2 = sha256(File(csvPath2).readBytes())

        // Round 2 has different bytes from round 1 raw (CRLF was folded).
        // This is the EXPECTED drift on the first canonicalisation pass.
        assertNotEquals(
            "round 2 should differ from round 1 raw - normalisation folded CRLF -> LF",
            hash1Raw,
            hash2,
        )

        // Round 3 export: re-import round 2, re-export. Must match round 2.
        val parsed2 = parseOrThrow(csvPath2)
        assertEquals(1, parsed2.credentials.size)
        val csvPath3 = tmp.newFile("crlf-round3.csv").absolutePath
        assertSuccess(exporter.export(listOf(parsed2.credentials[0]), ExportFormat.CSV, csvPath3))
        val hash3 = sha256(File(csvPath3).readBytes())

        assertEquals(
            "after the first canonicalisation pass, every subsequent round-trip must be byte-identical",
            hash2,
            hash3,
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun <T> assertSuccess(result: AppResult<T>) {
        if (result is AppResult.Error) {
            throw AssertionError("export() returned Error: ${result.message}")
        }
    }

    private suspend fun parseOrThrow(path: String): ParsedImport =
        when (val r = importer.parse(path)) {
            is AppResult.Success -> r.data
            is AppResult.Error -> throw AssertionError("parse() returned Error: ${r.message}")
        }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
