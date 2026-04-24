package com.onekey

import android.app.Application
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.repository.TagRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OneKeyApp : Application() {
    // Forces singleton creation at app startup so hot StateFlows begin
    // collecting before the user reaches the vault or settings screen.
    @Inject lateinit var tagRepository: TagRepository
    @Inject lateinit var appPreferences: AppPreferencesRepository
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var credentialRepository: CredentialRepository
}
