package com.onekey.feature.vault.presentation.screen

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

// ---------------------------------------------------------------------------
// State machine
// ---------------------------------------------------------------------------

private sealed class OcrSheetState {
    data object Preview    : OcrSheetState()
    data object Processing : OcrSheetState()
    data object NoText     : OcrSheetState()
    data class  Selecting(val blocks: List<String>) : OcrSheetState()
}

// ---------------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrScannerSheet(
    onResult: (username: String?, password: String?, notes: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // ── Camera permission ──────────────────────────────────────────────────
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

    // ── ML Kit recogniser — closed when sheet leaves composition ───────────
    val textRecognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    DisposableEffect(Unit) { onDispose { textRecognizer.close() } }

    // ── UI state ───────────────────────────────────────────────────────────
    var ocrState        by remember { mutableStateOf<OcrSheetState>(OcrSheetState.Preview) }
    val imageCaptureRef = remember { mutableStateOf<ImageCapture?>(null) }
    var selectedUsername by remember { mutableStateOf<String?>(null) }
    var selectedPassword by remember { mutableStateOf<String?>(null) }
    var selectedNotes    by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingBlock     by remember { mutableStateOf<String?>(null) }

    // ── Capture + OCR (all callbacks on main thread) ───────────────────────
    fun captureAndProcess() {
        val capture = imageCaptureRef.value ?: return
        imageCaptureRef.value = null  // disable button; prevents a second tap racing in
        ocrState = OcrSheetState.Processing

        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {

                override fun onCaptureSuccess(image: ImageProxy) {
                    // Convert to bitmap immediately so we can close the proxy early.
                    // toBitmap() is available since CameraX 1.2 and does not require
                    // @ExperimentalGetImage, keeping this file annotation-free.
                    val rotationDegrees = image.imageInfo.rotationDegrees
                    val bitmap = image.toBitmap()
                    image.close()

                    val inputImage = InputImage.fromBitmap(bitmap, rotationDegrees)
                    textRecognizer.process(inputImage)
                        .addOnSuccessListener { result ->
                            val blocks = result.textBlocks
                                .flatMap { it.lines }
                                .map { it.text.trim() }
                                .filter { it.isNotEmpty() && it.length <= 200 }
                                .distinct()
                                .take(30)
                            ocrState = if (blocks.isEmpty()) OcrSheetState.NoText
                                       else OcrSheetState.Selecting(blocks)
                        }
                        .addOnFailureListener {
                            ocrState = OcrSheetState.Preview
                        }
                    // No addOnCompleteListener needed — proxy already closed above.
                }

                override fun onError(exception: ImageCaptureException) {
                    // Runs on main thread (same executor passed to takePicture).
                    ocrState = OcrSheetState.Preview
                }
            }
        )
    }

    // ── Sheet ──────────────────────────────────────────────────────────────
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = if (ocrState is OcrSheetState.Selecting) "Select Fields" else "Scan Credentials",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "Text is read on-device. Nothing is sent to any server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))

            when (val state = ocrState) {

                OcrSheetState.Preview -> {
                    if (hasPermission) {
                        OcrCameraPreview(
                            imageCaptureRef = imageCaptureRef,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Point camera at printed credentials and tap Capture.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { captureAndProcess() },
                            enabled = imageCaptureRef.value != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Capture")
                        }
                    } else {
                        OcrNoPermissionContent {
                            permissionLauncher.launch(android.Manifest.permission.CAMERA)
                        }
                    }
                }

                OcrSheetState.Processing -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CircularProgressIndicator()
                            Text("Reading text…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                OcrSheetState.NoText -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(32.dp),
                        ) {
                            Text(
                                "No text detected in the photo.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                            OutlinedButton(onClick = {
                                imageCaptureRef.value = null
                                ocrState = OcrSheetState.Preview
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Try Again")
                            }
                        }
                    }
                }

                is OcrSheetState.Selecting -> {
                    OcrSelectionContent(
                        blocks = state.blocks,
                        selectedUsername = selectedUsername,
                        selectedPassword = selectedPassword,
                        selectedNotes = selectedNotes,
                        onBlockTap = { pendingBlock = it },
                        onDone = {
                            onResult(
                                selectedUsername,
                                selectedPassword,
                                selectedNotes.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                            )
                            onDismiss()
                        },
                        onRetake = {
                            selectedUsername = null
                            selectedPassword = null
                            selectedNotes = emptyList()
                            imageCaptureRef.value = null
                            ocrState = OcrSheetState.Preview
                        },
                    )
                }
            }
        }
    }

    // ── Assignment dialog (rendered outside the sheet so it layers on top) ─
    pendingBlock?.let { block ->
        // Capture current notes state so button label reflects it at render time.
        val blockInNotes = block in selectedNotes
        AlertDialog(
            onDismissRequest = { pendingBlock = null },
            title = { Text("Assign as…") },
            text = {
                Text(
                    block,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            selectedUsername = block
                            if (selectedPassword == block) selectedPassword = null
                            selectedNotes = selectedNotes - block
                            pendingBlock = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Username") }
                    OutlinedButton(
                        onClick = {
                            selectedPassword = block
                            if (selectedUsername == block) selectedUsername = null
                            selectedNotes = selectedNotes - block
                            pendingBlock = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Password") }
                    OutlinedButton(
                        onClick = {
                            if (blockInNotes) {
                                selectedNotes = selectedNotes - block
                            } else {
                                if (selectedUsername == block) selectedUsername = null
                                if (selectedPassword == block) selectedPassword = null
                                selectedNotes = selectedNotes + block
                            }
                            pendingBlock = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (blockInNotes) "Remove from Notes" else "Add to Notes") }
                }
            },
            dismissButton = null,
        )
    }
}

// ---------------------------------------------------------------------------
// Camera preview — binds ImageCapture to lifecycle; clears ref on dispose
// ---------------------------------------------------------------------------

@Composable
private fun OcrCameraPreview(
    imageCaptureRef: MutableState<ImageCapture?>,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // When this composable leaves composition (state changes away from Preview)
    // clear the stale capture reference so the button cannot fire against it.
    DisposableEffect(Unit) {
        onDispose { imageCaptureRef.value = null }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            ProcessCameraProvider.getInstance(ctx).also { future ->
                future.addListener({
                    runCatching {
                        val provider = future.get()
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture,
                        )
                        // Only expose the capture ref after a successful bind.
                        imageCaptureRef.value = imageCapture
                    }
                }, ContextCompat.getMainExecutor(ctx))
            }

            previewView
        },
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// Selection UI
// ---------------------------------------------------------------------------

@Composable
private fun OcrSelectionContent(
    blocks: List<String>,
    selectedUsername: String?,
    selectedPassword: String?,
    selectedNotes: List<String>,
    onBlockTap: (String) -> Unit,
    onDone: () -> Unit,
    onRetake: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {

        // Username + Password slot row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OcrSlotCard(label = "Username", value = selectedUsername, modifier = Modifier.weight(1f))
            OcrSlotCard(label = "Password", value = selectedPassword, modifier = Modifier.weight(1f))
        }

        // Notes slot — only shown when at least one block is assigned
        if (selectedNotes.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            OcrSlotCard(
                label = "Notes",
                value = "${selectedNotes.size} block${if (selectedNotes.size == 1) "" else "s"} selected",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            "Tap a block to assign it as username, password, or notes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(8.dp))

        // Scrollable block list — capped at 260 dp so the action row stays visible
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            blocks.forEach { block ->
                val assignment = when {
                    block == selectedUsername  -> "Username"
                    block == selectedPassword  -> "Password"
                    block in selectedNotes     -> "Notes"
                    else                       -> null
                }
                OcrTextBlockCard(
                    text = block,
                    assignment = assignment,
                    onClick = { onBlockTap(block) },
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.height(12.dp))

        // Action row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Retake")
            }
            Button(
                onClick = onDone,
                enabled = selectedUsername != null || selectedPassword != null || selectedNotes.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("Done") }
        }
    }
}

@Composable
private fun OcrTextBlockCard(
    text: String,
    assignment: String?,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(
            width = if (assignment != null) 1.5.dp else 1.dp,
            color = if (assignment != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (assignment != null) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = assignment,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OcrSlotCard(label: String, value: String?, modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = value ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = if (value != null) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OcrNoPermissionContent(onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                "Camera permission is required to scan credentials.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRequest) { Text("Grant Permission") }
        }
    }
}
