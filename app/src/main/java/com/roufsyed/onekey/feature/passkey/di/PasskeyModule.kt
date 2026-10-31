package com.roufsyed.onekey.feature.passkey.di

import com.roufsyed.onekey.feature.passkey.data.PasskeyPreferences
import com.roufsyed.onekey.feature.passkey.data.PasskeyPreferencesImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PasskeyModule {

    @Binds
    @Singleton
    abstract fun bindPasskeyPreferences(impl: PasskeyPreferencesImpl): PasskeyPreferences
}
