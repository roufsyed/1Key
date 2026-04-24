package com.onekey

import android.app.Application
import com.onekey.core.domain.repository.TagRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OneKeyApp : Application() {
    // Forces TagRepositoryImpl singleton creation at app startup so its
    // hot StateFlow begins collecting before the user reaches the vault screen.
    @Inject lateinit var tagRepository: TagRepository
}
