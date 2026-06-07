package com.onekey.feature.autofill.service

import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.feature.autofill.domain.AutofillBlocklist
import com.onekey.feature.autofill.domain.DatasetBuilder
import com.onekey.feature.autofill.domain.FieldParser
import com.onekey.feature.autofill.domain.PackageMatcher
import com.onekey.feature.autofill.domain.SaveInfoBuilder
import com.onekey.feature.autofill.domain.StructureWalker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point for [OneKeyAutofillService]. `AutofillService` is not a
 * Hilt-supported component type, so we resolve dependencies manually via
 * `EntryPointAccessors.fromApplication(context, AutofillEntryPoint::class.java)`
 * in `onCreate`. Every dependency listed here is `@Singleton`-scoped - there
 * is no per-request graph; the service is bound by the OS, runs briefly,
 * then unbinds.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutofillEntryPoint {
    fun structureWalker(): StructureWalker
    fun fieldParser(): FieldParser
    fun packageMatcher(): PackageMatcher
    fun datasetBuilder(): DatasetBuilder
    fun saveInfoBuilder(): SaveInfoBuilder
    fun blocklist(): AutofillBlocklist
    fun authRepository(): AuthRepository
    fun credentialRepository(): CredentialRepository
    fun appPreferences(): AppPreferencesRepository
}
