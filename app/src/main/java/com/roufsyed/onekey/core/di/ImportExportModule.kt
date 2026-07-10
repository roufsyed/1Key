package com.roufsyed.onekey.core.di

import com.roufsyed.onekey.feature.importexport.domain.VaultExporter
import com.roufsyed.onekey.feature.importexport.domain.VaultExporterImpl
import com.roufsyed.onekey.feature.importexport.domain.VaultImporter
import com.roufsyed.onekey.feature.importexport.domain.VaultImporterImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ImportExportModule {

    @Binds
    @Singleton
    abstract fun bindVaultExporter(impl: VaultExporterImpl): VaultExporter

    @Binds
    @Singleton
    abstract fun bindVaultImporter(impl: VaultImporterImpl): VaultImporter
}
