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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.presentation.lockaware.LocalUserActivityPing
import com.onekey.core.presentation.navigation.OneKeyNavGraph
import com.onekey.core.presentation.navigation.Screen
import com.onekey.core.presentation.theme.OneKeyTheme
import com.onekey.core.security.AutoLockManager
import com.onekey.core.security.RootDetector
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
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
        // manager - the observer below clears the flag if the user has enabled screenshots.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        lifecycleScope.launch {
            appPrefs.isScreenshotsEnabled().collect { enabled ->
                if (enabled) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        setContent {
            // Read isSetupComplete ONCE for the lifetime of this Activity composition.
            // NavHost.startDestination is one-shot per graph instance, but if we tracked
            // setupComplete reactively the `when` arm would change at the moment setup
            // finishes (false → true), passing a different `startDestination` to
            // OneKeyNavGraph and remounting the graph - which yanks the user from
            // VaultReadyPage straight to LockScreen and short-circuits the post-setup
            // hand-off. The onboarding flow does its own navigation via onSetupComplete;
            // MainActivity only needs the initial value.
            var initialSetupComplete by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(Unit) {
                initialSetupComplete = authRepository.isSetupComplete().first()
            }
            val isDarkTheme by appPrefs.isDarkTheme()
                .collectAsStateWithLifecycle(initialValue = false)

            OneKeyTheme(darkTheme = isDarkTheme) {
                // Provide the user-activity ping at the composition root so every
                // dialog / sheet / popup downstream - even ones rendered in their
                // own Window - can reset the inactivity timer. Without this, the
                // M3 dialog/sheet/popup family is blind to the timer, since their
                // touches never reach Activity.onUserInteraction().
                //
                // `remember` keeps the lambda's identity stable across
                // recompositions; `staticCompositionLocalOf` would otherwise
                // invalidate the entire subtree on every frame because each
                // recomposition would manufacture a fresh lambda instance.
                val userActivityPing = remember(autoLockManager) {
                    { autoLockManager.onUserActivity() }
                }
                CompositionLocalProvider(LocalUserActivityPing provides userActivityPing) {
                    when {
                        rootCheck.isRooted -> RootWarningScreen(
                            reason = rootCheck.reason ?: "Device appears to be rooted"
                        )
                        initialSetupComplete == null -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                        )
                        else -> OneKeyNavGraph(
                            startDestination = if (initialSetupComplete == true) Screen.Lock.route
                            else Screen.Onboarding.route,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RootWarningScreen(reason: String) {
    com.onekey.core.presentation.lockaware.LockAwareDialog(
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
