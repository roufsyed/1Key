package com.roufsyed.onekey.core.domain.passkey

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class PrivilegedAppListTest {

    private val fpA = "AA:" + "11:".repeat(30) + "22"
    private val fpB = "BB:" + "33:".repeat(30) + "44"

    private fun doc(vararg apps: String) = """{"apps":[${apps.joinToString(",")}]}"""
    private fun app(pkg: String, vararg sigs: Pair<String, String>) =
        """{"type":"android","info":{"package_name":"$pkg","signatures":[${sigs.joinToString(",") { """{"build":"${it.second}","cert_fingerprint_sha256":"${it.first}"}""" }}]}}"""

    @Test fun parse_normalises_fingerprints_and_drops_malformed_ones() {
        val list = PrivilegedAppList.parse(doc(app("com.a", fpA.lowercase() to "release", "nothex" to "release", fpB.replace(":", "") to "userdebug")))
        assertEquals(1, list.size)
        assertEquals("com.a", list[0].packageName)
        assertEquals(setOf(PrivilegedFingerprint(fpA, "release"), PrivilegedFingerprint(fpB, "userdebug")), list[0].fingerprints)
    }

    @Test fun parse_tolerates_trailing_commas_and_garbage() {
        val withTrailingComma = """{"apps":[{"type":"android","info":{"package_name":"com.a","signatures":[{"build":"release","cert_fingerprint_sha256":"$fpA"},]}},]}"""
        assertEquals(1, PrivilegedAppList.parse(withTrailingComma).size)
        assertTrue(PrivilegedAppList.parse("").isEmpty())
        assertTrue(PrivilegedAppList.parse("[]").isEmpty())
        assertTrue(PrivilegedAppList.parse(doc(app("com.a"))).isEmpty())
    }

    @Test fun merge_unions_packages_and_fingerprints() {
        val a = PrivilegedAppList.parse(doc(app("com.a", fpA to "release")))
        val b = PrivilegedAppList.parse(doc(app("com.a", fpB to "release"), app("com.b", fpB to "release")))
        val merged = PrivilegedAppList.merge(a, b)
        assertEquals(listOf("com.a", "com.b"), merged.map { it.packageName })
        assertEquals(2, merged[0].fingerprints.size)
    }

    @Test fun userdebug_fingerprints_only_count_on_userdebug_devices() {
        val list = PrivilegedAppList.parse(doc(app("com.a", fpA to "userdebug"), app("com.b", fpA to "release", fpB to "userdebug")))
        val production = PrivilegedAppList.filterForDevice(list, isUserdebugDevice = false)
        assertEquals(listOf("com.b"), production.map { it.packageName })
        assertEquals(setOf(PrivilegedFingerprint(fpA, "release")), production[0].fingerprints)
        assertEquals(2, PrivilegedAppList.filterForDevice(list, isUserdebugDevice = true).size)
    }

    @Test fun toJson_emits_google_schema_sorted_and_round_trips() {
        val list = PrivilegedAppList.parse(doc(app("com.z", fpB to "release"), app("com.a", fpA to "release")))
        val json = PrivilegedAppList.toJson(list)
        val apps = JsonParser.parseString(json).asJsonObject["apps"].asJsonArray
        assertEquals("com.a", apps[0].asJsonObject["info"].asJsonObject["package_name"].asString)
        assertEquals(fpA, apps[0].asJsonObject["info"].asJsonObject["signatures"].asJsonArray[0].asJsonObject["cert_fingerprint_sha256"].asString)
        assertEquals(list.sortedBy { it.packageName }, PrivilegedAppList.parse(json))
    }

    @Test fun user_entries_and_fingerprint_normalisation() {
        assertEquals(fpA, PrivilegedAppList.normaliseFingerprint(fpA.lowercase().replace(":", "")))
        assertNull(PrivilegedAppList.normaliseFingerprint("zz"))
        val entry = PrivilegedAppList.userEntry("org.example.browser", listOf(fpA, "bad"))
        assertEquals(1, entry!!.fingerprints.size)
        assertNull(PrivilegedAppList.userEntry("x", listOf("bad")))
    }

    @Test fun snapshot_meta_parses_and_ages() {
        val meta = AllowlistSnapshotMeta.parse("""{"source":"https://x","sha256":"ABC","fetched_at":"2026-11-01","entries":73}""")
        assertEquals("abc", meta.sha256)
        assertEquals(73, meta.entries)
        assertEquals(30L, meta.ageInDays(LocalDate.of(2026, 12, 1)))
        assertFalse(meta.isOlderThan(60, LocalDate.of(2026, 12, 1)))
        assertTrue(meta.isOlderThan(60, LocalDate.of(2027, 2, 1)))
    }

    // ── the bundled assets themselves ──────────────────────────────────────

    private fun asset(name: String): File {
        val candidates = listOf(File("src/main/assets/passkeys/$name"), File("app/src/main/assets/passkeys/$name"))
        return candidates.firstOrNull { it.exists() } ?: error("asset $name not found from ${File(".").absolutePath}")
    }

    @Test fun bundled_google_snapshot_is_well_formed_and_matches_its_meta() {
        val json = asset("privileged_apps_google.json").readText()
        val meta = AllowlistSnapshotMeta.parse(asset("privileged_apps_google.meta.json").readText())
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(asset("privileged_apps_google.json").readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals("meta sha256 must match the asset bytes; run scripts/refresh-passkey-allowlist.sh", meta.sha256, digest)
        val list = PrivilegedAppList.parse(json)
        assertEquals("meta entry count must match the asset", meta.entries, list.size)
        assertTrue("Google's list has dozens of browsers; a tiny list means a bad fetch", list.size >= 40)
        assertTrue(list.any { it.packageName == "com.android.chrome" })
        assertTrue(list.any { it.packageName == "org.mozilla.firefox" })
        assertTrue("Play services must stay listed so the QR cross-device flow reaches 1Key", list.any { it.packageName == "com.google.android.gms" })
        assertTrue(list.flatMap { it.fingerprints }.all { it.sha256.length == 95 })
    }

    @Test fun bundled_community_list_is_well_formed_and_disjoint_from_google() {
        val google = PrivilegedAppList.parse(asset("privileged_apps_google.json").readText())
        val community = PrivilegedAppList.parse(asset("privileged_apps_community.json").readText())
        assertTrue(community.size >= 5)
        assertTrue(community.any { it.packageName == "org.mozilla.fennec_fdroid" })
        val googleFps = google.flatMap { app -> app.fingerprints.map { app.packageName to it.sha256 } }.toSet()
        val duplicated = community.flatMap { app -> app.fingerprints.map { app.packageName to it.sha256 } }.filter { it in googleFps }
        assertTrue("community entries already in Google's list are redundant: $duplicated", duplicated.isEmpty())
    }

    /** Decision D7: freshness only warns. The release checklist says to refresh; this makes a skipped refresh visible. */
    @Test fun bundled_google_snapshot_freshness_warning_never_fails() {
        val meta = AllowlistSnapshotMeta.parse(asset("privileged_apps_google.meta.json").readText())
        val age = meta.ageInDays(LocalDate.now())
        if (age > AllowlistSnapshotMeta.WARN_AFTER_DAYS) {
            System.err.println(
                "WARNING: passkey browser allowlist snapshot is $age days old (fetched ${meta.fetchedAt}). " +
                    "Run scripts/refresh-passkey-allowlist.sh before the next release.",
            )
        }
        assertTrue(age >= 0)
    }
}
