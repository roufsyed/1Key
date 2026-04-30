package com.onekey.core.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

private const val CLIPBOARD_CLEAR_DELAY_MS = 30_000L

/**
 * Application-singleton clipboard manager for sensitive values. Provides three
 * properties that the raw system clipboard does not:
 *
 *  1. **Sensitive marking** — on API 33+ the clip carries [ClipDescription.EXTRA_IS_SENSITIVE]
 *     so the OS paste-preview toast doesn't display the value and the clipboard manager
 *     skips persisting the entry.
 *  2. **Conditional auto-clear** — after [CLIPBOARD_CLEAR_DELAY_MS] the clipboard is wiped
 *     **only if it still contains the value we put there**. If the user copied something
 *     else from another app in the meantime, we leave their content alone.
 *  3. **Navigation-safe scheduling** — the clear coroutine runs in this singleton's
 *     scope, so it survives screen navigation, app backgrounding, and vault auto-lock.
 *     Process death cancels it, but a copied secret can't outlive the process anyway.
 */
@Singleton
class SecureClipboardManager @Inject constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var clearJob: Job? = null

    fun copySecure(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, value).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        clipboard.setPrimaryClip(clip)
        scheduleClear(value)
    }

    private fun scheduleClear(originalValue: String) {
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(CLIPBOARD_CLEAR_DELAY_MS)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // Only clear if the clipboard still holds what we put there. If the user
            // copied something else in the interim, leave their content alone.
            val current = runCatching {
                clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            }.getOrNull()
            if (current == originalValue) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
    }

    fun cancelScheduledClear() {
        clearJob?.cancel()
        clearJob = null
    }
}
