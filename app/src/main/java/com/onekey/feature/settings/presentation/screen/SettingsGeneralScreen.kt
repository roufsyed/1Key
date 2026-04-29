package com.onekey.feature.settings.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.feature.settings.presentation.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsGeneralScreen(
    onBack: () -> Unit,
    onManageCategories: () -> Unit,
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val isDarkTheme by settingsVm.isDarkTheme.collectAsStateWithLifecycle()
    val isShowFavourites by settingsVm.isShowFavourites.collectAsStateWithLifecycle()
    val isHideTopBarOnScroll by settingsVm.isHideTopBarOnScroll.collectAsStateWithLifecycle()
    val isVaultFooterVisible by settingsVm.isVaultFooterVisible.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("General") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader("Appearance")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ListItem(
                        headlineContent = { Text("Dark theme") },
                        supportingContent = { Text(if (isDarkTheme) "On" else "Off") },
                        leadingContent = {
                            Icon(
                                if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = isDarkTheme,
                                onCheckedChange = { settingsVm.toggleTheme() },
                            )
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text("Show Favourites tab") },
                        supportingContent = {
                            Text(
                                if (isShowFavourites) "Favourites visible in bottom navigation"
                                else "Favourites hidden from bottom navigation"
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.Favorite, contentDescription = null)
                        },
                        trailingContent = {
                            Switch(
                                checked = isShowFavourites,
                                onCheckedChange = { settingsVm.setShowFavourites(it) },
                            )
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text("Hide top bar on scroll") },
                        supportingContent = {
                            Text(
                                if (isHideTopBarOnScroll) "Top bar collapses as you scroll lists"
                                else "Top bar stays pinned while scrolling"
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.UnfoldLess, contentDescription = null)
                        },
                        trailingContent = {
                            Switch(
                                checked = isHideTopBarOnScroll,
                                onCheckedChange = { settingsVm.setHideTopBarOnScroll(it) },
                            )
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text("Show privacy footer") },
                        supportingContent = {
                            Text(
                                if (isVaultFooterVisible)
                                    "\"Your vault is encrypted and stored only on this device.\" appears below the vault list"
                                else
                                    "Footer hidden from the vault list",
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Shield, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = isVaultFooterVisible,
                                onCheckedChange = { settingsVm.setVaultFooterVisible(it) },
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionHeader("Categories")
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Manage categories") },
                    supportingContent = { Text("Add or remove credential categories") },
                    leadingContent = { Icon(Icons.Default.LocalOffer, contentDescription = null) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                    modifier = Modifier.clickable(onClick = onManageCategories),
                )
            }
        }
    }
}
