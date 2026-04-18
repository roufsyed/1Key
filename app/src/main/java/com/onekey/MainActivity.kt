package com.onekey

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.presentation.navigation.OneKeyNavGraph
import com.onekey.core.presentation.navigation.Screen
import com.onekey.core.presentation.theme.OneKeyTheme
import com.onekey.core.security.RootDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var appPrefs: AppPreferencesRepository
    @Inject lateinit var rootDetector: RootDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        val rootCheck = rootDetector.check()
        val isSetupComplete = runBlocking(Dispatchers.IO) { authRepository.isSetupComplete().first() }
        val initialDarkTheme = runBlocking(Dispatchers.IO) { appPrefs.isDarkTheme().first() }

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
                "This device may be compromised:\n\n$reason\n\n1Key cannot guarantee the security of your vault on a rooted device."
            )
        },
        confirmButton = {},
        dismissButton = {},
    )
}
