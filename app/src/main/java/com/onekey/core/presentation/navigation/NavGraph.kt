package com.onekey.core.presentation.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
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
import com.onekey.core.presentation.viewmodel.AppViewModel
import com.onekey.feature.auth.presentation.screen.ChangePasswordScreen
import com.onekey.feature.auth.presentation.screen.LockScreen
import com.onekey.feature.auth.presentation.screen.OnboardingScreen
import com.onekey.feature.auth.presentation.screen.SetupPinScreen
import com.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import com.onekey.feature.settings.presentation.screen.SettingsScreen
import com.onekey.feature.twofa.presentation.screen.TwoFaListScreen
import com.onekey.feature.vault.presentation.screen.CredentialDetailScreen
import com.onekey.feature.vault.presentation.screen.FavouritesScreen
import com.onekey.feature.vault.presentation.screen.TaggedCredentialListScreen
import com.onekey.feature.vault.presentation.screen.TagsBrowseScreen
import com.onekey.feature.vault.presentation.screen.VaultScreen

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Lock : Screen("lock")
    data object Vault : Screen("vault")
    data object Favourites : Screen("favourites")
    data object Tags : Screen("tags")
    data object CredentialDetail : Screen("credential/{credentialId}?initialTag={initialTag}") {
        fun createRoute(id: String?, initialTag: String = "") =
            "credential/${id ?: "new"}?initialTag=${Uri.encode(initialTag)}"
    }
    data object TwoFaList : Screen("two_fa_list")
    data object Settings : Screen("settings")
    data object SetupPin : Screen("setup_pin")
    data object ChangePassword : Screen("change_password")
    data object TaggedList : Screen("tagged/{tagName}") {
        fun createRoute(tagName: String) = "tagged/${Uri.encode(tagName)}"
    }
}

private val BOTTOM_NAV_ROUTES = setOf(
    Screen.Vault.route,
    Screen.Favourites.route,
    Screen.Tags.route,
    Screen.TwoFaList.route,
    Screen.Settings.route,
)

@Composable
fun OneKeyNavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String,
) {
    val appViewModel: AppViewModel = hiltViewModel()
    val isUnlocked by appViewModel.isUnlocked.collectAsStateWithLifecycle()

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

    Scaffold(
        bottomBar = {
            if (showBottomNav) {
                OneKeyBottomNav(
                    currentRoute = currentRoute,
                    onNavigate = { navController.navigateToTab(it) },
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
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
                    onUnlocked = {
                        navController.navigate(Screen.Vault.route) {
                            popUpTo(Screen.Lock.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.Vault.route) {
                VaultScreen(
                    onAddClick = { tag ->
                        navController.navigate(Screen.CredentialDetail.createRoute(null, tag))
                    },
                    onTagClick = { tagName ->
                        navController.navigate(Screen.TaggedList.createRoute(tagName))
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

            composable(Screen.Tags.route) {
                TagsBrowseScreen(
                    onTagClick = { tagName ->
                        navController.navigate(Screen.TaggedList.createRoute(tagName))
                    },
                )
            }

            composable(
                route = Screen.CredentialDetail.route,
                arguments = listOf(
                    navArgument("credentialId") { type = NavType.StringType },
                    navArgument("initialTag") { type = NavType.StringType; defaultValue = "" },
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
                )
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
}

@Composable
private fun OneKeyBottomNav(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == Screen.Vault.route,
            onClick = { onNavigate(Screen.Vault.route) },
            icon = { Icon(Icons.Default.Home, contentDescription = "Vault") },
            label = { Text("Vault") },
        )
        NavigationBarItem(
            selected = currentRoute == Screen.Favourites.route,
            onClick = { onNavigate(Screen.Favourites.route) },
            icon = { Icon(Icons.Default.Favorite, contentDescription = "Favourites") },
            label = { Text("Favourites") },
        )
        NavigationBarItem(
            selected = currentRoute == Screen.Tags.route,
            onClick = { onNavigate(Screen.Tags.route) },
            icon = { Icon(Icons.Default.Label, contentDescription = "Tags") },
            label = { Text("Tags") },
        )
        NavigationBarItem(
            selected = currentRoute == Screen.TwoFaList.route,
            onClick = { onNavigate(Screen.TwoFaList.route) },
            icon = { Icon(Icons.Default.Security, contentDescription = "2FA") },
            label = { Text("2FA") },
        )
        NavigationBarItem(
            selected = currentRoute == Screen.Settings.route,
            onClick = { onNavigate(Screen.Settings.route) },
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
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
