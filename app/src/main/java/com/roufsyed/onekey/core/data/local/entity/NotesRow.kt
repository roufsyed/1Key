package com.roufsyed.onekey.core.data.local.entity

import androidx.room.ColumnInfo

/**
 * Ciphertext-only projection of one active row for the on-demand notes search
 * ([com.roufsyed.onekey.core.data.snapshot.NotesSearch]). Carries exactly what
 * decrypting the notes field needs and nothing else, so a scan never loads the
 * password, OTP or custom-field ciphertext.
 */
class NotesRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "notes_encrypted") val notesEncrypted: ByteArray,
    @ColumnInfo(name = "iv_notes") val ivNotes: ByteArray,
    @ColumnInfo(name = "cipher_version") val cipherVersion: Int,
)
