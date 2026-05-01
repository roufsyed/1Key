package com.onekey.feature.twofa.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.onekey.core.presentation.lockaware.LockAwareModalBottomSheet

/**
 * Bottom-sheet picker shown when the 2FA list FAB is tapped. Two choices:
 * scan a QR code or enter the secret manually. Modeled after Google
 * Authenticator's add-account sheet — the most familiar pattern for users
 * coming from another authenticator.
 *
 * Composable rather than a NavHost route because both follow-up flows
 * (`QrScannerScreen` and `ManualOtpEntrySheet`) are themselves screen-level,
 * and stacking a route just to pick one would add a back-stack entry the
 * user has to dismiss for no UX benefit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddOtpFabSheet(
    onScanQr: () -> Unit,
    onEnterManually: () -> Unit,
    onDismiss: () -> Unit,
) {
    LockAwareModalBottomSheet(
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
                text = "Add 2FA Code",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text("Scan QR Code") },
                supportingContent = { Text("Use the camera to read a setup QR") },
                leadingContent = {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onScanQr()
                        onDismiss()
                    },
            )
            ListItem(
                headlineContent = { Text("Enter Manually") },
                supportingContent = { Text("Type the issuer, account, and secret") },
                leadingContent = {
                    Icon(Icons.Default.Edit, contentDescription = null)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onEnterManually()
                        onDismiss()
                    },
            )
        }
    }
}
