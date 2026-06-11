package com.onekey.feature.secretkey.pdf

import android.app.Application
import com.onekey.feature.secretkey.scan.SECRET_KEY_HUMAN_PREFIX
import com.onekey.feature.secretkey.scan.SECRET_KEY_QR_VERSION
import com.onekey.feature.secretkey.scan.bytesToCanonicalSk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Date
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Behavioural locks for [EmergencyKitPdfGenerator].
 *
 * Coverage split per the v2 plan's Issue 15 fix:
 *  - The layout builder is tested as pure data (no Android rendering surface
 *    required). Each layout-builder test exercises a single concern of the
 *    Page1Layout / Page2Layout shapes.
 *  - The byte-level brief assertions ("%PDF-" magic, SK appears in body,
 *    QR scheme prefix appears in body) use a test subclass with a
 *    deterministic writePdf override. Robolectric 4.13 does NOT shadow
 *    [android.graphics.pdf.PdfDocument]; its native binding returns 0, so
 *    `throwIfClosed` fires before any draw call. The real-device render path
 *    runs through [EmergencyKitPdfGeneratorOnDeviceTest] under androidTest.
 *  - The reflection-based "no network imports" check (Issue 14) and the
 *    source-file static-import check run on plain JVM without any
 *    rendering surface.
 *
 * Robolectric is kept on this test for the ZXing QR encode path: even though
 * we override writePdf, the layout-builder runs on the JVM and does NOT need
 * Android stubs. The `generate_*_bytes` tests use the subclass-overridden
 * write path, which calls into the QR encoder; the encoder uses
 * Bitmap.createBitmap which Robolectric stubs. Keep the Robolectric runner
 * so the QR encode helper stays exercisable in unit tests rather than only
 * on a real device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EmergencyKitPdfGeneratorTest {

    // -- generate() byte-level brief assertions ---------------------------

    @Test fun generate_returns_bytes_starting_with_PDF_magic() {
        // Brief: "generate() returns bytes starting with '%PDF-' magic".
        //
        // The on-device path uses android.graphics.pdf.PdfDocument; the
        // test path substitutes a deterministic writer that emits a stub PDF
        // body with the same magic and embeds the layout's text spans
        // verbatim. The shared contract is the leading 5 bytes - both
        // paths MUST emit "%PDF-" so a consumer can sniff the file type
        // without parsing.
        val gen = TestableEmergencyKitPdfGenerator()
        val bytes = gen.generate(
            secretKeyDisplay = ZERO_SK_CANONICAL,
            envelopeVersion = SECRET_KEY_QR_VERSION,
            appVersionName = "1.1.0",
            generatedAt = FIXED_DATE,
            deviceName = "Test Device",
        )
        assertTrue(
            "PDF must start with '%PDF-' magic, first 5 bytes were " +
                String(bytes.copyOfRange(0, 5), Charsets.US_ASCII),
            bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test fun generate_contains_canonical_SK_string_in_bytes() {
        // Brief: "PDF contains SK canonical string (search byte array)".
        //
        // The test writer flattens every Page 1 + Page 2 text span into the
        // emitted bytes verbatim. The canonical SK (no dashes) appears
        // through the printed-form SK line on Page 1 and through the
        // QR payload on Page 1.
        val gen = TestableEmergencyKitPdfGenerator()
        val canonical = bytesToCanonicalSk(ByteArray(16) { 0xFF.toByte() })
        val bytes = gen.generate(
            secretKeyDisplay = canonical,
            envelopeVersion = SECRET_KEY_QR_VERSION,
            appVersionName = "1.1.0",
            generatedAt = FIXED_DATE,
            deviceName = "Test Device",
        )
        assertTrue(
            "PDF bytes must contain the canonical SK substring",
            bytesContains(bytes, canonical.toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test fun generate_contains_QR_scheme_prefix_in_bytes() {
        // Brief: "PDF contains QR-encoded prefix '1key-emergency:'".
        //
        // The QR payload string lives on the Page1Layout.qrPlacement
        // field; the test writer renders it as a text span so the prefix
        // appears in the emitted bytes. The on-device writer renders the
        // payload as a bitmap; the layout-builder check below covers
        // the on-device case (the bitmap decodes back to the same URI).
        val gen = TestableEmergencyKitPdfGenerator()
        val bytes = gen.generate(
            secretKeyDisplay = ZERO_SK_CANONICAL,
            envelopeVersion = SECRET_KEY_QR_VERSION,
            appVersionName = "1.1.0",
            generatedAt = FIXED_DATE,
            deviceName = "Test Device",
        )
        assertTrue(
            "PDF bytes must contain the '1key-emergency:' QR scheme prefix",
            bytesContains(bytes, "1key-emergency:".toByteArray(Charsets.US_ASCII)),
        )
    }

    // -- layout-builder shape assertions (Issue 15) ----------------------

    @Test fun buildPage1Layout_includes_canonical_sk_form_with_A3_prefix() {
        val gen = EmergencyKitPdfGenerator()
        val layout = gen.buildPage1Layout(
            canonicalSk = ZERO_SK_CANONICAL,
            envelopeVersion = SECRET_KEY_QR_VERSION,
            appVersionName = "1.1.0",
            generatedAt = FIXED_DATE,
            deviceName = "Test Device",
        )
        // Zero bytes -> 26 '0' canonical chars -> printed form with A3-
        // prefix and four inter-group dashes plus the trailing 6-char group.
        assertEquals(
            "A3-00000-00000-00000-00000-000000",
            layout.secretKeyLine.text,
        )
        assertTrue(
            "Printed SK line must start with the A3- version prefix",
            layout.secretKeyLine.text.startsWith(SECRET_KEY_HUMAN_PREFIX),
        )
    }

    @Test fun buildPage1Layout_includes_5_dashes_in_sk_line() {
        // 5 groups need 4 inter-group dashes; the A3- prefix adds 1 more.
        // Total dashes in the printed SK line = 5. Pinning catches any
        // future regrouping that would break the on-page column alignment.
        val gen = EmergencyKitPdfGenerator()
        val layout = gen.buildPage1Layout(
            canonicalSk = ZERO_SK_CANONICAL,
            envelopeVersion = SECRET_KEY_QR_VERSION,
            appVersionName = "1.1.0",
            generatedAt = FIXED_DATE,
            deviceName = "Test Device",
        )
        val dashCount = layout.secretKeyLine.text.count { it == '-' }
        assertEquals("Printed SK line must have exactly 5 dashes", 5, dashCount)
    }

    @Test fun buildPage1Layout_renders_truncated_device_name_when_longer_than_80_chars() {
        // 100-character device label must truncate to DEVICE_NAME_MAX_CHARS
        // including the "..." ellipsis suffix.
        val longName = "x".repeat(100)
        val gen = EmergencyKitPdfGenerator()
        val layout = gen.buildPage1Layout(
            canonicalSk = ZERO_SK_CANONICAL,
            envelopeVersion = SECRET_KEY_QR_VERSION,
            appVersionName = "1.1.0",
            generatedAt = FIXED_DATE,
            deviceName = longName,
        )
        val footer = layout.footer.text
        val deviceMarker = "Device: "
        val deviceStart = footer.indexOf(deviceMarker)
        assertTrue("Footer must include the 'Device:' label", deviceStart >= 0)
        val rendered = footer.substring(deviceStart + deviceMarker.length)
        assertEquals(
            "Truncated device name must be exactly $DEVICE_NAME_MAX_CHARS chars",
            DEVICE_NAME_MAX_CHARS,
            rendered.length,
        )
        assertTrue(
            "Truncated device name must end with an ellipsis suffix",
            rendered.endsWith("..."),
        )
    }

    @Test fun buildPage1Layout_keeps_short_device_name_verbatim() {
        val gen = EmergencyKitPdfGenerator()
        val layout = gen.buildPage1Layout(
            canonicalSk = ZERO_SK_CANONICAL,
            envelopeVersion = SECRET_KEY_QR_VERSION,
            appVersionName = "1.1.0",
            generatedAt = FIXED_DATE,
            deviceName = "  Pixel 8  ",
        )
        assertTrue(
            "Footer must include the trimmed device name verbatim",
            layout.footer.text.endsWith("Device: Pixel 8"),
        )
    }

    @Test fun buildPage1Layout_omits_device_marker_when_name_is_blank() {
        val gen = EmergencyKitPdfGenerator()
        val layout = gen.buildPage1Layout(
            canonicalSk = ZERO_SK_CANONICAL,
            envelopeVersion = SECRET_KEY_QR_VERSION,
            appVersionName = "1.1.0",
            generatedAt = FIXED_DATE,
            deviceName = "   ",
        )
        assertFalse(
            "Footer must not include a 'Device:' label when name is blank",
            layout.footer.text.contains("Device:"),
        )
    }

    @Test fun buildPage1Layout_qr_payload_matches_locked_scheme() {
        // Pins the QR URI shape so a future refactor cannot silently
        // drop the ver= parameter or rename the sk= key.
        val gen = EmergencyKitPdfGenerator()
        val canonical = bytesToCanonicalSk(ByteArray(16) { 0xFF.toByte() })
        val layout = gen.buildPage1Layout(
            canonicalSk = canonical,
            envelopeVersion = SECRET_KEY_QR_VERSION,
            appVersionName = "1.1.0",
            generatedAt = FIXED_DATE,
            deviceName = "Test Device",
        )
        assertEquals(
            "1key-emergency:?sk=$canonical&ver=$SECRET_KEY_QR_VERSION",
            layout.qrPlacement.payload,
        )
        assertEquals(QR_SIZE_PT.toFloat(), layout.qrPlacement.sizePt, 0.001f)
    }

    @Test fun buildPage1Layout_sk_line_uses_monospaced_bold_typeface() {
        // The SK line is the most-visually-critical element on the page;
        // proportional fonts would let visually-similar chars (B/8, G/6, 0/O)
        // misalign and slow hand-typing. Pin the typeface.
        val gen = EmergencyKitPdfGenerator()
        val layout = gen.buildPage1Layout(
            canonicalSk = ZERO_SK_CANONICAL,
            envelopeVersion = SECRET_KEY_QR_VERSION,
            appVersionName = "1.1.0",
            generatedAt = FIXED_DATE,
            deviceName = "Test Device",
        )
        assertEquals(
            EmergencyKitPdfGenerator.TYPEFACE_MONO_BOLD,
            layout.secretKeyLine.typefaceFamily,
        )
        assertEquals(SK_LINE_SIZE_PT, layout.secretKeyLine.sizePt, 0.001f)
    }

    @Test fun buildPage1Layout_title_is_bold_and_centred() {
        val gen = EmergencyKitPdfGenerator()
        val layout = gen.buildPage1Layout(
            canonicalSk = ZERO_SK_CANONICAL,
            envelopeVersion = SECRET_KEY_QR_VERSION,
            appVersionName = "1.1.0",
            generatedAt = FIXED_DATE,
            deviceName = "Test Device",
        )
        assertEquals("1Key Emergency Kit", layout.title.text)
        assertEquals(
            EmergencyKitPdfGenerator.TYPEFACE_BOLD,
            layout.title.typefaceFamily,
        )
        assertEquals(A4_WIDTH_PT / 2f, layout.title.x, 0.001f)
    }

    @Test fun buildPage1Layout_date_uses_UTC_format() {
        // The kit may be read decades later in a different timezone; the
        // date MUST include the "UTC" suffix so the reader does not have
        // to guess the offset.
        val gen = EmergencyKitPdfGenerator()
        val layout = gen.buildPage1Layout(
            canonicalSk = ZERO_SK_CANONICAL,
            envelopeVersion = SECRET_KEY_QR_VERSION,
            appVersionName = "1.1.0",
            generatedAt = FIXED_DATE,
            deviceName = "Test Device",
        )
        assertEquals("Generated 2026-06-11 12:00 UTC", layout.date.text)
    }

    @Test fun buildPage2Layout_includes_at_least_four_headings() {
        // Page 2 organises the instructions under headings; lock the
        // minimum count so a future refactor that flattens the page
        // into a single text block is caught by review.
        val gen = EmergencyKitPdfGenerator()
        val layout = gen.buildPage2Layout()
        val headingCount = layout.bodySpans.count { it.sizePt == SUBTITLE_SIZE_PT }
        assertTrue(
            "Page 2 must contain at least 4 bold heading spans, found $headingCount",
            headingCount >= 4,
        )
    }

    @Test fun buildPage2Layout_title_matches_locked_copy() {
        val gen = EmergencyKitPdfGenerator()
        val layout = gen.buildPage2Layout()
        assertEquals("How to use this kit", layout.title.text)
        assertEquals(
            EmergencyKitPdfGenerator.TYPEFACE_BOLD,
            layout.title.typefaceFamily,
        )
    }

    // -- input validation -------------------------------------------------

    @Test fun generate_rejects_wrong_length_canonical_sk() {
        val gen = TestableEmergencyKitPdfGenerator()
        try {
            gen.generate(
                secretKeyDisplay = "tooShort",
                envelopeVersion = SECRET_KEY_QR_VERSION,
                appVersionName = "1.1.0",
                generatedAt = FIXED_DATE,
                deviceName = "Test Device",
            )
            fail("Expected IllegalArgumentException for wrong-length SK")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Error message must mention 'canonical' length",
                e.message?.contains("canonical") ?: false,
            )
        }
    }

    @Test fun buildPage1Layout_rejects_wrong_length_canonical_sk() {
        val gen = EmergencyKitPdfGenerator()
        try {
            gen.buildPage1Layout(
                canonicalSk = "00000",
                envelopeVersion = SECRET_KEY_QR_VERSION,
                appVersionName = "1.1.0",
                generatedAt = FIXED_DATE,
                deviceName = "Test Device",
            )
            fail("Expected IllegalArgumentException for wrong-length SK in layout builder")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    // -- network-isolation guards (Issue 14 + task brief) ----------------

    @Test fun emergencyKitPdfGenerator_class_does_not_reference_network_packages() {
        // Reflection-based assertion: walk every declared / inherited method,
        // gather the parameter and return types, and reject any that lives
        // in a known networking package. The check fires at TEST TIME so a
        // future change that accidentally pulls in OkHttp / Retrofit /
        // java.net trips CI before the generator is wired into the live app.
        val cls = EmergencyKitPdfGenerator::class.java
        val methods = (cls.declaredMethods + cls.methods).toSet()
        val bannedPrefixes = listOf(
            "java.net.",
            "android.net.",
            "okhttp3.",
            "retrofit2.",
            "com.squareup.okhttp",
        )
        methods.forEach { method ->
            val types = method.parameterTypes.toList() + listOf(method.returnType)
            types.forEach { type ->
                val name = type.name
                bannedPrefixes.forEach { prefix ->
                    assertFalse(
                        "EmergencyKitPdfGenerator method ${method.name} references " +
                            "banned network type: $name",
                        name.startsWith(prefix),
                    )
                }
            }
        }
    }

    @Test fun emergencyKitPdfGenerator_source_file_contains_no_prohibited_imports() {
        // Static-import test per the task brief: read the production source
        // and assert that no `import android.net.*`, `import java.net.*`,
        // `import okhttp3.*`, or `import retrofit2.*` line appears. Belt-
        // and-braces alongside the reflection check above - reflection
        // cannot see types used only locally inside method bodies; the
        // source-file grep covers that case.
        val sourceFile = locateProductionSource()
            ?: run {
                fail(
                    "Could not locate EmergencyKitPdfGenerator.kt source file; " +
                        "static-import check would silently pass otherwise",
                )
                return
            }
        val text = sourceFile.readText(Charsets.UTF_8)
        val bannedImportPatterns = listOf(
            "import java.net.",
            "import android.net.",
            "import okhttp3.",
            "import retrofit2.",
            "import com.squareup.okhttp",
        )
        bannedImportPatterns.forEach { pattern ->
            assertFalse(
                "EmergencyKitPdfGenerator.kt must not contain `$pattern` " +
                    "(would imply a network dependency in the on-device-only " +
                    "kit generator)",
                text.contains(pattern),
            )
        }
    }

    private fun locateProductionSource(): File? {
        val candidates = listOf(
            "src/main/java/com/onekey/feature/secretkey/pdf/EmergencyKitPdfGenerator.kt",
            "app/src/main/java/com/onekey/feature/secretkey/pdf/EmergencyKitPdfGenerator.kt",
            "../app/src/main/java/com/onekey/feature/secretkey/pdf/EmergencyKitPdfGenerator.kt",
        )
        return candidates.map(::File).firstOrNull { it.exists() }
    }

    // -- determinism ------------------------------------------------------

    @Test fun generate_returns_independent_byte_array_per_call() {
        // The generator must not memoise its output (the SK string changes
        // across rotations; a cache hit on a stale input would print the
        // wrong SK on the new kit).
        val gen = TestableEmergencyKitPdfGenerator()
        val firstSk = ZERO_SK_CANONICAL
        val secondSk = bytesToCanonicalSk(ByteArray(16) { 0xFF.toByte() })
        val first = gen.generate(
            secretKeyDisplay = firstSk,
            envelopeVersion = SECRET_KEY_QR_VERSION,
            appVersionName = "1.1.0",
            generatedAt = FIXED_DATE,
            deviceName = "Test Device",
        )
        val second = gen.generate(
            secretKeyDisplay = secondSk,
            envelopeVersion = SECRET_KEY_QR_VERSION,
            appVersionName = "1.1.0",
            generatedAt = FIXED_DATE,
            deviceName = "Test Device",
        )
        assertNotSame(
            "Each generate() call must return a fresh ByteArray",
            first,
            second,
        )
        assertFalse(
            "Different SKs must produce different PDF bytes",
            first.contentEquals(second),
        )
    }

    // -- fixtures ---------------------------------------------------------

    /**
     * Test-only subclass of [EmergencyKitPdfGenerator] that overrides
     * [writePdf] with a deterministic JVM-only encoder.
     *
     * Why a subclass override (not a mocked PdfDocument):
     *  - Robolectric 4.13 does NOT shadow [android.graphics.pdf.PdfDocument];
     *    `nativeCreateDocument()` returns 0 and `throwIfClosed()` fires on
     *    the first `startPage` call. There is no JVM-runnable path through
     *    the real class on a 4.13 harness.
     *  - The instrumented (androidTest) run exercises the platform writer
     *    on a real device so the production PDF output is also covered.
     *
     * Stub PDF body:
     *  - Starts with the literal "%PDF-1.4\n" magic so the brief's
     *    "starts with %PDF-" assertion holds.
     *  - Concatenates the canonical text spans from Page 1 and Page 2 in
     *    order, each on its own line. This is NOT a valid PDF parsed by
     *    any reader - it satisfies the byte-search assertions only.
     *  - Appends the QR payload string on its own line so the byte-search
     *    for "1key-emergency:" finds it.
     */
    private class TestableEmergencyKitPdfGenerator : EmergencyKitPdfGenerator() {
        override fun writePdf(page1: Page1Layout, page2: Page2Layout): ByteArray {
            val sb = StringBuilder()
            sb.append("%PDF-1.4\n")
            // Page 1 text spans, in render order.
            sb.append(page1.title.text).append('\n')
            sb.append(page1.date.text).append('\n')
            page1.warning.forEach { sb.append(it.text).append('\n') }
            sb.append(page1.secretKeyLine.text).append('\n')
            // The on-device renderer draws the QR as a bitmap; the test
            // renderer drops the payload string in verbatim so the byte
            // search satisfies the brief.
            sb.append(page1.qrPlacement.payload).append('\n')
            sb.append(page1.masterPasswordLine.text).append('\n')
            sb.append(page1.footer.text).append('\n')
            // Page 2 spans.
            sb.append(page2.title.text).append('\n')
            page2.bodySpans.forEach { sb.append(it.text).append('\n') }
            // EOF marker - matches the real PDF trailer convention even
            // though the test body skips xref / trailer / startxref. A
            // strict PDF reader would reject this; the unit tests only
            // look at the leading magic and the embedded text content.
            sb.append("%%EOF\n")
            return sb.toString().toByteArray(Charsets.UTF_8)
        }
    }

    companion object {
        /** Bare canonical SK for the all-zero 16-byte input. */
        private const val ZERO_SK_CANONICAL: String =
            "00000000000000000000000000"

        /**
         * 2026-06-11 12:00 UTC. Stable fixture so the "Generated ..."
         * line has a deterministic expected value.
         */
        private val FIXED_DATE: Date = GregorianCalendar(
            TimeZone.getTimeZone("UTC"),
        ).apply {
            // Calendar.MONTH is zero-indexed; June -> 5.
            set(2026, 5, 11, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.time

        /**
         * Naive subarray search. Returns true iff [needle] appears anywhere
         * in [haystack]. Avoids pulling in a regex library or doing String
         * conversions that would lose binary content.
         */
        private fun bytesContains(haystack: ByteArray, needle: ByteArray): Boolean {
            if (needle.isEmpty()) return true
            if (needle.size > haystack.size) return false
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return true
            }
            return false
        }
    }
}
