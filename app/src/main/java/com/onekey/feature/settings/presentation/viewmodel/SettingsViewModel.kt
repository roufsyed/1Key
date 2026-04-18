package com.onekey.feature.settings.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.Tag
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val tagRepository: TagRepository,
    private val appPrefs: AppPreferencesRepository,
) : ViewModel() {

    val tags: StateFlow<List<Tag>> = tagRepository.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isDarkTheme: StateFlow<Boolean> = appPrefs.isDarkTheme()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isBiometricEnabled: StateFlow<Boolean> = appPrefs.isBiometricEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleTheme() {
        viewModelScope.launch { appPrefs.setDarkTheme(!isDarkTheme.value) }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setBiometricEnabled(enabled) }
    }

    fun addTag(name: String) {
        viewModelScope.launch {
            tagRepository.addTag(Tag(name = name, color = 0xFF6200EE.toInt(), icon = ""))
        }
    }

    fun deleteTag(name: String) {
        viewModelScope.launch {
            val tag = tags.value.find { it.name == name }
            if (tag != null && !tag.isDefault) {
                tagRepository.deleteTag(name)
            }
        }
    }
}
