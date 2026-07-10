package com.roufsyed.onekey.feature.settings.presentation.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsHighlightStore @Inject constructor() {

    private val _pendingKey = MutableStateFlow<String?>(null)
    val pendingKey: StateFlow<String?> = _pendingKey.asStateFlow()

    fun set(key: String?) {
        _pendingKey.value = key
    }

    fun clear() {
        _pendingKey.value = null
    }
}
