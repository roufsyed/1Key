package com.onekey.core.presentation.lockaware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Drop-in replacement for [androidx.compose.material3.AlertDialog] that wires
 * the surface modifier through [Modifier.lockAware] (so touches/keys inside
 * keep the inactivity timer alive) AND keeps the dialog body above the
 * keyboard.
 *
 * Why we don't just use `AlertDialog` here: M3's `AlertDialog` relies on the
 * platform Dialog window's `softInputMode` to resize when the IME shows. That
 * works on some devices/Compose versions and fails on others, leaving tall
 * dialogs (descriptive copy + a `TextField` + buttons - e.g. the biometric and
 * remove-PIN confirms) with their button row pushed below the keyboard.
 *
 * To make the IME behaviour deterministic we render with the raw `Dialog`,
 * size the dialog window to fill the screen (`usePlatformDefaultWidth = false`),
 * apply `.imePadding()` to the outer wrapper, and centre an
 * `AlertDialog`-styled `Surface` inside that wrapper. When the keyboard opens
 * the wrapper shrinks; the surface re-centres in the smaller area; if the
 * surface's natural content exceeds the available height, the text body
 * scrolls (via the `weight(1f, fill = false) + verticalScroll` slot) and the
 * buttons remain pinned at the bottom of the surface - always visible above
 * the keyboard.
 *
 * Visual layout mirrors `AlertDialog` (icon → title → text → buttons row,
 * 24dp padding, M3 spacing constants) so call sites don't change.
 */
@Composable
fun LockAwareDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        // `usePlatformDefaultWidth = false` lets us size the dialog window to
        // the full screen so the centring `Box` below has room to recentre the
        // surface as the IME inset changes. The other DialogProperties (back-
        // press, click-outside, secure flag) flow through unchanged.
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = modifier
                    .lockAware()
                    .widthIn(min = 280.dp, max = 560.dp),
                shape = shape,
                color = containerColor,
                tonalElevation = tonalElevation,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    if (icon != null) {
                        CompositionLocalProvider(LocalContentColor provides iconContentColor) {
                            Box(
                                modifier = Modifier
                                    .padding(bottom = 16.dp)
                                    .align(Alignment.CenterHorizontally),
                            ) { icon() }
                        }
                    }
                    if (title != null) {
                        CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                                Box(
                                    modifier = Modifier
                                        .padding(bottom = 16.dp)
                                        .align(
                                            if (icon == null) Alignment.Start
                                            else Alignment.CenterHorizontally
                                        ),
                                ) { title() }
                            }
                        }
                    }
                    if (text != null) {
                        CompositionLocalProvider(LocalContentColor provides textContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                Box(
                                    modifier = Modifier
                                        .weight(weight = 1f, fill = false)
                                        .verticalScroll(rememberScrollState())
                                        .padding(bottom = 24.dp)
                                        .align(Alignment.Start),
                                ) { text() }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.End),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.primary,
                            LocalTextStyle provides MaterialTheme.typography.labelLarge,
                        ) {
                            if (dismissButton != null) {
                                dismissButton()
                                Spacer(Modifier.width(8.dp))
                            }
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}
