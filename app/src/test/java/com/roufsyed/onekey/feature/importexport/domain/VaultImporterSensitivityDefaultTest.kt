package com.roufsyed.onekey.feature.importexport.domain

import com.roufsyed.onekey.core.domain.model.AppResult
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

/**
 * Import sensitivity hardening: a custom field that arrives WITHOUT a sensitivity
 * flag takes the FieldLabelCatalog default for its label (Card Number, CVV,
 * Account Number, API Token, ... are sensitive; Expiry, Branch, free-form labels
 * are not), on all three ingress paths:
 *
 *   1. JSON `custom_fields` entries with no `sensitive` key (foreign tools),
 *   2. JSON unknown top-level scalar keys that become custom fields,
 *   3. CSV extra columns (CSV has no sensitivity column at all).
 *
 * An explicit boolean flag is the source's word and is always kept, so a 1Key
 * backup round-trips byte for byte, including a Card Number the user chose to
 * leave visible. Existing rows in the vault are never rewritten by this rule.
 */
class VaultImporterSensitivityDefaultTest {

    @get:Rule val tmp = TemporaryFolder()

    private val crypto = CryptoManager()
    private val exporter = VaultExporterImpl(crypto, SecretKeyHolder())
    private val importer = VaultImporterImpl(crypto)

    // ── JSON custom_fields ───────────────────────────────────────────────

    @Test fun json_custom_field_without_flag_takes_the_catalogue_default() = runBlocking {
        val path = writeJson(
            """[{"title":"Visa","type":"CREDIT_CARD","custom_fields":[
                 {"key":"Card Number","value":"4111 1111 1111 1111"},
                 {"key":"Expiry","value":"12/27"},
                 {"key":"cvc","value":"123"},
                 {"key":"Security Question","value":"first pet"}
               ]}]""",
        )
        val fields = parseOrThrow(path).credentials.single().customFields.associate { it.key to it.isSensitive }

        assertEquals(
            mapOf("Card Number" to true, "Expiry" to false, "cvc" to true, "Security Question" to false),
            fields,
        )
    }

    @Test fun json_custom_field_explicit_flag_is_kept_either_way() = runBlocking {
        val path = writeJson(
            """[{"title":"Visa","type":"CREDIT_CARD","custom_fields":[
                 {"key":"CVV","value":"123","sensitive":false},
                 {"key":"Branch","value":"Main Street","sensitive":true}
               ]}]""",
        )
        val fields = parseOrThrow(path).credentials.single().customFields.associate { it.key to it.isSensitive }

        assertEquals(mapOf("CVV" to false, "Branch" to true), fields)
    }

    @Test fun json_custom_field_non_boolean_flag_counts_as_missing() = runBlocking {
        // "no" is not a flag; the entry is treated as unflagged and the label decides.
        val path = writeJson(
            """[{"title":"Visa","type":"CREDIT_CARD","custom_fields":[
                 {"key":"Card Number","value":"4111 1111 1111 1111","sensitive":"no"}
               ]}]""",
        )
        assertTrue(parseOrThrow(path).credentials.single().customFields.single().isSensitive)
    }

    // ── JSON unknown top-level keys ──────────────────────────────────────

    @Test fun json_unknown_scalar_key_takes_the_catalogue_default_after_prettifying() = runBlocking {
        val path = writeJson(
            """[{"title":"Bank","type":"BANK_ACCOUNT",
                 "account_number":"1234567890","phone_number":"555 0100","api_token":"abc"}]""",
        )
        val fields = parseOrThrow(path).credentials.single().customFields.associate { it.key to it.isSensitive }

        assertEquals(
            mapOf("Account Number" to true, "Phone Number" to false, "Api Token" to true),
            fields,
        )
    }

    // ── CSV extra columns ────────────────────────────────────────────────

    @Test fun csv_extra_column_takes_the_catalogue_default() = runBlocking {
        val path = writeCsv(
            header = "title,username,CVV,branch",
            row = listOf("Card", "alice", "123", "Main Street"),
        )
        val fields = parseOrThrow(path).credentials.single().customFields.associate { it.key to it.isSensitive }

        assertEquals(mapOf("CVV" to true, "Branch" to false), fields)
    }

    // ── round trip ───────────────────────────────────────────────────────

    @Test fun json_round_trip_keeps_an_explicit_false_on_a_sensitive_label() = runBlocking {
        val original = credential(
            listOf(
                CustomField("Card Number", "4111 1111 1111 1111", isSensitive = false),
                CustomField("CVV", "123", isSensitive = true),
                CustomField("Branch", "Main Street", isSensitive = true),
            ),
        )
        val path = tmp.newFile("export-${System.nanoTime()}.json").absolutePath
        assertSuccess(exporter.export(listOf(original), ExportFormat.JSON, path))

        val imported = parseOrThrow(path).credentials.single()

        assertEquals(original.customFields, imported.customFields)
        assertFalse(imported.customFields.first { it.key == "Card Number" }.isSensitive)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun writeJson(content: String): String {
        val file = tmp.newFile("import-${System.nanoTime()}.json")
        file.writeText(content)
        return file.absolutePath
    }

    private fun writeCsv(header: String, row: List<String>): String {
        val file = tmp.newFile("import-${System.nanoTime()}.csv")
        file.writeText(header + "\n" + row.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } + "\n")
        return file.absolutePath
    }

    private suspend fun parseOrThrow(path: String): ParsedImport =
        when (val r = importer.parse(path)) {
            is AppResult.Success -> r.data
            is AppResult.Error -> throw AssertionError("parse failed: ${r.message}", r.exception)
        }

    private fun assertSuccess(result: AppResult<Unit>) {
        if (result is AppResult.Error) throw AssertionError("export failed: ${result.message}", result.exception)
    }

    private fun credential(customFields: List<CustomField>) = Credential(
        id = "fixed-id", title = "Visa", username = "", password = "", url = "", notes = "",
        otpParams = null, tags = emptyList(), customFields = customFields,
        createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_001_000L,
        type = CredentialType.CREDIT_CARD,
    )
}
