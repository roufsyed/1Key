package com.roufsyed.onekey.feature.vault.presentation.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.roufsyed.onekey.core.presentation.lockaware.LockAwareDialog

/**
 * F-Droid flavor of the credential-editor "Scan from photo" (OCR) affordance.
 *
 * The full flavor's [OcrScannerSheet] (src/full) reads text from a photo using
 * Google's proprietary ML Kit text-recognition library. F-Droid requires a
 * fully free/open-source build, so ML Kit is excluded from this flavor entirely
 * and OCR is therefore unavailable here.
 *
 * Rather than hide the editor's OCR icon - which would leave F-Droid users
 * wondering why the GitHub build has a button theirs lacks - we keep the icon
 * and, on tap, explain the trade-off. Nothing is actually lost: every field OCR
 * would fill is an ordinary text field the user can type into; only the shortcut
 * is gone.
 *
 * The signature matches the full flavor's [OcrScannerSheet] exactly so the
 * shared CredentialDetailScreen call site compiles unchanged. [targets] and
 * [onResult] are intentionally unused - this path never produces an assignment.
 *
 * Uses [LockAwareDialog] (never a raw AlertDialog): the UnsafeUnlockableSurface
 * lint rule fails the build on raw dialog surfaces outside the lockaware package.
 */
@Composable
fun OcrScannerSheet(
    targets: List<OcrTarget>,
    onResult: (OcrAssignments) -> Unit,
    onDismiss: () -> Unit,
) {
    LockAwareDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DocumentScanner, contentDescription = null) },
        title = { Text("Scan from photo isn't available") },
        text = {
            Text(
                "Reading text from a photo uses Google's proprietary ML Kit library. " +
                    "This F-Droid build is fully open-source, so that feature is left " +
                    "out.\n\nYou can type these details in directly, or install the " +
                    "build from GitHub if you need photo scanning.",
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        },
    )
}
