package com.roufsyed.onekey.core.domain.passkey

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One signing certificate an allowlisted package may be signed with. */
data class PrivilegedFingerprint(
    /** Uppercase, colon-separated SHA-256, the exact form Google's list and `CallingAppInfo.getOrigin` use. */
    val sha256: String,
    /** `release` or `userdebug`; the latter only ever counts on a userdebug device. */
    val build: String,
)

data class PrivilegedApp(val packageName: String, val fingerprints: Set<PrivilegedFingerprint>)

/**
 * The privileged-browser allowlist schema Google publishes at
 * `gstatic.com/gpm-passkeys-privileged-apps/apps.json` and that
 * `androidx.credentials.provider.CallingAppInfo.getOrigin` consumes:
 *
 * ```
 * {"apps":[{"type":"android","info":{"package_name":"...","signatures":[{"build":"release","cert_fingerprint_sha256":"AA:BB:..."}]}}]}
 * ```
 *
 * 1Key merges several sources into one document of this shape (the bundled
 * Google snapshot, the community list, the F-Droid flavour's override and the
 * user's own trusted browsers). Everything here is pure Kotlin on Gson so the
 * merge, the device filter and the snapshot checks run as JVM tests.
 */
object PrivilegedAppList {
    const val BUILD_RELEASE = "release"
    const val BUILD_USERDEBUG = "userdebug"

    /**
     * Parses one allowlist document. Lenient about trailing commas (one
     * upstream community file carries them) but strict about shape: every
     * fingerprint must be 32 bytes of hex or the entry is dropped.
     */
    fun parse(json: String): List<PrivilegedApp> {
        if (json.isBlank()) return emptyList()
        val reader = JsonReader(StringReader(json)).apply { isLenient = true }
        val root = JsonParser.parseReader(reader)
        if (!root.isJsonObject) return emptyList()
        val apps = root.asJsonObject.get("apps")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val out = LinkedHashMap<String, MutableSet<PrivilegedFingerprint>>()
        for (element in apps) {
            val app = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            if (app.get("type")?.takeIf { it.isJsonPrimitive }?.asString != "android") continue
            val info = app.get("info")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val packageName = info.get("package_name")?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: continue
            if (packageName.isEmpty()) continue
            val signatures = info.get("signatures")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (sigElement in signatures) {
                val sig = sigElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val raw = sig.get("cert_fingerprint_sha256")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                val normalised = normaliseFingerprint(raw) ?: continue
                val build = sig.get("build")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase() ?: BUILD_RELEASE
                out.getOrPut(packageName) { LinkedHashSet() } += PrivilegedFingerprint(normalised, build)
            }
        }
        return out.filterValues { it.isNotEmpty() }.map { (pkg, fps) -> PrivilegedApp(pkg, fps.toSet()) }
    }

    /** Union by package name, union of fingerprints per package. Order of first appearance is kept. */
    fun merge(vararg lists: List<PrivilegedApp>): List<PrivilegedApp> {
        val out = LinkedHashMap<String, LinkedHashSet<PrivilegedFingerprint>>()
        for (list in lists) for (app in list) out.getOrPut(app.packageName) { LinkedHashSet() } += app.fingerprints
        return out.map { (pkg, fps) -> PrivilegedApp(pkg, fps.toSet()) }
    }

    /**
     * Drops `userdebug` fingerprints unless the device itself is a userdebug
     * build: those entries exist for Google's internal test builds, whose
     * signing keys are widely available, and must never vouch for an origin on
     * a production phone. Packages left without fingerprints disappear.
     */
    fun filterForDevice(list: List<PrivilegedApp>, isUserdebugDevice: Boolean): List<PrivilegedApp> =
        if (isUserdebugDevice) list
        else list.mapNotNull { app ->
            val kept = app.fingerprints.filter { it.build != BUILD_USERDEBUG }.toSet()
            if (kept.isEmpty()) null else app.copy(fingerprints = kept)
        }

    /** Serialises back to the Google schema, sorted by package so the output is stable. */
    fun toJson(list: List<PrivilegedApp>): String {
        val apps = JsonArray()
        for (app in list.sortedBy { it.packageName }) {
            val signatures = JsonArray()
            for (fp in app.fingerprints.sortedBy { it.sha256 }) {
                signatures.add(JsonObject().apply {
                    addProperty("build", fp.build)
                    addProperty("cert_fingerprint_sha256", fp.sha256)
                })
            }
            apps.add(JsonObject().apply {
                addProperty("type", "android")
                add("info", JsonObject().apply {
                    addProperty("package_name", app.packageName)
                    add("signatures", signatures)
                })
            })
        }
        return JsonObject().apply { add("apps", apps) }.toString()
    }

    /** Builds the entry for a browser the user trusted by hand: one package with every certificate in its lineage. */
    fun userEntry(packageName: String, lineageSha256Hex: Collection<String>): PrivilegedApp? {
        val fps = lineageSha256Hex.mapNotNull { normaliseFingerprint(it) }.map { PrivilegedFingerprint(it, BUILD_RELEASE) }.toSet()
        return if (fps.isEmpty()) null else PrivilegedApp(packageName, fps)
    }

    /** Accepts `AA:BB:...`, `aabb...` or `aa:bb:...`; returns the canonical uppercase colon form or null. */
    fun normaliseFingerprint(raw: String): String? {
        val hex = raw.trim().replace(":", "").lowercase()
        if (hex.length != 64 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return hex.chunked(2).joinToString(":").uppercase()
    }
}

/**
 * Sidecar for the bundled Google snapshot (`privileged_apps_google.meta.json`):
 * where it came from, its hash, when it was fetched and how many entries it had.
 * Shown in Settings and checked by the snapshot test.
 */
data class AllowlistSnapshotMeta(
    val source: String,
    val sha256: String,
    val fetchedAt: LocalDate,
    val entries: Int,
) {
    /** Age in whole days at [today]; drives the warn-only freshness check (plan decision D7). */
    fun ageInDays(today: LocalDate): Long = ChronoUnit.DAYS.between(fetchedAt, today)

    fun isOlderThan(days: Long, today: LocalDate): Boolean = ageInDays(today) > days

    companion object {
        /** Warn (never block) when the snapshot is older than this at build or release time. */
        const val WARN_AFTER_DAYS = 60L

        fun parse(json: String): AllowlistSnapshotMeta {
            val obj = JsonParser.parseString(json).asJsonObject
            return AllowlistSnapshotMeta(
                source = obj.get("source").asString,
                sha256 = obj.get("sha256").asString.lowercase(),
                fetchedAt = LocalDate.parse(obj.get("fetched_at").asString),
                entries = obj.get("entries").asInt,
            )
        }
    }
}
