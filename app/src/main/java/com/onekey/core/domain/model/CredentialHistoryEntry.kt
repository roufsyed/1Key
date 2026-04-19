package com.onekey.core.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class CredentialHistoryEntry(
    val id: String,
    val credentialId: String,
    val title: String,
    val username: String,
    val password: String,
    val url: String,
    val modifiedAt: Long,
)
