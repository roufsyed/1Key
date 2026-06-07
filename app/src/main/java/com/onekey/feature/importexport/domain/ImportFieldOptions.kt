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
    /**
     * When true, blank titles are auto-filled from the URL via
     * [com.onekey.feature.importexport.domain.UrlTitleExtractor]. Independent of
     * [url] - turning the URL field off still lets us rescue the title from the
     * parsed URL before discarding it.
     */
    val deriveTitleFromUrl: Boolean = true,
)
