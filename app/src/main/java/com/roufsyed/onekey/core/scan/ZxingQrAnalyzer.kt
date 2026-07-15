package com.roufsyed.onekey.core.scan

import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * CameraX [ImageAnalysis.Analyzer] that decodes QR codes from the live camera
 * preview using ZXing core (via [QrLuminanceDecoder]) - the fully-FOSS
 * replacement for Google ML Kit's `BarcodeScanning`. It is the single decode
 * path shared by all three 1Key QR scanners (2FA enrolment, Emergency-Kit
 * import, in-editor TOTP).
 *
 * ## Threading
 * CameraX invokes [analyze] on the single-thread analysis executor the caller
 * passes to `setAnalyzer`; decoding runs there (off the main thread). The
 * decoded string is delivered on the **main thread** via [onQrDecoded], matching
 * ML Kit's old behaviour (its success listener also fired on main) so callers
 * can touch Compose state, navigate, or drive a ViewModel with no thread hop.
 *
 * De-duplication (a QR would otherwise decode ~30x/second) remains the caller's
 * responsibility, exactly as with ML Kit - every existing scanner already gates
 * repeats with an `AtomicBoolean` / `detected` flag.
 */
class ZxingQrAnalyzer(
    private val onQrDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val decoder = QrLuminanceDecoder()

    @Volatile
    private var active = true

    override fun analyze(image: ImageProxy) {
        try {
            if (active) {
                // Y (luminance) plane of the YUV_420_888 frame. pixelStride is
                // always 1; rowStride padding is handled inside the decoder.
                val plane = image.planes[0]
                val buffer = plane.buffer
                val yBytes = ByteArray(buffer.remaining())
                buffer.get(yBytes)

                decoder.decode(yBytes, plane.rowStride, image.width, image.height)
                    ?.let { text -> mainHandler.post { if (active) onQrDecoded(text) } }
            }
        } finally {
            // Must close every frame, or ImageAnalysis stops delivering.
            image.close()
        }
    }

    /**
     * Stop delivering results and drop any queued main-thread callback. Call
     * from the scanner's `onDispose`. Prevents a late in-flight decode from
     * navigating / mutating state after the screen has gone - a small
     * correctness improvement over the previous ML Kit path, which left its
     * success listener unguarded.
     */
    fun cancel() {
        active = false
        mainHandler.removeCallbacksAndMessages(null)
    }
}
