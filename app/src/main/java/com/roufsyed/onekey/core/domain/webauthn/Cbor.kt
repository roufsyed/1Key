package com.roufsyed.onekey.core.domain.webauthn

import java.io.ByteArrayOutputStream

/**
 * Minimal canonical CBOR encoder (RFC 8949) for the handful of shapes WebAuthn
 * needs on the authenticator side: the COSE public key, the attestation object
 * and extension outputs. Encoding only: every request the provider receives
 * arrives as JSON through Credential Manager, so nothing is ever decoded.
 *
 * Hand-written rather than a library on purpose. The subset is a few dozen
 * lines, it keeps the dependency list unchanged (F-Droid reproducibility, no
 * Kotlin-metadata clash with the 2.0.20 compiler), and the encoding is pinned
 * by the RFC 8949 appendix A vectors in `CborTest`.
 *
 * Supported values: `null`, `Boolean`, `Int`, `Long`, `ByteArray` (byte
 * string), `String` (text string), `List<*>` (array) and `Map<*, *>` (map).
 * Maps are written in the canonical key order of RFC 8949 section 4.2.1
 * (shorter encoded key first, then lexicographic by unsigned byte), which is
 * also the CTAP2 canonical form relying parties expect.
 */
object Cbor {

    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, value)
        return out.toByteArray()
    }

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0xF6)
            is Boolean -> out.write(if (value) 0xF5 else 0xF4)
            is Int -> writeInteger(out, value.toLong())
            is Long -> writeInteger(out, value)
            is ByteArray -> {
                writeHead(out, MAJOR_BYTES, value.size.toLong())
                out.write(value)
            }
            is String -> {
                val bytes = value.toByteArray(Charsets.UTF_8)
                writeHead(out, MAJOR_TEXT, bytes.size.toLong())
                out.write(bytes)
            }
            is List<*> -> {
                writeHead(out, MAJOR_ARRAY, value.size.toLong())
                value.forEach { write(out, it) }
            }
            is Map<*, *> -> writeMap(out, value)
            else -> throw IllegalArgumentException("Unsupported CBOR value type: ${value::class.java.name}")
        }
    }

    private fun writeInteger(out: ByteArrayOutputStream, value: Long) {
        if (value >= 0) writeHead(out, MAJOR_UNSIGNED, value) else writeHead(out, MAJOR_NEGATIVE, -1L - value)
    }

    private fun writeMap(out: ByteArrayOutputStream, map: Map<*, *>) {
        // Canonical ordering: encode each key, sort by (encoded length, bytes).
        val entries = map.entries.map { (key, v) -> encode(key) to v }
            .sortedWith { a, b -> compareEncodedKeys(a.first, b.first) }
        writeHead(out, MAJOR_MAP, entries.size.toLong())
        for ((encodedKey, v) in entries) {
            out.write(encodedKey)
            write(out, v)
        }
    }

    private fun compareEncodedKeys(a: ByteArray, b: ByteArray): Int {
        if (a.size != b.size) return a.size.compareTo(b.size)
        for (i in a.indices) {
            val ua = a[i].toInt() and 0xFF
            val ub = b[i].toInt() and 0xFF
            if (ua != ub) return ua.compareTo(ub)
        }
        return 0
    }

    private fun writeHead(out: ByteArrayOutputStream, major: Int, argument: Long) {
        require(argument >= 0) { "CBOR head argument must be non-negative" }
        val mt = major shl 5
        when {
            argument < 24 -> out.write(mt or argument.toInt())
            argument <= 0xFF -> {
                out.write(mt or 24)
                out.write(argument.toInt())
            }
            argument <= 0xFFFF -> {
                out.write(mt or 25)
                out.write((argument shr 8).toInt())
                out.write(argument.toInt())
            }
            argument <= 0xFFFF_FFFFL -> {
                out.write(mt or 26)
                for (shift in 24 downTo 0 step 8) out.write((argument shr shift).toInt())
            }
            else -> {
                out.write(mt or 27)
                for (shift in 56 downTo 0 step 8) out.write((argument shr shift).toInt())
            }
        }
    }

    private const val MAJOR_UNSIGNED = 0
    private const val MAJOR_NEGATIVE = 1
    private const val MAJOR_BYTES = 2
    private const val MAJOR_TEXT = 3
    private const val MAJOR_ARRAY = 4
    private const val MAJOR_MAP = 5
}
