package com.onekey

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.presentation.navigation.OneKeyNavGraph
import com.onekey.core.presentation.navigation.Screen
import com.onekey.core.presentation.theme.OneKeyTheme
import com.onekey.core.security.AutoLockManager
import com.onekey.core.security.RootDetector
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var appPrefs: AppPreferencesRepository
    @Inject lateinit var rootDetector: RootDetector
    @Inject lateinit var autoLockManager: AutoLockManager

    override fun onUserInteraction() {
        super.onUserInteraction()
        autoLockManager.onUserActivity()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        val rootCheck = rootDetector.check()
        val isSetupComplete = runBlocking(Dispatchers.IO) { authRepository.isSetupComplete().first() }
        val initialDarkTheme = runBlocking(Dispatchers.IO) { appPrefs.isDarkTheme().first() }
        val initialScreenshotsEnabled = runBlocking(Dispatchers.IO) { appPrefs.isScreenshotsEnabled().first() }
        if (!initialScreenshotsEnabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // Observe the preference so the flag updates the moment the user toggles it in Settings,
        // without requiring an Activity restart.
        lifecycleScope.launch {
            appPrefs.isScreenshotsEnabled().collect { enabled ->
                if (enabled) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        setContent {
            val isDarkTheme by appPrefs.isDarkTheme()
                .collectAsStateWithLifecycle(initialValue = initialDarkTheme)

            OneKeyTheme(darkTheme = isDarkTheme) {
                if (rootCheck.isRooted) {
                    RootWarningScreen(reason = rootCheck.reason ?: "Device appears to be rooted")
                } else {
                    OneKeyNavGraph(
                        startDestination = when {
                            !isSetupComplete -> Screen.Onboarding.route
                            else -> Screen.Lock.route
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RootWarningScreen(reason: String) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = { androidx.compose.material3.Text("Security Warning") },
        text = {
            androidx.compose.material3.Text(
                "This device may be compromised:\n\n$reason\n\n1Key cannot guarantee the security of your vault on a rooted device. On rooted devices, other apps may be able to read memory or bypass Android's security sandbox, exposing your vault key."
            )
        },
        confirmButton = {},
        dismissButton = {},
    )
}
