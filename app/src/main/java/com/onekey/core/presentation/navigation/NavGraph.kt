package com.onekey.core.presentation.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.onekey.core.domain.model.CredentialType
import com.onekey.core.presentation.animation.UnlockOverlay
import com.onekey.core.presentation.viewmodel.AppViewModel
import com.onekey.feature.auth.presentation.screen.ChangePasswordScreen
import com.onekey.feature.auth.presentation.screen.LockScreen
import com.onekey.feature.auth.presentation.screen.OnboardingScreen
import com.onekey.feature.auth.presentation.screen.SetupPinScreen
import com.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import com.onekey.feature.importexport.presentation.screen.BackupScreen
import com.onekey.feature.settings.presentation.screen.SettingsScreen
import com.onekey.feature.twofa.presentation.screen.QrScannerScreen
import com.onekey.feature.twofa.presentation.screen.TwoFaListScreen
import com.onekey.feature.vault.presentation.screen.CredentialDetailScreen
import com.onekey.feature.vault.presentation.screen.FavouritesScreen
import com.onekey.feature.vault.presentation.screen.TaggedCredentialListScreen
import com.onekey.feature.vault.presentation.screen.VaultScreen

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Lock : Screen("lock")
    data object Vault : Screen("vault")
    data object Favourites : Screen("favourites")
    data object CredentialDetail : Screen("credential/{credentialId}?initialTag={initialTag}&initialType={initialType}") {
        fun createRoute(id: String?, initialTag: String = "", initialType: String = "") =
            "credential/${id ?: "new"}?initialTag=${Uri.encode(initialTag)}&initialType=${Uri.encode(initialType)}"
    }
    data object TwoFaList : Screen("two_fa_list")
    data object Settings : Screen("settings")
    data object SetupPin : Screen("setup_pin")
    data object ChangePassword : Screen("change_password")
    data object TaggedList : Screen("tagged/{tagName}") {
        fun createRoute(tagName: String) = "tagged/${Uri.encode(tagName)}"
    }
    data object QrScanner : Screen("qr_scanner")
    data object Backup : Screen("backup")
}

private val BOTTOM_NAV_ROUTES = setOf(
    Screen.Vault.route,
    Screen.Favourites.route,
    Screen.TwoFaList.route,
    Screen.Settings.route,
)

private const val NAV_TRANSITION_MS = 280

@Composable
fun OneKeyNavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String,
) {
    val appViewModel: AppViewModel = hiltViewModel()
    val isUnlocked by appViewModel.isUnlocked.collectAsStateWithLifecycle()
    val isShowFavourites by appViewModel.isShowFavourites.collectAsStateWithLifecycle()

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val showBottomNav = currentRoute in BOTTOM_NAV_ROUTES

    // Navigate to Lock whenever the vault locks mid-session.
    // Skip if already on Lock or Onboarding to avoid redundant navigation.
    LaunchedEffect(isUnlocked) {
        if (!isUnlocked &&
            currentRoute != null &&
            currentRoute != Screen.Lock.route &&
            currentRoute != Screen.Onboarding.route
        ) {
            navController.navigate(Screen.Lock.route) {
                popUpTo(navController.graph.id) { inclusive = false }
            }
        }
    }

    // If Favourites is hidden while the user is on that tab, bounce them to Vault.
    LaunchedEffect(isShowFavourites) {
        if (!isShowFavourites && currentRoute == Screen.Favourites.route) {
            navController.navigate(Screen.Vault.route) {
                popUpTo(Screen.Vault.route) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    // The outer Box hosts an UnlockOverlay sibling above the Scaffold. Keeping the morph at
    // app root means the bottom-nav appearing and the NavHost cross-fade both happen
    // *under* the curtain, eliminating the layout pop the user would otherwise see when
    // LockScreen → Vault navigation flips showBottomNav to true.
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                AnimatedVisibility(
                    visible = showBottomNav,
                    enter = slideInVertically(
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                        initialOffsetY = { it },
                    ) + fadeIn(tween(180)),
                    exit = slideOutVertically(
                        animationSpec = tween(220, easing = FastOutLinearInEasing),
                        targetOffsetY = { it },
                    ) + fadeOut(tween(160)),
                ) {
                    OneKeyBottomNav(
                        currentRoute = currentRoute,
                        showFavourites = isShowFavourites,
                        onNavigate = { navController.navigateToTab(it) },
                    )
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                enterTransition = { fadeIn(tween(NAV_TRANSITION_MS)) },
                exitTransition = { fadeOut(tween(NAV_TRANSITION_MS)) },
                popEnterTransition = { fadeIn(tween(NAV_TRANSITION_MS)) },
                popExitTransition = { fadeOut(tween(NAV_TRANSITION_MS)) },
            ) {

                composable(Screen.Onboarding.route) {
                    val vm = hiltViewModel<AuthViewModel>()
                    OnboardingScreen(
                        viewModel = vm,
                        onSetupComplete = {
                            navController.navigate(Screen.Vault.route) {
                                popUpTo(Screen.Onboarding.route) { inclusive = true }
                            }
                        }
                    )
                }

                composable(Screen.Lock.route) {
                    val vm = hiltViewModel<AuthViewModel>()
                    LockScreen(
                        viewModel = vm,
                        appViewModel = appViewModel,
                        onUnlocked = {
                            navController.navigate(Screen.Vault.route) {
                                popUpTo(Screen.Lock.route) { inclusive = true }
                            }
                        }
                    )
                }

                composable(Screen.Vault.route) {
                    VaultScreen(
                        onAddClick = { type ->
                            // Auto-apply matching tag (Bank Account, Login, …) so the new
                            // credential lands in the user's existing tag-based grouping.
                            // OTHER stays untagged.
                            val initialTag = if (type == CredentialType.OTHER) "" else type.displayName
                            navController.navigate(
                                Screen.CredentialDetail.createRoute(null, initialTag, type.name)
                            )
                        },
                        onTagClick = { tagName ->
                            navController.navigate(Screen.TaggedList.createRoute(tagName))
                        },
                        onCredentialClick = { id ->
                            navController.navigate(Screen.CredentialDetail.createRoute(id))
                        },
                    )
                }

                composable(Screen.Favourites.route) {
                    FavouritesScreen(
                        onCredentialClick = { id ->
                            navController.navigate(Screen.CredentialDetail.createRoute(id))
                        },
                    )
                }

                composable(
                    route = Screen.CredentialDetail.route,
                    arguments = listOf(
                        navArgument("credentialId") { type = NavType.StringType },
                        navArgument("initialTag") { type = NavType.StringType; defaultValue = "" },
                        navArgument("initialType") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) {
                    CredentialDetailScreen(
                        onBack = { navController.popBackStack() },
                        onDeleted = { navController.popBackStack() },
                    )
                }

                composable(Screen.TwoFaList.route) {
                    TwoFaListScreen(
                        onBack = { navController.popBackStack() },
                        showBack = false,
                        onScanQr = { navController.navigate(Screen.QrScanner.route) },
                    )
                }

                composable(Screen.QrScanner.route) {
                    QrScannerScreen(
                        onBack = { navController.popBackStack() },
                        onSaved = { navController.popBackStack() },
                    )
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        showBack = false,
                        onSetupPin = { navController.navigate(Screen.SetupPin.route) },
                        onChangePassword = { navController.navigate(Screen.ChangePassword.route) },
                        onVaultReset = {
                            navController.navigate(Screen.Onboarding.route) {
                                popUpTo(navController.graph.id) { inclusive = true }
                            }
                        },
                        onBackup = { navController.navigate(Screen.Backup.route) },
                    )
                }

                composable(Screen.Backup.route) {
                    BackupScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.SetupPin.route) {
                    val vm = hiltViewModel<AuthViewModel>()
                    SetupPinScreen(
                        viewModel = vm,
                        onPinSet = { navController.popBackStack() },
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Screen.ChangePassword.route) {
                    ChangePasswordScreen(
                        onBack = { navController.popBackStack() },
                        onSuccess = { navController.popBackStack() },
                    )
                }

                composable(
                    route = Screen.TaggedList.route,
                    arguments = listOf(navArgument("tagName") { type = NavType.StringType }),
                ) {
                    TaggedCredentialListScreen(
                        onBack = { navController.popBackStack() },
                        onCredentialClick = { id ->
                            navController.navigate(Screen.CredentialDetail.createRoute(id))
                        },
                    )
                }
            }
        }

        UnlockOverlay(appViewModel = appViewModel)
    }
}

@Composable
private fun OneKeyBottomNav(
    currentRoute: String?,
    showFavourites: Boolean,
    onNavigate: (String) -> Unit,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    NavigationBar(
        modifier = Modifier.height(64.dp + bottomInset),
        windowInsets = WindowInsets(bottom = bottomInset),
    ) {
        NavigationBarItem(
            selected = currentRoute == Screen.Vault.route,
            onClick = { onNavigate(Screen.Vault.route) },
            icon = { Icon(Icons.Default.Home, contentDescription = "Vault") },
            label = { Text("Vault") },
            alwaysShowLabel = false,
        )
        if (showFavourites) {
            NavigationBarItem(
                selected = currentRoute == Screen.Favourites.route,
                onClick = { onNavigate(Screen.Favourites.route) },
                icon = { Icon(Icons.Default.Favorite, contentDescription = "Favourites") },
                label = { Text("Favourites") },
                alwaysShowLabel = false,
            )
        }
        NavigationBarItem(
            selected = currentRoute == Screen.TwoFaList.route,
            onClick = { onNavigate(Screen.TwoFaList.route) },
            icon = { Icon(Icons.Default.Security, contentDescription = "2FA") },
            label = { Text("2FA") },
            alwaysShowLabel = false,
        )
        NavigationBarItem(
            selected = currentRoute == Screen.Settings.route,
            onClick = { onNavigate(Screen.Settings.route) },
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
            alwaysShowLabel = false,
        )
    }
}

// Pop up to Vault so tabs never build an unbounded back stack.
// saveState/restoreState preserves per-tab scroll position across tab switches.
private fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(Screen.Vault.route) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
