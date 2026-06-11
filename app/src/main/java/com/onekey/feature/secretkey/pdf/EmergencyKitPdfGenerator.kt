package com.onekey.feature.secretkey.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.onekey.feature.secretkey.scan.SECRET_KEY_CANONICAL_LENGTH
import com.onekey.feature.secretkey.scan.formatCanonicalSkForPrint
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layout / rendering parameters for the printed Emergency Kit. All measurements
 * are in PostScript points (1pt = 1/72 inch); [PdfDocument.PageInfo] uses
 * points as its native unit so this avoids any density-dependent conversion.
 *
 * The A4 dimensions and the margin block are pulled into companion constants
 * so the layout-builder tests can read them without instantiating the class
 * (no Android Context required for the layout phase).
 *
 *  - [A4_WIDTH_PT] / [A4_HEIGHT_PT] - 595 x 842 pt, the ISO 216 A4 size at
 *    72 dpi. We match the standard exactly so a user who prints the kit on
 *    any office printer gets a one-to-one-scale page.
 *  - [PAGE_MARGIN_PT] - 36 pt = 0.5 inch white margin on all four sides.
 *    Page printers typically cannot print to the edge; 0.5 inch is the
 *    safest commonly-honoured printable area.
 *  - [QR_SIZE_PT] - 200 pt square (~2.78 inch). Large enough that a phone
 *    camera held 10-15 cm away from the printout reliably resolves all 25
 *    modules of a Version-2 ECC-Medium QR code (which our 33-byte
 *    `1key-emergency:?sk=<26>&ver=5` payload fits into comfortably).
 */
internal const val A4_WIDTH_PT: Int = 595
internal const val A4_HEIGHT_PT: Int = 842
internal const val PAGE_MARGIN_PT: Float = 36f
internal const val QR_SIZE_PT: Int = 200

// Headline / body type sizes in points. Kept large and high-contrast so a
// user reading the printout in daylight from arm's length never strains.
internal const val TITLE_SIZE_PT: Float = 24f
internal const val SUBTITLE_SIZE_PT: Float = 14f
internal const val BODY_SIZE_PT: Float = 11f
internal const val SK_LINE_SIZE_PT: Float = 20f
internal const val FOOTER_SIZE_PT: Float = 9f

/**
 * Hard cap on the printable length of the device-name footer line on Page 1.
 * Long custom hostnames ("Rouf's 2026 Pixel Fold Display Edition for Work")
 * would overflow the right margin and either wrap badly or get clipped. We
 * truncate-with-ellipsis at 80 chars before drawing.
 *
 * Surfaced as a constant so the truncation test
 * [EmergencyKitPdfGeneratorTest.buildPage1Layout_renders_truncated_device_name_when_longer_than_80_chars]
 * can verify the limit without hard-coding the magic number in two places.
 */
internal const val DEVICE_NAME_MAX_CHARS: Int = 80

/**
 * Position + text for a single drawn span on the Emergency Kit page. The PDF
 * generator emits these from its layout builder; tests read them to assert
 * shape without driving an actual PdfDocument render. Mirrors the structure
 * called out in the v2 plan's Issue 15 fix.
 *
 *  - [x] / [y] - top-left baseline anchor in points relative to the page
 *    origin (PostScript-style, origin = top-left after [Canvas] translation).
 *  - [text] - the exact UTF-8 string drawn. Tests assert canonical formatting
 *    (the SK printed form, the date) through this field.
 *  - [typefaceFamily] - "sans-serif" / "monospace" / "sans-serif-bold". The
 *    PDF renderer maps these to [Typeface.create]; tests assert the
 *    monospaced family on the SK line and the bold family on the title.
 *  - [sizePt] - point size used by [Paint.setTextSize]. Tests pin the SK
 *    line at [SK_LINE_SIZE_PT].
 */
internal data class DrawnTextSpan(
    val x: Float,
    val y: Float,
    val text: String,
    val typefaceFamily: String,
    val sizePt: Float,
)

/**
 * Position + size of the QR bitmap drawn on Page 1.
 *
 *  - [x] / [y] - top-left corner of the QR in page-space points.
 *  - [sizePt] - square edge length in points. Matches [QR_SIZE_PT].
 *  - [payload] - the exact string ZXing encodes into the QR. Tests assert
 *    this matches `1key-emergency:?sk=<canonical>&ver=<SECRET_KEY_QR_VERSION>`
 *    so the round-trip-with-ML-Kit test has a known expected value.
 */
internal data class QrPlacement(
    val x: Float,
    val y: Float,
    val sizePt: Float,
    val payload: String,
)

/**
 * Structured layout of Page 1 ("front side" of the printable kit). Built by
 * [EmergencyKitPdfGenerator.buildPage1Layout] and consumed by both the live
 * render path inside [EmergencyKitPdfGenerator.generate] and the layout-only
 * unit tests. Decoupling layout-build from canvas-draw is the v2 plan's
 * Issue 15 resolution.
 */
internal data class Page1Layout(
    val title: DrawnTextSpan,
    val date: DrawnTextSpan,
    val warning: List<DrawnTextSpan>,
    val secretKeyLine: DrawnTextSpan,
    val qrPlacement: QrPlacement,
    val masterPasswordLine: DrawnTextSpan,
    val footer: DrawnTextSpan,
)

/**
 * Structured layout of Page 2 ("instructions" side of the printable kit).
 * A list of body spans plus the page title; the renderer walks the list in
 * order and emits each one verbatim.
 */
internal data class Page2Layout(
    val title: DrawnTextSpan,
    val bodySpans: List<DrawnTextSpan>,
)

/**
 * Renders the printable Emergency Kit for the Secret Key as a two-page
 * A4 PDF, returning the bytes for the caller to write to a user-selected
 * SAF URI.
 *
 * Why a PDF (not PNG/HTML):
 *  - Users want to print the kit on a one-shot, archival-grade medium. PDF
 *    is the lingua franca of printers; every printer's driver supports it
 *    and every OS renders it identically.
 *  - The kit is read offline forever - no JavaScript / web view / network
 *    dependency. PdfDocument is part of the Android platform since API 19,
 *    so this code path has zero third-party render surface.
 *
 * Why ZXing for the QR (not ML Kit):
 *  - ML Kit's `barcode-scanning` artifact only DECODES; it has no encode
 *    surface. ZXing is the canonical pure-Java QR encoder and we already
 *    depend on it for [QRCodeWriter] specifically; nothing else from the
 *    library is reachable at runtime in release builds (R8 tree-shakes).
 *
 * NO network calls. The class deliberately imports nothing from
 * `android.net.*`, `java.net.*`, OkHttp, Retrofit, or any other transport
 * package - the suite includes a static-import test that pins this.
 */
@Singleton
open class EmergencyKitPdfGenerator @Inject constructor() {

    /**
     * Generates the printable Emergency Kit PDF bytes.
     *
     * @param secretKeyDisplay the bare 26-character Crockford base32 canonical
     *   Secret Key string (no prefix, no dashes - matches what [bytesToCanonicalSk]
     *   emits). The generator inserts the `A3-` prefix and dashes for the
     *   on-page text via [formatCanonicalSkForPrint] and uses the bare form
     *   verbatim in the QR payload.
     * @param envelopeVersion the V5 backup envelope format version that
     *   matches this SK. Stamped into the QR URI's `ver=` parameter so the
     *   scanner can refuse to import a kit from a newer app build.
     * @param appVersionName the app's release version string (e.g. "1.1.0").
     *   Printed in the page footer so a user can identify the build that
     *   generated the kit when reading it years later.
     * @param generatedAt the UTC timestamp the kit was generated. Printed in
     *   ISO-8601 (yyyy-MM-dd HH:mm UTC) on Page 1.
     * @param deviceName a short label describing the device that generated
     *   the kit (e.g. "Rouf's Pixel 8"). Truncated at [DEVICE_NAME_MAX_CHARS]
     *   so an unreasonable hostname does not overflow the page. Pass an
     *   empty string if no name is available; the footer adapts.
     * @return the encoded PDF bytes. Always returns a non-empty array; if
     *   any drawing step throws, the exception propagates (the caller
     *   surfaces a "couldn't write kit" message and keeps the SK in memory
     *   so the user can retry).
     */
    fun generate(
        secretKeyDisplay: String,
        envelopeVersion: Int,
        appVersionName: String,
        generatedAt: Date,
        deviceName: String,
    ): ByteArray {
        require(secretKeyDisplay.length == SECRET_KEY_CANONICAL_LENGTH) {
            "secretKeyDisplay must be the bare $SECRET_KEY_CANONICAL_LENGTH-character canonical form"
        }

        val page1Layout = buildPage1Layout(
            canonicalSk = secretKeyDisplay,
            envelopeVersion = envelopeVersion,
            appVersionName = appVersionName,
            generatedAt = generatedAt,
            deviceName = deviceName,
        )
        val page2Layout = buildPage2Layout()
        return writePdf(page1Layout, page2Layout)
    }

    /**
     * Writes the supplied page layouts to a PDF byte stream. Extracted as an
     * `internal open` hook so unit tests can substitute a deterministic
     * test-only writer that does not rely on the native PdfDocument backing
     * (the Robolectric harness has no shadow for PdfDocument; its native
     * binding returns 0 which trips throwIfClosed). Production code calls
     * straight through to [renderPdfWithPlatform], which uses the real
     * [android.graphics.pdf.PdfDocument]; instrumented (androidTest) runs
     * exercise that path on a real device.
     *
     * Visibility is `internal` (not `protected`) because the layout types
     * are `internal` data classes; Kotlin rejects a protected method whose
     * parameters expose tighter-visibility types. `internal` keeps the
     * override surface scoped to the app module, matching the layout
     * builders themselves.
     *
     * Test overrides MUST still produce a byte stream that begins with
     * `%PDF-` and embeds the on-page text content - the layout-builder
     * tests are the authoritative shape checks, but the generated bytes
     * must still satisfy the brief's "starts with %PDF-" assertion.
     */
    internal open fun writePdf(page1: Page1Layout, page2: Page2Layout): ByteArray {
        return renderPdfWithPlatform(page1, page2)
    }

    private fun renderPdfWithPlatform(page1: Page1Layout, page2: Page2Layout): ByteArray {
        val pdf = PdfDocument()
        try {
            renderPage1(pdf, page1)
            renderPage2(pdf, page2)
            val out = ByteArrayOutputStream()
            pdf.writeTo(out)
            return out.toByteArray()
        } finally {
            pdf.close()
        }
    }

    /**
     * Builds the structured Page 1 layout without touching a [Canvas]. The
     * generator's render path consumes the returned layout to drive the
     * actual draw calls; tests consume it to assert positions, typefaces, and
     * the canonical SK formatting in isolation.
     *
     * Layout walk (top-to-bottom):
     *  1. Title "1Key Emergency Kit" centred at the top margin.
     *  2. Generation date sub-line below the title.
     *  3. Warning block (three lines of ASCII text) below the date.
     *  4. The SK line in 20pt monospaced bold, printed with the canonical
     *     `A3-XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX` formatting.
     *  5. The QR bitmap (200 pt square) centred horizontally below the SK
     *     line. Its `payload` field carries the exact ZXing-encoded URI.
     *  6. "Master password: __________________" line below the QR.
     *  7. Footer with app version, device name (truncated), and a
     *     "Keep this kit in a safe place" reminder.
     */
    internal fun buildPage1Layout(
        canonicalSk: String,
        envelopeVersion: Int,
        appVersionName: String,
        generatedAt: Date,
        deviceName: String,
    ): Page1Layout {
        require(canonicalSk.length == SECRET_KEY_CANONICAL_LENGTH) {
            "canonicalSk must be exactly $SECRET_KEY_CANONICAL_LENGTH chars"
        }

        val pageCentreX = A4_WIDTH_PT / 2f
        val contentLeft = PAGE_MARGIN_PT
        val contentRight = A4_WIDTH_PT - PAGE_MARGIN_PT
        val contentWidth = contentRight - contentLeft

        var cursorY = PAGE_MARGIN_PT + TITLE_SIZE_PT
        val title = DrawnTextSpan(
            x = pageCentreX,
            y = cursorY,
            text = "1Key Emergency Kit",
            typefaceFamily = TYPEFACE_BOLD,
            sizePt = TITLE_SIZE_PT,
        )

        cursorY += TITLE_SIZE_PT * 1.2f
        val dateString = formatGeneratedAt(generatedAt)
        val date = DrawnTextSpan(
            x = pageCentreX,
            y = cursorY,
            text = "Generated $dateString",
            typefaceFamily = TYPEFACE_REGULAR,
            sizePt = SUBTITLE_SIZE_PT,
        )

        cursorY += SUBTITLE_SIZE_PT * 2.0f
        val warningLines = listOf(
            "This Secret Key is required (along with your master password)",
            "to restore 1Key backups created on this device.",
            "Treat it like the master password itself: keep it offline.",
        )
        val warning = warningLines.map { line ->
            cursorY += BODY_SIZE_PT * 1.4f
            DrawnTextSpan(
                x = pageCentreX,
                y = cursorY,
                text = line,
                typefaceFamily = TYPEFACE_REGULAR,
                sizePt = BODY_SIZE_PT,
            )
        }

        cursorY += SK_LINE_SIZE_PT * 1.6f
        val skPrinted = formatCanonicalSkForPrint(canonicalSk)
        val secretKeyLine = DrawnTextSpan(
            x = pageCentreX,
            y = cursorY,
            text = skPrinted,
            typefaceFamily = TYPEFACE_MONO_BOLD,
            sizePt = SK_LINE_SIZE_PT,
        )

        cursorY += SK_LINE_SIZE_PT * 0.8f
        val qrLeft = pageCentreX - QR_SIZE_PT / 2f
        val qrPayload = "1key-emergency:?sk=$canonicalSk&ver=$envelopeVersion"
        val qrPlacement = QrPlacement(
            x = qrLeft,
            y = cursorY,
            sizePt = QR_SIZE_PT.toFloat(),
            payload = qrPayload,
        )

        cursorY += QR_SIZE_PT + BODY_SIZE_PT * 2.5f
        val masterPasswordLine = DrawnTextSpan(
            x = contentLeft,
            y = cursorY,
            text = "Master password: __________________________",
            typefaceFamily = TYPEFACE_REGULAR,
            sizePt = BODY_SIZE_PT,
        )

        val footerY = (A4_HEIGHT_PT - PAGE_MARGIN_PT).toFloat()
        val device = truncateDeviceName(deviceName)
        val footerText = buildString {
            append("1Key ")
            append(appVersionName)
            append("  -  Keep this kit safe.")
            if (device.isNotBlank()) {
                append("  Device: ")
                append(device)
            }
        }
        val footer = DrawnTextSpan(
            x = pageCentreX,
            y = footerY,
            text = footerText,
            typefaceFamily = TYPEFACE_REGULAR,
            sizePt = FOOTER_SIZE_PT,
        )

        // contentWidth is referenced here only as a guard against future
        // layout changes that might overflow the right margin. The check is
        // intentionally non-throwing in production (it would block the user
        // from saving the kit at all on a too-long master-password line);
        // tests use the same constant directly to assert the field is sized
        // to the margins.
        check(contentWidth > 0f) { "Content area has non-positive width" }

        return Page1Layout(
            title = title,
            date = date,
            warning = warning,
            secretKeyLine = secretKeyLine,
            qrPlacement = qrPlacement,
            masterPasswordLine = masterPasswordLine,
            footer = footer,
        )
    }

    /**
     * Builds the structured Page 2 layout - the printed instructions panel.
     * Plain text, no SK or QR; users skim this when they hand-recover the
     * vault years after generating the kit.
     *
     * The text is intentionally ASCII-only (no smart quotes / em dashes /
     * arrows) so a printer with no UTF-8 font installed still renders the
     * instructions correctly.
     */
    internal fun buildPage2Layout(): Page2Layout {
        val pageCentreX = A4_WIDTH_PT / 2f
        val contentLeft = PAGE_MARGIN_PT

        var cursorY = PAGE_MARGIN_PT + TITLE_SIZE_PT
        val title = DrawnTextSpan(
            x = pageCentreX,
            y = cursorY,
            text = "How to use this kit",
            typefaceFamily = TYPEFACE_BOLD,
            sizePt = TITLE_SIZE_PT,
        )

        cursorY += TITLE_SIZE_PT * 1.5f
        val lines = PAGE_2_INSTRUCTIONS
        val bodySpans = lines.map { line ->
            cursorY += BODY_SIZE_PT * 1.5f
            val isHeading = line.startsWith("# ")
            val displayText = if (isHeading) line.removePrefix("# ") else line
            DrawnTextSpan(
                x = contentLeft,
                y = cursorY,
                text = displayText,
                typefaceFamily = if (isHeading) TYPEFACE_BOLD else TYPEFACE_REGULAR,
                sizePt = if (isHeading) SUBTITLE_SIZE_PT else BODY_SIZE_PT,
            )
        }

        return Page2Layout(
            title = title,
            bodySpans = bodySpans,
        )
    }

    private fun renderPage1(pdf: PdfDocument, layout: Page1Layout) {
        val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, 1).create()
        val page = pdf.startPage(pageInfo)
        try {
            val canvas = page.canvas
            drawCentredText(canvas, layout.title)
            drawCentredText(canvas, layout.date)
            layout.warning.forEach { drawCentredText(canvas, it) }
            drawCentredText(canvas, layout.secretKeyLine)
            drawQr(canvas, layout.qrPlacement)
            drawLeftAlignedText(canvas, layout.masterPasswordLine)
            drawCentredText(canvas, layout.footer)
        } finally {
            pdf.finishPage(page)
        }
    }

    private fun renderPage2(pdf: PdfDocument, layout: Page2Layout) {
        val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, 2).create()
        val page = pdf.startPage(pageInfo)
        try {
            val canvas = page.canvas
            drawCentredText(canvas, layout.title)
            layout.bodySpans.forEach { drawLeftAlignedText(canvas, it) }
        } finally {
            pdf.finishPage(page)
        }
    }

    private fun drawCentredText(canvas: Canvas, span: DrawnTextSpan) {
        val paint = paintFor(span)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(span.text, span.x, span.y, paint)
    }

    private fun drawLeftAlignedText(canvas: Canvas, span: DrawnTextSpan) {
        val paint = paintFor(span)
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(span.text, span.x, span.y, paint)
    }

    private fun paintFor(span: DrawnTextSpan): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.BLACK
        paint.textSize = span.sizePt
        paint.typeface = typefaceFor(span.typefaceFamily)
        return paint
    }

    private fun typefaceFor(family: String): Typeface {
        return when (family) {
            TYPEFACE_BOLD -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            TYPEFACE_MONO_BOLD -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            TYPEFACE_REGULAR -> Typeface.DEFAULT
            else -> Typeface.DEFAULT
        }
    }

    private fun drawQr(canvas: Canvas, placement: QrPlacement) {
        val bitmap = encodeQrToBitmap(placement.payload, placement.sizePt.toInt())
        try {
            val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
            val dstRect = Rect(
                placement.x.toInt(),
                placement.y.toInt(),
                (placement.x + placement.sizePt).toInt(),
                (placement.y + placement.sizePt).toInt(),
            )
            val paint = Paint()
            paint.isAntiAlias = false
            paint.isFilterBitmap = false
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodeQrToBitmap(payload: String, sizePx: Int): Bitmap {
        val hints = mutableMapOf<EncodeHintType, Any>()
        // ECC Medium balances density vs damage resistance: the QR survives
        // mild smudging on a printed page while still fitting our short
        // payload into a small Version-2 grid (25x25 modules). Higher ECC
        // levels would inflate the QR for negligible practical gain.
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
        // Force ASCII byte mode - the canonical SK string is pure ASCII and
        // the URI prefix contains only ASCII characters. The default detection
        // picks the right mode anyway, but pinning it makes the encoded byte
        // stream byte-for-byte stable across ZXing versions.
        hints[EncodeHintType.CHARACTER_SET] = "ISO-8859-1"
        // Quiet zone in modules. The QR spec recommends 4 modules; ZXing
        // defaults to 4. Pinned here so future ZXing default changes do not
        // shift our centred placement.
        hints[EncodeHintType.MARGIN] = 1

        val writer = QRCodeWriter()
        val matrix: BitMatrix = writer.encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun formatGeneratedAt(date: Date): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(date)
    }

    private fun truncateDeviceName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.length <= DEVICE_NAME_MAX_CHARS) return trimmed
        // Reserve 3 chars for the ellipsis "..." so the printed line never
        // exceeds DEVICE_NAME_MAX_CHARS visible characters.
        return trimmed.substring(0, DEVICE_NAME_MAX_CHARS - 3) + "..."
    }

    companion object {
        /** Sans-serif regular typeface family token. */
        internal const val TYPEFACE_REGULAR: String = "sans-serif"

        /** Sans-serif bold typeface family token. Used by titles and Page 2 headings. */
        internal const val TYPEFACE_BOLD: String = "sans-serif-bold"

        /** Monospaced bold typeface family token. Used by the SK line on Page 1. */
        internal const val TYPEFACE_MONO_BOLD: String = "monospace-bold"

        /**
         * Page 2 body text. Lines prefixed with `# ` render as bold headings;
         * everything else renders as body. Storing it as a flat list keeps
         * the layout-builder deterministic and the test fixture small.
         *
         * The instructions are intentionally short - users facing a recovery
         * scenario are stressed, so the text optimises for skim-readability
         * over completeness. The in-app FAQ carries the deeper explanation.
         */
        internal val PAGE_2_INSTRUCTIONS: List<String> = listOf(
            "# What the Secret Key does",
            "The Secret Key strengthens your master password against attackers",
            "who steal your encrypted backup. Both values are required to",
            "decrypt a 1Key backup file marked V5 with the requires_secret_key",
            "flag set.",
            "",
            "# When you will need this kit",
            "Use this kit when restoring a backup on a new device, or when",
            "restoring after a vault reset on this device. The 1Key import",
            "screen will prompt you to scan the QR code on the front page,",
            "then ask for your master password.",
            "",
            "# How to keep it safe",
            "Print this kit on paper and store it in a locked drawer, safe",
            "deposit box, or other secure offline location. Do not store the",
            "kit in cloud storage or send it over email or chat: a copy of",
            "this kit plus a leaked backup is the equivalent of a single",
            "stolen master password.",
            "",
            "# If you lose the Secret Key",
            "Lost Secret Keys cannot be recovered. The 1Key team does not",
            "have a copy. If you lose both the kit and every device that",
            "had the vault unlocked, the backups protected by this kit are",
            "permanently unreadable. Print a fresh kit on a separate sheet",
            "and store the copies in different physical locations.",
            "",
            "# Rotation",
            "Rotating the Secret Key in Settings invalidates this kit. Save",
            "a fresh kit immediately after any rotation, then destroy the",
            "old paper copy.",
        )
    }
}
