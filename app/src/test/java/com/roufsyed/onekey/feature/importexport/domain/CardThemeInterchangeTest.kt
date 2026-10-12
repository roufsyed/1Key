package com.roufsyed.onekey.feature.importexport.domain

import com.google.gson.Gson
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.CardTheme
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.CustomField
import com.roufsyed.onekey.core.domain.usecase.ExportFormat
import com.roufsyed.onekey.core.security.CryptoManager
import com.roufsyed.onekey.core.security.SecretKeyHolder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Round-trip contract for the card theme (DB v16) through the JSON backup format,
 * the sync serialisation (same DTO) and CSV:
 *
 *  - JSON export writes `card_theme` (enum name) ONLY for non-default themes, so
 *    every pre-existing backup stays byte-identical and older app versions, which
 *    turn unknown keys into a visible custom field, are affected only by cards
 *    that were actually decorated.
 *  - JSON import reads the key; a missing key, an unknown name (newer palette) or
 *    garbage resolve to the default and never produce a "Card Theme" custom field.
 *  - CSV carries neither card fields nor the theme, by design.
 */
class CardThemeInterchangeTest {

    @get:Rule val tmp = TemporaryFolder()

    private val crypto = CryptoManager()
    private val exporter = VaultExporterImpl(crypto, SecretKeyHolder())
    private val importer = VaultImporterImpl(crypto)
    private val gson = Gson()

    // ── export ───────────────────────────────────────────────────────────

    @Test fun json_export_omits_card_theme_for_the_default() = runBlocking {
        val path = exportJson(card(theme = CardTheme.GRAPHITE))
        val dto = singleDto(path)
        assertFalse("default theme must not be written", dto.containsKey("card_theme"))
    }

    @Test fun json_export_writes_enum_name_for_non_default_theme() = runBlocking {
        val path = exportJson(card(theme = CardTheme.MIDNIGHT))
        assertEquals("MIDNIGHT", singleDto(path)["card_theme"])
    }

    @Test fun sync_serialisation_uses_the_same_dto() {
        val bytes = exporter.serializeForSync(listOf(card(theme = CardTheme.CORAL)), ExportFormat.JSON)
        val dto = parseDtos(bytes.toString(Charsets.UTF_8)).single()
        assertEquals("CORAL", dto["card_theme"])

        val defaultBytes = exporter.serializeForSync(listOf(card(theme = CardTheme.GRAPHITE)), ExportFormat.JSON)
        assertFalse(parseDtos(defaultBytes.toString(Charsets.UTF_8)).single().containsKey("card_theme"))
    }

    // ── import ───────────────────────────────────────────────────────────

    @Test fun json_import_reads_card_theme_and_creates_no_custom_field() = runBlocking {
        val path = writeJson("""[{"title":"Visa","type":"CREDIT_CARD","card_theme":"MIDNIGHT"}]""")
        val imported = parseOrThrow(path).credentials.single()
        assertEquals(CardTheme.MIDNIGHT, imported.cardTheme)
        assertEquals(CredentialType.CREDIT_CARD, imported.type)
        assertTrue("known key must not become a custom field", imported.customFields.isEmpty())
    }

    @Test fun json_import_without_key_defaults_to_graphite() = runBlocking {
        val path = writeJson("""[{"title":"Visa","type":"CREDIT_CARD"}]""")
        assertEquals(CardTheme.GRAPHITE, parseOrThrow(path).credentials.single().cardTheme)
    }

    @Test fun json_import_unknown_theme_name_defaults_and_creates_no_custom_field() = runBlocking {
        // A backup written by a newer app version with a palette this build does
        // not know. Must degrade to the default, never fail the row, never leak
        // the name into the custom fields.
        val path = writeJson("""[{"title":"Visa","type":"CREDIT_CARD","card_theme":"ULTRAVIOLET"}]""")
        val parsed = parseOrThrow(path)
        assertEquals(0, parsed.failed.size)
        val imported = parsed.credentials.single()
        assertEquals(CardTheme.GRAPHITE, imported.cardTheme)
        assertTrue(imported.customFields.none { it.key.equals("Card Theme", ignoreCase = true) })
    }

    @Test fun json_import_treats_non_string_theme_as_default() = runBlocking {
        val path = writeJson("""[{"title":"Visa","card_theme":7}]""")
        val parsed = parseOrThrow(path)
        assertEquals(0, parsed.failed.size)
        assertEquals(CardTheme.GRAPHITE, parsed.credentials.single().cardTheme)
        assertTrue(parsed.credentials.single().customFields.isEmpty())
    }

    // ── round trip ───────────────────────────────────────────────────────

    @Test fun json_round_trip_preserves_theme_type_and_card_fields() = runBlocking {
        val original = card(
            theme = CardTheme.CORAL,
            customFields = listOf(
                CustomField("Cardholder", "Alice Example", isSensitive = false),
                CustomField("Card Number", "4111 1111 1111 1111", isSensitive = true),
                CustomField("Expiry", "12/27", isSensitive = false),
                CustomField("CVV", "123", isSensitive = true),
            ),
        )
        val path = exportJson(original)
        val imported = parseOrThrow(path).credentials.single()

        assertEquals(CardTheme.CORAL, imported.cardTheme)
        assertEquals(CredentialType.CREDIT_CARD, imported.type)
        assertEquals(original.customFields, imported.customFields)
        assertEquals(original.title, imported.title)
    }

    // ── CSV ──────────────────────────────────────────────────────────────

    @Test fun csv_export_has_no_card_theme_column_and_import_defaults() = runBlocking {
        val path = tmp.newFile("cards.csv").absolutePath
        assertSuccess(exporter.export(listOf(card(theme = CardTheme.MIDNIGHT)), ExportFormat.CSV, path))
        val header = File(path).readLines().first()
        assertFalse("CSV carries no theme by design", header.contains("card_theme"))

        assertEquals(CardTheme.GRAPHITE, parseOrThrow(path).credentials.single().cardTheme)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private suspend fun exportJson(vararg credentials: Credential): String {
        val path = tmp.newFile("export-${System.nanoTime()}.json").absolutePath
        assertSuccess(exporter.export(credentials.toList(), ExportFormat.JSON, path))
        return path
    }

    private fun writeJson(content: String): String {
        val file = tmp.newFile("import-${System.nanoTime()}.json")
        file.writeText(content)
        return file.absolutePath
    }

    private fun singleDto(path: String): Map<String, Any?> = parseDtos(File(path).readText()).single()

    @Suppress("UNCHECKED_CAST")
    private fun parseDtos(json: String): List<Map<String, Any?>> =
        gson.fromJson(json, List::class.java) as List<Map<String, Any?>>

    private suspend fun parseOrThrow(path: String): ParsedImport =
        when (val r = importer.parse(path)) {
            is AppResult.Success -> r.data
            is AppResult.Error -> throw AssertionError("parse failed: ${r.message}", r.exception)
        }

    private fun assertSuccess(result: AppResult<Unit>) {
        if (result is AppResult.Error) throw AssertionError("export failed: ${result.message}", result.exception)
    }

    private fun card(
        theme: CardTheme,
        customFields: List<CustomField> = emptyList(),
    ) = Credential(
        id = "fixed-id", title = "Visa", username = "", password = "", url = "", notes = "",
        otpParams = null, tags = emptyList(), customFields = customFields,
        createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_001_000L,
        type = CredentialType.CREDIT_CARD, cardTheme = theme,
    )
}
