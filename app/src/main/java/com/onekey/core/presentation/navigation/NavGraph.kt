package com.onekey.core.presentation.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.onekey.feature.auth.presentation.screen.ChangePasswordScreen
import com.onekey.feature.auth.presentation.screen.LockScreen
import com.onekey.feature.auth.presentation.screen.OnboardingScreen
import com.onekey.feature.auth.presentation.screen.SetupPinScreen
import com.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import com.onekey.feature.settings.presentation.screen.SettingsScreen
import com.onekey.feature.twofa.presentation.screen.TwoFaListScreen
import com.onekey.feature.vault.presentation.screen.CredentialDetailScreen
import com.onekey.feature.vault.presentation.screen.TaggedCredentialListScreen
import com.onekey.feature.vault.presentation.screen.VaultScreen

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Lock : Screen("lock")
    data object Vault : Screen("vault")
    data object CredentialDetail : Screen("credential/{credentialId}") {
        fun createRoute(id: String?) = "credential/${id ?: "new"}"
    }
    data object TwoFaList : Screen("two_fa_list")
    data object Settings : Screen("settings")
    data object SetupPin : Screen("setup_pin")
    data object ChangePassword : Screen("change_password")
    data object TaggedList : Screen("tagged/{tagName}") {
        fun createRoute(tagName: String) = "tagged/${Uri.encode(tagName)}"
    }
}

@Composable
fun OneKeyNavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String,
) {
    NavHost(navController = navController, startDestination = startDestination) {

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
                onAddClick = {
                    navController.navigate(Screen.CredentialDetail.createRoute(null))
                },
                onTwoFaClick = {
                    navController.navigate(Screen.TwoFaList.route)
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                },
                onTagClick = { tagName ->
                    navController.navigate(Screen.TaggedList.createRoute(tagName))
                },
            )
        }

        composable(Screen.CredentialDetail.route) {
            CredentialDetailScreen(
                onBack = { navController.popBackStack() },
                onDeleted = { navController.popBackStack() },
            )
        }

        composable(Screen.TwoFaList.route) {
            TwoFaListScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onSetupPin = { navController.navigate(Screen.SetupPin.route) },
                onChangePassword = { navController.navigate(Screen.ChangePassword.route) },
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
