package com.onekey.feature.twofa.presentation.screen

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.onekey.core.presentation.lockaware.LockAwareDialog
import com.onekey.core.presentation.lockaware.LockAwareOutlinedTextField
import com.onekey.feature.twofa.domain.ParsedOtpAuthUri
import com.onekey.feature.twofa.presentation.viewmodel.QrScannerViewModel
import com.onekey.feature.twofa.presentation.viewmodel.ScanEvent
import com.onekey.feature.twofa.presentation.viewmodel.ScanState
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: QrScannerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    // Camera previews don't surface touch events, so the inactivity auto-lock
    // would otherwise fire mid-scan. Pair acquire/release with the screen's
    // composition lifetime - onDispose runs on back-press, vault-lock-driven
    // navigation, and process death equivalents in compose.
    DisposableEffect(Unit) {
        viewModel.beginCameraSession()
        onDispose { viewModel.endCameraSession() }
    }

    LaunchedEffect(state) {
        if (state is ScanState.Saved) onSaved()
    }

    // Surface a transient snackbar when the camera reads a QR that isn't a 2FA code,
    // and surface save failures with the actual error message.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ScanEvent.InvalidQr -> snackbarHostState.showSnackbar(
                    message = "Not a 2FA QR code - try a different one.",
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }
    LaunchedEffect(state) {
        if (state is ScanState.Error) {
            snackbarHostState.showSnackbar(
                message = (state as ScanState.Error).message,
                duration = SnackbarDuration.Short,
            )
            viewModel.dismissDetected()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                title = {
                    Column {
                        Text("Scan QR Code")
                        Text(
                            "QR codes are read on-device. Nothing is sent to any server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (hasPermission) {
                CameraPreviewWithOverlay(
                    onBarcodeDetected = viewModel::onBarcodeDetected,
                    isScanning = state is ScanState.Scanning,
                )
            } else {
                NoCameraPermissionContent {
                    permissionLauncher.launch(android.Manifest.permission.CAMERA)
                }
            }
        }
    }

    when (val s = state) {
        is ScanState.Detected -> DetectedDialog(
            detected = s,
            onSave = viewModel::save,
            onDismiss = viewModel::dismissDetected,
        )
        is ScanState.Saving -> SavingDialog()
        // Error already handled by the snackbar LaunchedEffect above.
        else -> Unit
    }
}

@Composable
private fun CameraPreviewWithOverlay(
    onBarcodeDetected: (String) -> Unit,
    isScanning: Boolean,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // rememberUpdatedState so the factory lambda always calls the latest callback
    // even if the parent recomposes before the factory is invoked.
    val onDetectedState = rememberUpdatedState(newValue = onBarcodeDetected)

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            barcodeScanner.close()
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
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            // CameraX's ExperimentalGetImage uses androidx RequiresOptIn,
                            // so a Kotlin @OptIn is a no-op. AGP lint still flags
                            // imageProxy.image inside this nested lambda - suppress here.
                            @SuppressLint("UnsafeOptInUsageError")
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val input = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees,
                                )
                                barcodeScanner.process(input)
                                    .addOnSuccessListener { barcodes ->
                                        // Pass every QR through to the VM - it parses and
                                        // decides between a valid otpauth URI and an
                                        // InvalidQr event.
                                        barcodes.firstOrNull()?.rawValue
                                            ?.let { onDetectedState.value(it) }
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            } else {
                                imageProxy.close()
                            }
                        }
                    }

                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

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

        // Viewfinder frame overlay
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .border(
                        width = 3.dp,
                        color = if (isScanning) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.tertiary,
                        shape = RoundedCornerShape(16.dp),
                    ),
            )
        }

        // Instruction hint
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = if (isScanning) "Point camera at a 2FA QR code" else "QR code detected",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun DetectedDialog(
    detected: ScanState.Detected,
    onSave: (ParsedOtpAuthUri, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(detected.suggestedTitle) { mutableStateOf(detected.suggestedTitle) }
    LockAwareDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save 2FA Account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (detected.parsed.issuer.isNotEmpty()) {
                    LabelValue("Service", detected.parsed.issuer)
                }
                if (detected.parsed.account.isNotEmpty()) {
                    LabelValue("Account", detected.parsed.account)
                }
                LockAwareOutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(detected.parsed, title) },
                enabled = title.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SavingDialog() {
    LockAwareDialog(
        onDismissRequest = {},
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text("Saving…")
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun NoCameraPermissionContent(onRequest: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Text(
            "Camera permission is required to scan QR codes",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onRequest) { Text("Grant Permission") }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
