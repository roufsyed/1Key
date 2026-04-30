package com.onekey

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch
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

        // Default to FLAG_SECURE until the screenshots preference hydrates from DataStore.
        // Erring on "blocked" during the brief startup window is safer for a password
        // manager — the observer below clears the flag if the user has enabled screenshots.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        lifecycleScope.launch {
            appPrefs.isScreenshotsEnabled().collect { enabled ->
                if (enabled) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        setContent {
            // isSetupComplete drives startDestination, which NavHost reads only once.
            // Hold the nav graph until DataStore hydrates so cold start can never briefly
            // mount the wrong root (e.g. Lock when the user hasn't onboarded yet).
            val isSetupComplete by authRepository.isSetupComplete()
                .collectAsStateWithLifecycle(initialValue = null)
            val isDarkTheme by appPrefs.isDarkTheme()
                .collectAsStateWithLifecycle(initialValue = false)

            OneKeyTheme(darkTheme = isDarkTheme) {
                when {
                    rootCheck.isRooted -> RootWarningScreen(
                        reason = rootCheck.reason ?: "Device appears to be rooted"
                    )
                    isSetupComplete == null -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    )
                    else -> OneKeyNavGraph(
                        startDestination = if (isSetupComplete == true) Screen.Lock.route
                        else Screen.Onboarding.route,
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
