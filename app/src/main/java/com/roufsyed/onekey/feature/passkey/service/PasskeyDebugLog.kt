package com.roufsyed.onekey.feature.passkey.service

import android.util.Log
import com.roufsyed.onekey.BuildConfig

/**
 * Debug-build-only trace of the provider's decisions, for reading in logcat
 * while testing on a device. Compiled out of release builds by the
 * `BuildConfig.DEBUG` check. Never passed a site name, an account name or key
 * material: callers log package names, booleans, counts and exception types.
 */
internal object PasskeyDebugLog {
    private const val TAG = "1KeyPasskey"

    inline fun d(message: () -> String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message())
    }
}
