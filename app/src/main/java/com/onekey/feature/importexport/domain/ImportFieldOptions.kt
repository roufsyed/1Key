package com.onekey.feature.importexport.domain

import androidx.compose.runtime.Immutable

@Immutable
data class ImportFieldOptions(
    val username: Boolean = true,
    val password: Boolean = true,
    val url: Boolean = true,
    val notes: Boolean = true,
    val totp: Boolean = true,
    val tags: Boolean = true,
    val customFieldKeys: Set<String> = emptySet(),
    val isFavorite: Boolean = true,
)
