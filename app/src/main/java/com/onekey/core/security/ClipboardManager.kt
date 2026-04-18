package com.onekey.core.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

private const val CLIPBOARD_CLEAR_DELAY_MS = 30_000L

@Singleton
class SecureClipboardManager @Inject constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var clearJob: Job? = null

    fun copySecure(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, value)
        clipboard.setPrimaryClip(clip)
        scheduleClear(clipboard)
    }

    private fun scheduleClear(clipboard: ClipboardManager) {
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(CLIPBOARD_CLEAR_DELAY_MS)
            clearClipboard(clipboard)
        }
    }

    private fun clearClipboard(clipboard: ClipboardManager) {
        // Replace with empty clip — Android 13+ clears automatically,
        // but this covers older API levels.
        val empty = ClipData.newPlainText("", "")
        clipboard.setPrimaryClip(empty)
    }

    fun cancelScheduledClear() {
        clearJob?.cancel()
    }
}
