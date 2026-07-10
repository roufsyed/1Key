package com.roufsyed.onekey

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
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
import com.roufsyed.onekey.core.domain.model.isDark
import com.roufsyed.onekey.core.domain.model.isDarkFromConfig
import com.roufsyed.onekey.core.domain.repository.AppPreferencesRepository
import com.roufsyed.onekey.core.domain.repository.AuthRepository
import com.roufsyed.onekey.core.presentation.lockaware.LocalUserActivityPing
import com.roufsyed.onekey.core.presentation.navigation.OneKeyNavGraph
import com.roufsyed.onekey.core.presentation.navigation.Screen
import com.roufsyed.onekey.core.presentation.theme.OneKeyTheme
import com.roufsyed.onekey.core.security.AutoLockManager
import com.roufsyed.onekey.core.security.RootDetector
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

        // Task background colour used by the system when our window is being
        // composed in / out (Recents thumbnail placeholder for FLAG_SECURE
        // tasks, launcher -> app expand animation, and similar). The
        // attribute hierarchy in themes.xml already wires this for static
        // paths; setting it programmatically here makes it explicit for the
        // animation paths where the system reads it from the Activity's
        // TaskDescription. Matches @color/window_background so the placeholder
        // is invisible against the Compose surface that lands on top.
        // TaskDescription.Builder is API 33+; below that we accept the OS default
        // placeholder colour. The earlier theme + windowSplashScreenBackground
        // changes already cover the rotation gap and most warm-restart paths even
        // on older API levels - this Builder path is the belt-and-braces hint
        // specifically for the Recents thumbnail on modern Android.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setTaskDescription(
                ActivityManager.TaskDescription.Builder()
                    .setBackgroundColor(ContextCompat.getColor(this, R.color.window_background))
                    .build()
            )
        }

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

        // Read the user's theme preference synchronously before the first
        // composition. Without this, `collectAsStateWithLifecycle` would render
        // one frame with `initialValue = SYSTEM` (resolved against current
        // system theme) before the real value arrives - producing a flash on
        // every rotation when the user has overridden to LIGHT/DARK. The
        // `prefs` StateFlow in AppPreferencesRepositoryImpl is Eagerly started
        // at app construction (Hilt @Singleton via OneKeyApp field injection),
        // so the cached value is in memory by the time MainActivity.onCreate
        // runs - this first() returns in microseconds on rotation. On the very
        // first app launch it may briefly block while DataStore loads its file,
        // which is acceptable for that one-time cold-start path.
        //
        // The mode is resolved to a Boolean via the current Configuration so
        // SYSTEM-mode users get the right initial value too; the Compose
        // resolver below takes over for reactive system-theme changes.
        val initialThemeMode = runBlocking { appPrefs.getThemeMode().first() }
        val initialDarkTheme = initialThemeMode.isDarkFromConfig(resources.configuration)

        setContent {
            // Read isSetupComplete ONCE for the lifetime of this Activity composition.
            // NavHost.startDestination is one-shot per graph instance, but if we tracked
            // setupComplete reactively the `when` arm would change at the moment setup
            // finishes (false -> true), passing a different `startDestination` to
            // OneKeyNavGraph and remounting the graph - which yanks the user from
            // VaultReadyPage straight to LockScreen and short-circuits the post-setup
            // hand-off. The onboarding flow does its own navigation via onSetupComplete;
            // MainActivity only needs the initial value.
            var initialSetupComplete by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(Unit) {
                initialSetupComplete = authRepository.isSetupComplete().first()
            }

            // Root detection runs off the main thread so cold-start composition isn't
            // blocked by file-existence stats, PackageManager lookups, and the two
            // `getprop` subprocesses inside RootDetector.check() (combined ~150-500ms
            // on a real device). The vault is gated behind the unlock screen anyway,
            // so the brief placeholder shown while the check runs exposes nothing
            // sensitive. The check happens at most once per Activity composition.
            var rootCheck by remember { mutableStateOf<RootDetector.RootCheckResult?>(null) }
            LaunchedEffect(Unit) {
                rootCheck = withContext(Dispatchers.IO) { rootDetector.check() }
            }
            val themeMode by appPrefs.getThemeMode()
                .collectAsStateWithLifecycle(initialValue = initialThemeMode)
            // Compose-side resolver recomposes when the OS flips dark/light so
            // SYSTEM-mode users see the change immediately without restart.
            val isDarkTheme = themeMode.isDark()

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
                    val resolvedRootCheck = rootCheck
                    when {
                        resolvedRootCheck?.isRooted == true -> RootWarningScreen(
                            reason = resolvedRootCheck.reason ?: "Device appears to be rooted"
                        )
                        resolvedRootCheck == null || initialSetupComplete == null -> Box(
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
    com.roufsyed.onekey.core.presentation.lockaware.LockAwareDialog(
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
