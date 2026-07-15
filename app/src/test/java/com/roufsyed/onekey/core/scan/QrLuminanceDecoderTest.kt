package com.roufsyed.onekey.core.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Decode-level safety net for the ML Kit -> ZXing QR migration. The camera
 * pipeline itself is untestable without a device, but [QrLuminanceDecoder] is
 * pure (no Android types), so we exercise it with QR codes we encode in-process
 * via ZXing's own [QRCodeWriter] - a genuine encode/decode round trip. The
 * Emergency-Kit QR is *already* ZXing-encoded in production, so this mirrors the
 * real round trip exactly.
 */
class QrLuminanceDecoderTest {

    private val decoder = QrLuminanceDecoder()

    /**
     * Encode [text] to a QR and return it as a tightly-packed luminance plane
     * (rowStride == width): 0x00 for dark modules, 0xFF for light. Triple is
     * (yData, width, height).
     */
    private fun qrLuminance(text: String, size: Int = 400): Triple<ByteArray, Int, Int> {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        val w = matrix.width
        val h = matrix.height
        val y = ByteArray(w * h)
        for (row in 0 until h) {
            for (col in 0 until w) {
                y[row * w + col] = if (matrix.get(col, row)) 0x00 else 0xFF.toByte()
            }
        }
        return Triple(y, w, h)
    }

    @Test
    fun decodes_otpauth_uri_round_trip() {
        val uri =
            "otpauth://totp/1Key:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=1Key&period=30&digits=6"
        val (y, w, h) = qrLuminance(uri)
        assertEquals(uri, decoder.decode(y, w, w, h))
    }

    @Test
    fun decodes_long_payload_round_trip() {
        val payload =
            "https://vault.example.com/recover?token=Zm9vYmFyMTIzNDU2Nzg5MC1hYmNkZWZnaGlqaw&v=2"
        val (y, w, h) = qrLuminance(payload)
        assertEquals(payload, decoder.decode(y, w, w, h))
    }

    @Test
    fun handles_row_stride_padding() {
        // Simulate a device whose Y-plane rows are padded (rowStride > width):
        // rebuild the buffer with extra bytes per row and confirm decoding still
        // works when the stride is passed correctly. With the naive
        // rowStride == width assumption this frame would fail to decode.
        val uri = "otpauth://totp/Acme:bob?secret=GEZDGNBVGY3TQOJQ&issuer=Acme"
        val (tight, w, h) = qrLuminance(uri)
        val padding = 24
        val stride = w + padding
        val padded = ByteArray(stride * h) { 0x7F } // arbitrary fill in the padding gap
        for (row in 0 until h) {
            System.arraycopy(tight, row * w, padded, row * stride, w)
        }
        assertEquals(uri, decoder.decode(padded, stride, w, h))
    }

    @Test
    fun returns_null_on_blank_frame() {
        // All-light plane → no finder patterns → NotFoundException → null.
        val w = 200
        val h = 200
        val blank = ByteArray(w * h) { 0xFF.toByte() }
        assertNull(decoder.decode(blank, w, w, h))
    }
}
