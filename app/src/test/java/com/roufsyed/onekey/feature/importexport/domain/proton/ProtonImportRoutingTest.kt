package com.roufsyed.onekey.feature.importexport.domain.proton

import android.app.Application
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.OtpType
import com.roufsyed.onekey.core.domain.repository.FakePasskeyRepository
import com.roufsyed.onekey.core.domain.usecase.ImportVaultUseCase
import com.roufsyed.onekey.core.domain.usecase.InMemoryCredentialRepository
import com.roufsyed.onekey.core.security.CryptoManager
import com.roufsyed.onekey.feature.importexport.domain.ImportFieldOptions
import com.roufsyed.onekey.feature.importexport.domain.VaultImporterImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The file picker hands the importer a path and nothing else, so format
 * detection has to recognise a Proton Authenticator export on its own, keep
 * 1Key's own array backups on the generic path, and turn the password-protected
 * variant into a message the user can act on. This is the GitHub issue 8 case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProtonImportRoutingTest {

    @get:Rule val tmp = TemporaryFolder()

    private val importer = VaultImporterImpl(CryptoManager())

    private val protonExport = """
        {"version":1,"entries":[
          {"id":"a","content":{"uri":"otpauth://totp/GitHub:octo%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&algorithm=SHA1&digits=6&period=30","entry_type":"Totp","name":"GitHub"},"note":"Backup codes in the safe"},
          {"id":"b","content":{"uri":"steam://77776ERUKZ4JVPG66AISEM2EKVTHPCEZ","entry_type":"Steam","name":"Steam"},"note":null}
        ]}
    """.trimIndent()

    @Test fun proton_export_is_detected_and_both_entries_import_with_their_codes() = runBlocking {
        val file = tmp.newFile("proton-authenticator-export.json").also { it.writeText(protonExport) }
        val parsed = (importer.parse(file.path) as AppResult.Success).data
        assertTrue(parsed.failed.isEmpty())
        assertEquals(listOf("GitHub", "Steam"), parsed.credentials.map { it.title })
        assertEquals(listOf(OtpType.TOTP, OtpType.STEAM), parsed.credentials.map { it.otpParams!!.type })

        val repo = InMemoryCredentialRepository(emptyList())
        val result = (ImportVaultUseCase(repo, importer, FakePasskeyRepository()).saveImport(parsed, ImportFieldOptions()) as AppResult.Success).data
        assertEquals(2, result.imported)
        assertEquals(2, repo.active().count { it.otpParams != null })
    }

    @Test fun a_byte_order_mark_does_not_hide_the_format() = runBlocking {
        val file = tmp.newFile("bom.json").also { it.writeText("﻿" + protonExport) }
        assertEquals(2, (importer.parse(file.path) as AppResult.Success).data.credentials.size)
    }

    @Test fun password_protected_export_gets_the_proton_specific_message() = runBlocking {
        val file = tmp.newFile("protected.json").also {
            it.writeText("""{"version":1,"salt":"AAAAAAAAAAAAAAAAAAAAAA==","content":"AAECAwQ="}""")
        }
        val error = importer.parse(file.path) as AppResult.Error
        assertTrue(error.message, error.message!!.contains("password-protected"))
    }

    @Test fun onekey_arrays_and_unknown_objects_keep_their_own_paths() = runBlocking {
        val backup = tmp.newFile("backup.json").also { it.writeText("""[{"id":"c1","title":"GitHub","username":"rouf","password":"pw"}]""") }
        assertEquals("pw", (importer.parse(backup.path) as AppResult.Success).data.credentials.single().password)

        val stranger = tmp.newFile("stranger.json").also { it.writeText("""{"foo":1}""") }
        val error = importer.parse(stranger.path) as AppResult.Error
        assertTrue(error.message, error.message!!.contains("Proton Authenticator"))
    }
}
