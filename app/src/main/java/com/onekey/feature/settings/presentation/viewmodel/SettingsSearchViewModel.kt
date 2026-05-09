package com.onekey.feature.settings.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class SettingsSearchViewModel @Inject constructor() : ViewModel() {

    private val index: List<SettingsEntry> = buildSettingsIndex()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchActive = MutableStateFlow(false)
    val searchActive: StateFlow<Boolean> = _searchActive.asStateFlow()

    val results: StateFlow<List<SettingsEntry>> = _query
        .debounce(80)
        .map { q ->
            if (q.length < 2) return@map emptyList()
            val lower = q.lowercase()
            index.filter { entry ->
                entry.title.lowercase().contains(lower) ||
                    entry.subtitle.lowercase().contains(lower) ||
                    entry.keywords.any { it.lowercase().contains(lower) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun updateQuery(q: String) {
        _query.value = q
    }

    fun setSearchActive(active: Boolean) {
        _searchActive.value = active
        if (!active) _query.value = ""
    }
}
