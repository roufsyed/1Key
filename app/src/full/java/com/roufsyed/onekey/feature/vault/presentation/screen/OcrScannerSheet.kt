package com.roufsyed.onekey.feature.vault.presentation.screen

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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.roufsyed.onekey.core.presentation.lockaware.LockAwareDialog
import com.roufsyed.onekey.core.presentation.lockaware.LockAwareModalBottomSheet

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
    targets: List<OcrTarget>,
    onResult: (OcrAssignments) -> Unit,
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

    // ── ML Kit recogniser - closed when sheet leaves composition ───────────
    val textRecognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    DisposableEffect(Unit) { onDispose { textRecognizer.close() } }

    // ── UI state ───────────────────────────────────────────────────────────
    var ocrState        by remember { mutableStateOf<OcrSheetState>(OcrSheetState.Preview) }
    val imageCaptureRef = remember { mutableStateOf<ImageCapture?>(null) }
    // Single-block assignments: Username, Password, Url, and each CustomField map to one block.
    var singleAssignments by remember { mutableStateOf<Map<OcrTarget, String>>(emptyMap()) }
    // Notes is the only target that aggregates multiple blocks.
    var notesBlocks       by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingBlock      by remember { mutableStateOf<String?>(null) }

    /**
     * True when the user has assigned at least one captured text block to a
     * field. Drives the discard guard on every dismiss path so a stray swipe
     * doesn't throw away the work the user did selecting blocks.
     *
     * State checks (`Selecting`) aren't part of this - the assignments live
     * across state transitions (e.g. Retake clears them, NoText shouldn't
     * have any). We rely directly on the assignment containers.
     */
    val hasChanges by remember {
        derivedStateOf { singleAssignments.isNotEmpty() || notesBlocks.isNotEmpty() }
    }

    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }

    // ── Capture + OCR (all callbacks on main thread) ───────────────────────
    fun captureAndProcess() {
        val capture = imageCaptureRef.value ?: return
        imageCaptureRef.value = null  // disable button; prevents a second tap racing in
        ocrState = OcrSheetState.Processing

        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {

                override fun onCaptureSuccess(image: ImageProxy) {
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
                }

                override fun onError(exception: ImageCaptureException) {
                    ocrState = OcrSheetState.Preview
                }
            }
        )
    }

    // ── Dismiss guard ──────────────────────────────────────────────────────
    // Block hide-attempts (swipe-down, scrim tap, system back) when the user
    // has accumulated assignments; surface the discard dialog instead. The
    // success path through the "Done" button calls `onResult` then `onDismiss`
    // which flips the parent's `showSheet` flag - that programmatic dismissal
    // tears down without going through `confirmValueChange`, so no bypass flag
    // is needed.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && hasChanges) {
                showDiscardDialog = true
                false
            } else {
                true
            }
        },
    )

    // While the discard dialog is up, system-back should close the dialog
    // ("Keep selecting"), not unwind into the sheet's own back-handling.
    BackHandler(enabled = showDiscardDialog) {
        showDiscardDialog = false
    }

    // ── Sheet ──────────────────────────────────────────────────────────────
    LockAwareModalBottomSheet(
        onDismissRequest = {
            // Only fires when `confirmValueChange` allowed the hide, which
            // means the user has no unsaved assignments.
            onDismiss()
        },
        sheetState = sheetState,
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
                        targets = targets,
                        singleAssignments = singleAssignments,
                        notesBlocks = notesBlocks,
                        onBlockTap = { pendingBlock = it },
                        onDone = {
                            onResult(buildAssignments(targets, singleAssignments, notesBlocks))
                            onDismiss()
                        },
                        onRetake = {
                            singleAssignments = emptyMap()
                            notesBlocks = emptyList()
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
        OcrAssignmentDialog(
            block = block,
            targets = targets,
            assignedTarget = currentAssignmentFor(block, singleAssignments, notesBlocks),
            blockInNotes = block in notesBlocks,
            onPick = { picked ->
                when (picked) {
                    OcrTarget.Notes -> {
                        if (block in notesBlocks) {
                            notesBlocks = notesBlocks - block
                        } else {
                            // Block can only live in one slot at a time.
                            singleAssignments = singleAssignments.filterValues { it != block }
                            notesBlocks = notesBlocks + block
                        }
                    }
                    else -> {
                        // Replace semantics: assigning a block to a single-slot target removes
                        // it from any prior slot AND removes any prior block already in `picked`.
                        notesBlocks = notesBlocks - block
                        singleAssignments = singleAssignments
                            .filterValues { it != block }
                            .plus(picked to block)
                    }
                }
                pendingBlock = null
            },
            onDismiss = { pendingBlock = null },
        )
    }

    if (showDiscardDialog) {
        LockAwareDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard scanned text?") },
            text = {
                Text(
                    "Your field selections will be lost. This can't be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        // Forwarding to the parent flips its `showSheet` flag
                        // and removes the sheet from composition without
                        // re-entering `confirmValueChange`.
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep selecting")
                }
            },
        )
    }
}

private fun currentAssignmentFor(
    block: String,
    singleAssignments: Map<OcrTarget, String>,
    notesBlocks: List<String>,
): String? = when {
    block in notesBlocks -> OcrTarget.Notes.label
    else -> singleAssignments.entries.firstOrNull { it.value == block }?.key?.label
}

private fun buildAssignments(
    targets: List<OcrTarget>,
    singleAssignments: Map<OcrTarget, String>,
    notesBlocks: List<String>,
): OcrAssignments {
    val customFields = targets
        .filterIsInstance<OcrTarget.CustomField>()
        .mapNotNull { target ->
            val value = singleAssignments[target] ?: return@mapNotNull null
            OcrCustomFieldAssignment(key = target.key, value = value, sensitive = target.sensitive)
        }
    return OcrAssignments(
        username = singleAssignments[OcrTarget.Username],
        password = singleAssignments[OcrTarget.Password],
        url = singleAssignments[OcrTarget.Url],
        notes = notesBlocks.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        customFields = customFields,
    )
}

// ---------------------------------------------------------------------------
// Camera preview - binds ImageCapture to lifecycle; clears ref on dispose
// ---------------------------------------------------------------------------

@Composable
private fun OcrCameraPreview(
    imageCaptureRef: MutableState<ImageCapture?>,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

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
    targets: List<OcrTarget>,
    singleAssignments: Map<OcrTarget, String>,
    notesBlocks: List<String>,
    onBlockTap: (String) -> Unit,
    onDone: () -> Unit,
    onRetake: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {

        // Compact summary: one chip per assigned target. Skipped entirely when nothing's
        // been picked yet - keeps the empty state tidy.
        val assignedSummary = targets.mapNotNull { target ->
            when (target) {
                OcrTarget.Notes -> if (notesBlocks.isNotEmpty()) {
                    target.label to "${notesBlocks.size} block${if (notesBlocks.size == 1) "" else "s"}"
                } else null
                else -> singleAssignments[target]?.let { target.label to it }
            }
        }
        if (assignedSummary.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                assignedSummary.forEach { (label, value) ->
                    OcrSlotCard(label = label, value = value, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Text(
            "Tap a block to assign it to a field. Tap an already-Notes block again to remove it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            blocks.forEach { block ->
                val assignment = currentAssignmentFor(block, singleAssignments, notesBlocks)
                OcrTextBlockCard(
                    text = block,
                    assignment = assignment,
                    onClick = { onBlockTap(block) },
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.height(12.dp))

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
                enabled = singleAssignments.isNotEmpty() || notesBlocks.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("Done") }
        }
    }
}

@Composable
private fun OcrAssignmentDialog(
    block: String,
    targets: List<OcrTarget>,
    assignedTarget: String?,
    blockInNotes: Boolean,
    onPick: (OcrTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    LockAwareDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign as…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    block,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (assignedTarget != null) {
                    Text(
                        "Currently assigned to: $assignedTarget",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                targets.forEach { target ->
                    val label = when (target) {
                        OcrTarget.Notes -> if (blockInNotes) "Remove from Notes" else "Add to Notes"
                        else -> target.label
                    }
                    OutlinedButton(
                        onClick = { onPick(target) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label) }
                }
            }
        },
        dismissButton = null,
    )
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
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(0.4f),
            )
            Text(
                text = value ?: "-",
                style = MaterialTheme.typography.bodySmall,
                color = if (value != null) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.6f),
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
