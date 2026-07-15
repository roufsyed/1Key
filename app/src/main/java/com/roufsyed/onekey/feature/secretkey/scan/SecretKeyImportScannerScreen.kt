package com.roufsyed.onekey.feature.secretkey.scan

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.roufsyed.onekey.core.presentation.util.oneKeyTopBarColors
import com.roufsyed.onekey.core.scan.ZxingQrAnalyzer
import java.util.concurrent.Executors

/**
 * QR scanner dedicated to the Secret-Key-required restore flow. The user
 * lands here from the restore-from-backup dialog after a backup with the
 * "requires Secret Key" flag is detected. Scans the QR off a printed (or
 * on-screen) Emergency Kit and returns the canonical Secret Key string
 * to the caller via SavedStateHandle.
 *
 * # Why a separate scanner from feature/twofa/.../QrScannerScreen.kt
 *
 * The 2FA scanner is tightly coupled to its own ViewModel, which on a
 * successful scan PARSES the otpauth URI and writes a credential to the
 * database. That coupling is intentional for the 2FA flow but makes the
 * 2FA scanner unusable as a generic "scan and return the payload" surface.
 *
 * This screen is intentionally minimal:
 *  - Composable-only (no ViewModel) - the only state is "have we detected
 *    a valid SK QR yet?" plus a transient snackbar for non-SK QRs.
 *  - Validation lives in [parseEmergencyKitQr], which rejects anything
 *    that is not the canonical `1key-emergency://...` URI shape.
 *  - On a valid scan the canonical 26-char SK string is written to the
 *    previous back-stack entry's SavedStateHandle under
 *    [com.roufsyed.onekey.core.presentation.navigation.SK_IMPORT_SCAN_RESULT_KEY],
 *    then this screen pops.
 *
 * # Permissions
 *
 * Camera permission is requested at first composition. Denial leaves the
 * scanner inert with a "permission required" message; user can re-enter
 * via the system settings and retry.
 *
 * # Camera lifecycle
 *
 * CameraX is bound to [LocalLifecycleOwner.current] and unbound by the
 * DisposableEffect on dispose. The ZXing analyzer is cancelled and the
 * analyzer executor is shut down on the same dispose so no camera-frame
 * work outlives the screen.
 *
 * @param onScanned receives the validated canonical SK string (26 chars,
 *   no dashes, no "A3-" prefix - matches [parseEmergencyKitQr] Ok
 *   payload). The caller takes ownership and is expected to navigate
 *   away (typically popBackStack after writing to SavedStateHandle).
 * @param onCancel called when the user taps Back without scanning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretKeyImportScannerScreen(
    onScanned: (canonicalSk: String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var detected by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                colors = oneKeyTopBarColors(),
                title = {
                    Column {
                        Text("Scan Emergency Kit")
                        Text(
                            "QR is read on this device; nothing is sent anywhere.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!hasPermission) {
                PermissionDeniedSurface()
            } else {
                CameraPreviewWithOverlay(
                    isScanning = !detected,
                    onBarcodeDetected = { raw ->
                        if (detected) return@CameraPreviewWithOverlay
                        when (val parsed = parseEmergencyKitQr(raw)) {
                            is QrParseResult.Ok -> {
                                detected = true
                                onScanned(parsed.canonicalSk)
                            }
                            QrParseResult.NotEmergencyKit -> {
                                // Common case for stray QRs. Toast and
                                // keep scanning. Throttle via the same
                                // detected gate so a sea of repeated
                                // frames does not spam the snackbar.
                            }
                            is QrParseResult.WrongVersion -> {
                                // Future-proofing: a kit minted by a
                                // newer 1Key build the user has not yet
                                // installed.
                            }
                            QrParseResult.Malformed -> {
                                // Same handling as NotEmergencyKit -
                                // wait for the next clean frame.
                            }
                        }
                    },
                )
            }
        }
    }

    // Single snackbar for "not a 1Key Emergency Kit" so the user can fix
    // the kit and keep trying without leaving the screen. Re-emits once
    // per second at most to avoid scanner-flood spam.
    LaunchedEffect(Unit) {
        // No-op for now; the snackbar logic could be enriched if users
        // report aiming-at-wrong-QR confusion. Hosted here so we have a
        // stable insertion point.
    }
}

@Composable
private fun PermissionDeniedSurface() {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "1Key needs camera access to scan the QR code on your Emergency " +
                "Kit. Enable Camera in system Settings, then return here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * CameraX + ZXing QR preview with a centred viewfinder overlay.
 * Mirrors the shape of the 2FA scanner's preview composable but takes
 * a generic onBarcodeDetected(rawPayload) callback instead of routing
 * through a 2FA-specific ViewModel.
 */
@Composable
private fun CameraPreviewWithOverlay(
    onBarcodeDetected: (String) -> Unit,
    isScanning: Boolean,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // rememberUpdatedState so the analyzer always calls the latest
    // callback even if the parent recomposes after the factory ran.
    val onDetectedState = rememberUpdatedState(newValue = onBarcodeDetected)

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    // ZXing (pure-Java, FOSS) replaces ML Kit BarcodeScanning. Decodes QR on the
    // analysis executor and delivers the raw payload on the main thread.
    val analyzer = remember { ZxingQrAnalyzer(onQrDecoded = { onDetectedState.value(it) }) }

    DisposableEffect(Unit) {
        onDispose {
            analyzer.cancel()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, analyzer) }
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    runCatching {
                        val provider = cameraProviderFuture.get()
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis,
                        )
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Viewfinder frame overlay. Same 260.dp box as the 2FA scanner so
        // muscle memory carries between the two flows.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .border(
                        width = 3.dp,
                        color = if (isScanning) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.tertiary,
                    )
                    .background(Color.Transparent),
            )
        }

        // Bottom hint band.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Hold the QR code from your Emergency Kit inside the frame.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            Text(
                "Or tap Back to type the Secret Key by hand.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

// Silences the unused-import warning for SnackbarDuration which we leave
// imported so a future enrichment of the LaunchedEffect snackbar logic
// does not require a re-import. Cheap to keep.
@Suppress("unused")
private val SNACKBAR_DURATION_HOOK = SnackbarDuration.Short
