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

    // Singleton-scoped — lives for app lifetime, cancellation on process death is sufficient.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var clearJob: Job? = null

    fun copySecure(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        scheduleClear()
    }

    private fun scheduleClear() {
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(CLIPBOARD_CLEAR_DELAY_MS)
            // Re-fetch the system service in the coroutine body — no stale reference captured.
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    fun cancelScheduledClear() {
        clearJob?.cancel()
        clearJob = null
    }
}
