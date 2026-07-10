package com.roufsyed.onekey.core.security

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RootDetector @Inject constructor(private val context: Context) {

    data class RootCheckResult(val isRooted: Boolean, val reason: String?)

    fun check(): RootCheckResult {
        val reasons = mutableListOf<String>()

        if (hasSuBinary()) reasons += "su binary detected"
        if (hasKnownRootApps()) reasons += "root management app installed"
        if (hasWritableSystemPath()) reasons += "system path is writable"
        if (hasDangerousProps()) reasons += "dangerous build props found"
        if (hasTestKeys()) reasons += "device uses test-release keys"

        return if (reasons.isEmpty()) {
            RootCheckResult(false, null)
        } else {
            RootCheckResult(true, reasons.joinToString("; "))
        }
    }

    private fun hasSuBinary(): Boolean {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su",
            "/su/bin/su"
        )
        return paths.any { File(it).exists() }
    }

    private fun hasKnownRootApps(): Boolean {
        val rootPackages = listOf(
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.kingroot.kinguser",
            "com.kingo.root",
            "com.smedialink.oneclickroot",
            "com.zhiqupk.root.global",
            "com.alephzain.framaroot",
        )
        val pm = context.packageManager
        return rootPackages.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    private fun hasWritableSystemPath(): Boolean {
        val paths = listOf("/system", "/system/bin", "/system/sbin", "/system/xbin",
            "/vendor/bin", "/sys", "/sbin", "/etc")
        return paths.any { path ->
            try {
                val f = File(path)
                f.exists() && f.canWrite()
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun hasDangerousProps(): Boolean {
        val dangerousProps = mapOf(
            "ro.debuggable" to "1",
            "ro.secure" to "0",
        )
        return dangerousProps.any { (key, value) ->
            try {
                val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
                try {
                    process.inputStream.bufferedReader().use { it.readLine()?.trim() == value }
                } finally {
                    // Without destroy() each call leaks a subprocess + file descriptor.
                    process.destroy()
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun hasTestKeys(): Boolean {
        val tags = android.os.Build.TAGS
        return tags != null && tags.contains("test-keys")
    }
}
