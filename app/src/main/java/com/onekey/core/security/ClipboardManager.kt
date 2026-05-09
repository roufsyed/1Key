package com.onekey.core.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val CLIPBOARD_CLEAR_SECONDS = 30

/**
 * Application-singleton clipboard manager for sensitive values. Provides:
 *
 *  1. **Sensitive marking** — on API 33+ the clip carries [ClipDescription.EXTRA_IS_SENSITIVE]
 *     so the OS paste-preview toast doesn't display the value and the clipboard manager
 *     skips persisting the entry.
 *  2. **Conditional auto-clear** — after [CLIPBOARD_CLEAR_SECONDS] the clipboard is wiped
 *     only if it still contains the value we put there. If the user copied something else
 *     from another app in the meantime, we leave their content alone.
 *  3. **Navigation-safe scheduling** — the clear coroutine runs in this singleton's
 *     scope, so it survives screen navigation, app backgrounding, and vault auto-lock.
 *  4. **Visible countdown** — [countdown] emits the remaining seconds so UI can display
 *     a "Clipboard clears in Xs" badge while the auto-clear is pending.
 */
@Singleton
class SecureClipboardManager @Inject constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var clearJob: Job? = null

    private val _countdown = MutableStateFlow<Int?>(null)
    val countdown: StateFlow<Int?> = _countdown.asStateFlow()

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
        scheduleCountdownAndClear(value)
    }

    private fun scheduleCountdownAndClear(originalValue: String) {
        clearJob?.cancel()
        clearJob = scope.launch {
            for (remaining in CLIPBOARD_CLEAR_SECONDS downTo 1) {
                _countdown.value = remaining
                delay(1_000L)
            }
            _countdown.value = null

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
        _countdown.value = null
    }
}
