package com.onekey.feature.settings.presentation.viewmodel

import android.app.Application
import android.view.autofill.AutofillManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.repository.AppPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs [com.onekey.feature.settings.presentation.screen.SettingsAutofillScreen].
 *
 *  - [isAutofillEnabled] is the app's *soft* kill switch (DataStore-backed).
 *    Even when the OS reports us as the active provider, this flag lets the
 *    user disable our chips without untoggling at the OS level.
 *  - [isSystemAutofillProvider] mirrors [AutofillManager.hasEnabledAutofillServices].
 *    Refreshed lazily via [refreshSystemStatus] — the screen calls it on
 *    every ON_RESUME because the user typically toggles this in the system
 *    settings panel and we have no callback when they return.
 */
@HiltViewModel
class AutofillSettingsViewModel @Inject constructor(
    private val appPrefs: AppPreferencesRepository,
    private val highlightStore: SettingsHighlightStore,
    private val application: Application,
) : ViewModel() {

    val highlightKey: StateFlow<String?> = highlightStore.pendingKey
    fun clearHighlight() = highlightStore.clear()

    val isAutofillEnabled: StateFlow<Boolean> = appPrefs.isAutofillEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _isSystemAutofillProvider = MutableStateFlow(querySystemProviderStatus())
    val isSystemAutofillProvider: StateFlow<Boolean> = _isSystemAutofillProvider.asStateFlow()

    fun setAutofillEnabled(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setAutofillEnabled(enabled) }
    }

    /** Re-reads the OS-level provider status. Idempotent. */
    fun refreshSystemStatus() {
        _isSystemAutofillProvider.value = querySystemProviderStatus()
    }

    private fun querySystemProviderStatus(): Boolean {
        // AutofillManager can return null on form factors that don't support
        // autofill (e.g. Wear). Treat that as "not our provider" — the screen
        // will surface a useful message.
        val manager = application.getSystemService(AutofillManager::class.java) ?: return false
        return runCatching { manager.hasEnabledAutofillServices() }.getOrDefault(false)
    }
}
