package com.roufsyed.onekey.core.di

import android.content.Context
import com.roufsyed.onekey.core.security.RootDetector
import com.roufsyed.onekey.core.security.SecureClipboardManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun provideRootDetector(@ApplicationContext context: Context): RootDetector =
        RootDetector(context)

    @Provides
    @Singleton
    fun provideSecureClipboard(@ApplicationContext context: Context): SecureClipboardManager =
        SecureClipboardManager(context)
}
