package com.roufsyed.onekey.core.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Pure QR decoder over a raw YUV_420_888 luminance (Y) plane, backed by ZXing
 * core. Deliberately free of any Android / CameraX type, so it is unit-testable
 * on the JVM without a device (see `QrLuminanceDecoderTest`). [ZxingQrAnalyzer]
 * owns the CameraX plumbing and delegates the actual decode here.
 *
 * Only [BarcodeFormat.QR_CODE] is decoded - every 1Key scanner is QR-only, and
 * restricting the format is faster and avoids 1-D-symbology false positives.
 * `TRY_HARDER` trades a little CPU per frame for a higher hit rate on a
 * hand-held preview.
 *
 * Not thread-safe: the [MultiFormatReader] is reused across calls (a large
 * speed-up for continuous scanning per ZXing's guidance) and must be driven
 * from a single thread - CameraX's analysis executor in production.
 */
class QrLuminanceDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    /**
     * Decode a single QR code from a luminance plane, or return null if none is
     * found / readable.
     *
     * @param yData row-major luminance bytes (the Y plane). Must contain at
     *   least [rowStride] * [height] bytes.
     * @param rowStride bytes per row (>= [width]). YUV_420_888 Y planes are
     *   frequently padded so rowStride > width; it is passed as the ZXing
     *   source's dataWidth and the visible region is cropped to [width], so the
     *   trailing padding bytes are skipped rather than decoded as image data.
     *   The naive `rowStride == width` assumption silently corrupts every row on
     *   devices that pad the plane.
     * @param width visible pixel width.
     * @param height visible pixel height.
     */
    fun decode(yData: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(
            yData,
            rowStride,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            reader.decodeWithState(bitmap).text
        } catch (_: NotFoundException) {
            // No QR in this frame - the overwhelmingly common case.
            null
        } catch (_: Exception) {
            // Any other decode error (ChecksumException, FormatException, or an
            // AIOOBE from an unusually-sized plane) → treat as "no readable QR".
            null
        } finally {
            reader.reset()
        }
    }
}
