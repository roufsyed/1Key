package com.onekey.feature.settings.presentation.viewmodel

data class SettingsEntry(
    val title: String,
    val subtitle: String,
    val sectionLabel: String,
    val keywords: List<String> = emptyList(),
    val action: SettingsAction,
    val highlightKey: String? = null,
)

sealed class SettingsAction {
    data class Navigate(val destination: SettingsDestination) : SettingsAction()
    data class OpenDialogOn(val dialogId: SettingsDialogId) : SettingsAction()
}

enum class SettingsDestination {
    General, Security, Backup, Faq, PrivacyPolicy, SetupPin, ChangePassword
}

// Only dialogs whose state lives in SettingsScreen itself.
// Dialogs owned by sub-screens (biometric, screenshots, lock timers, etc.) are reached
// by navigating to their sub-screen — lifting them here would require invasive API changes.
enum class SettingsDialogId { DeleteVault }

// Stable string keys used by sub-screens to identify which row to scroll-to and highlight
// when the user arrives via a search result. Defined here so the index and the sub-screens
// share the same constants without magic strings.
//
// Why every search entry navigates (no in-place toggle): tapping a search result for
// "dark mode" should open the General screen with that row pulsed, not silently flip the
// theme. The latter is surprising — users expect search to reveal where a setting lives,
// not to actuate it. So all toggles now go through Navigate + a highlight key.
object SettingsHighlightKeys {
    const val DARK_THEME = "dark_theme"
    const val SHOW_FAVOURITES = "show_favourites"
    const val HIDE_TOP_BAR_ON_SCROLL = "hide_top_bar_on_scroll"
    const val VAULT_FOOTER = "vault_footer"
    const val RECYCLE_BIN = "recycle_bin"
    const val RECYCLE_BIN_RETENTION = "recycle_bin_retention"
    const val MANAGE_CATEGORIES = "manage_categories"
    const val PIN_SETUP = "pin_setup"
    const val BIOMETRIC_UNLOCK = "biometric_unlock"
    const val REMOVE_PIN = "remove_pin"
    const val BACKGROUND_LOCK = "background_lock"
    const val INACTIVITY_LOCK = "inactivity_lock"
    const val RESTORE_LAST_SCREEN = "restore_last_screen"
    const val MASTER_PASSWORD_RECHECK = "master_password_recheck"
    const val ALLOW_SCREENSHOTS = "allow_screenshots"
}

internal fun buildSettingsIndex(): List<SettingsEntry> = listOf(
    // ── General ──────────────────────────────────────────────────────────────
    SettingsEntry(
        title = "General",
        subtitle = "Theme, layout, categories",
        sectionLabel = "General",
        keywords = listOf("appearance", "display", "layout"),
        action = SettingsAction.Navigate(SettingsDestination.General),
        // No highlightKey — navigates to the screen overview, no specific row.
    ),
    SettingsEntry(
        title = "Dark theme",
        subtitle = "Toggle between light and dark mode",
        sectionLabel = "General",
        keywords = listOf("night", "appearance", "colour", "color", "dark mode"),
        action = SettingsAction.Navigate(SettingsDestination.General),
        highlightKey = SettingsHighlightKeys.DARK_THEME,
    ),
    SettingsEntry(
        title = "Show Favourites tab",
        subtitle = "Show or hide Favourites in bottom navigation",
        sectionLabel = "General",
        keywords = listOf("favorites", "nav", "bottom bar", "starred"),
        action = SettingsAction.Navigate(SettingsDestination.General),
        highlightKey = SettingsHighlightKeys.SHOW_FAVOURITES,
    ),
    SettingsEntry(
        title = "Hide top bar on scroll",
        subtitle = "Collapse the top bar as you scroll lists",
        sectionLabel = "General",
        keywords = listOf("toolbar", "appbar", "collapse", "scroll"),
        action = SettingsAction.Navigate(SettingsDestination.General),
        highlightKey = SettingsHighlightKeys.HIDE_TOP_BAR_ON_SCROLL,
    ),
    SettingsEntry(
        title = "Show privacy footer",
        subtitle = "Show the encrypted-on-device footer in the vault list",
        sectionLabel = "General",
        keywords = listOf("footer", "encryption notice", "vault"),
        action = SettingsAction.Navigate(SettingsDestination.General),
        highlightKey = SettingsHighlightKeys.VAULT_FOOTER,
    ),
    SettingsEntry(
        title = "Recycle bin",
        subtitle = "Deleted credentials wait in the bin so you can restore them",
        sectionLabel = "General",
        keywords = listOf("trash", "delete", "restore", "recovery", "undo"),
        action = SettingsAction.Navigate(SettingsDestination.General),
        highlightKey = SettingsHighlightKeys.RECYCLE_BIN,
    ),
    SettingsEntry(
        title = "Auto-clear recycle bin",
        subtitle = "How long deleted credentials wait before permanent removal",
        sectionLabel = "General",
        keywords = listOf("trash", "retention", "purge", "30 days"),
        action = SettingsAction.Navigate(SettingsDestination.General),
        highlightKey = SettingsHighlightKeys.RECYCLE_BIN_RETENTION,
    ),
    SettingsEntry(
        title = "Manage categories",
        subtitle = "Add or remove credential categories",
        sectionLabel = "General",
        keywords = listOf("tags", "labels", "organize", "folder"),
        action = SettingsAction.Navigate(SettingsDestination.General),
        highlightKey = SettingsHighlightKeys.MANAGE_CATEGORIES,
    ),

    // ── Security ─────────────────────────────────────────────────────────────
    SettingsEntry(
        title = "Security",
        subtitle = "Unlock methods, auto-lock, master password",
        sectionLabel = "Security",
        keywords = listOf("password", "pin", "biometric", "lock", "fingerprint"),
        action = SettingsAction.Navigate(SettingsDestination.Security),
        // No highlightKey — navigates to the screen overview, no specific row.
    ),
    SettingsEntry(
        title = "Setup / Change PIN",
        subtitle = "Faster unlock with a 6-digit PIN",
        sectionLabel = "Security",
        keywords = listOf("pin", "code", "passcode", "six digit"),
        action = SettingsAction.Navigate(SettingsDestination.SetupPin),
        highlightKey = SettingsHighlightKeys.PIN_SETUP,
    ),
    SettingsEntry(
        title = "Biometric Unlock",
        subtitle = "Unlock with fingerprint or face recognition",
        sectionLabel = "Security",
        keywords = listOf("fingerprint", "face", "touch id", "face id", "biometrics"),
        action = SettingsAction.Navigate(SettingsDestination.Security),
        highlightKey = SettingsHighlightKeys.BIOMETRIC_UNLOCK,
    ),
    SettingsEntry(
        title = "Remove PIN",
        subtitle = "Stop using a PIN — only master password will unlock 1Key",
        sectionLabel = "Security",
        keywords = listOf("delete pin", "disable pin", "unset pin"),
        action = SettingsAction.Navigate(SettingsDestination.Security),
        highlightKey = SettingsHighlightKeys.REMOVE_PIN,
    ),
    SettingsEntry(
        title = "Change Master Password",
        subtitle = "Update your vault master password",
        sectionLabel = "Security",
        keywords = listOf("update password", "reset password", "new password"),
        action = SettingsAction.Navigate(SettingsDestination.ChangePassword),
        // No highlightKey — navigates to a standalone screen, not a row in Security.
    ),
    SettingsEntry(
        title = "Lock when app in background",
        subtitle = "How quickly the vault locks after you leave the app",
        sectionLabel = "Security",
        keywords = listOf("auto lock", "background", "timeout", "timer"),
        action = SettingsAction.Navigate(SettingsDestination.Security),
        highlightKey = SettingsHighlightKeys.BACKGROUND_LOCK,
    ),
    SettingsEntry(
        title = "Lock after inactivity",
        subtitle = "How long the vault stays unlocked while idle",
        sectionLabel = "Security",
        keywords = listOf("idle", "inactivity", "timeout", "auto lock", "timer"),
        action = SettingsAction.Navigate(SettingsDestination.Security),
        highlightKey = SettingsHighlightKeys.INACTIVITY_LOCK,
    ),
    SettingsEntry(
        title = "Pick up where you left off",
        subtitle = "Restore your last screen after auto-lock",
        sectionLabel = "Security",
        keywords = listOf("restore screen", "resume", "navigation", "last screen"),
        action = SettingsAction.Navigate(SettingsDestination.Security),
        highlightKey = SettingsHighlightKeys.RESTORE_LAST_SCREEN,
    ),
    SettingsEntry(
        title = "Periodic master password check",
        subtitle = "Require master password at a set interval even when using PIN or biometric",
        sectionLabel = "Security",
        keywords = listOf("recheck", "re-enter", "interval", "biometric interval", "pin interval"),
        action = SettingsAction.Navigate(SettingsDestination.Security),
        highlightKey = SettingsHighlightKeys.MASTER_PASSWORD_RECHECK,
    ),
    SettingsEntry(
        title = "Allow Screenshots",
        subtitle = "Enable or disable screen capture and Recent Apps preview",
        sectionLabel = "Security",
        keywords = listOf("screenshot", "screen record", "recent apps", "capture"),
        action = SettingsAction.Navigate(SettingsDestination.Security),
        highlightKey = SettingsHighlightKeys.ALLOW_SCREENSHOTS,
    ),

    // ── Backup ───────────────────────────────────────────────────────────────
    SettingsEntry(
        title = "Backup & Import",
        subtitle = "Export your vault or import from another app",
        sectionLabel = "Backup",
        keywords = listOf("export", "backup", "import", "transfer", "migrate", "csv", "json"),
        action = SettingsAction.Navigate(SettingsDestination.Backup),
    ),

    // ── Help ─────────────────────────────────────────────────────────────────
    SettingsEntry(
        title = "FAQ",
        subtitle = "How encryption, privacy, and security work in 1Key",
        sectionLabel = "Help",
        keywords = listOf("help", "questions", "how", "encryption", "guide"),
        action = SettingsAction.Navigate(SettingsDestination.Faq),
    ),
    SettingsEntry(
        title = "Privacy Policy",
        subtitle = "What we collect, what we don't, and why",
        sectionLabel = "Help",
        keywords = listOf("privacy", "data", "gdpr", "collect"),
        action = SettingsAction.Navigate(SettingsDestination.PrivacyPolicy),
    ),

    // ── Danger Zone ──────────────────────────────────────────────────────────
    SettingsEntry(
        title = "Delete Vault",
        subtitle = "Remove all credentials while keeping your account active",
        sectionLabel = "Danger",
        keywords = listOf("wipe", "reset", "erase", "clear vault", "remove all"),
        action = SettingsAction.OpenDialogOn(SettingsDialogId.DeleteVault),
    ),
)
