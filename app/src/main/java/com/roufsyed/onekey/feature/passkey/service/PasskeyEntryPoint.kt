package com.roufsyed.onekey.feature.passkey.service

import com.roufsyed.onekey.core.domain.repository.AuthRepository
import com.roufsyed.onekey.feature.passkey.data.PasskeyPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point for [OneKeyCredentialProviderService]. Like the autofill
 * service, a `CredentialProviderService` is not a Hilt component type, so the
 * service resolves its singletons through `EntryPointAccessors.fromApplication`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PasskeyEntryPoint {
    fun preferences(): PasskeyPreferences
    fun authRepository(): AuthRepository
    fun planner(): PasskeyGetPlanner
    fun entries(): PasskeyEntries
}
