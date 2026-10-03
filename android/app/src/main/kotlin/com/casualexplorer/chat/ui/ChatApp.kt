package com.casualexplorer.chat.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

/** The conversation, the start screen. */
@Serializable
data object ChatKey : NavKey

@Serializable
data object SettingsKey : NavKey

/**
 * The app's screens. The back stack survives rotation and the process being
 * stopped; system back (with its predictive animation) pops it.
 *
 * Both ViewModels belong to the activity: there are only two screens, and
 * the chat's state should outlive a visit to Settings.
 */
@Composable
fun ChatApp(
    onDarkThemeChange: (Boolean) -> Unit,
    chatViewModel: ChatViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    // The settings load in moments; nothing is drawn until then, so the
    // first screen can depend on them.
    val settings = (settingsViewModel.uiState.collectAsStateWithLifecycle().value as? SettingsUiState.Loaded)?.settings ?: return
    // Settings opens over the chat until there is a key to use.
    val start = if (settings.hasKey(0) || settings.hasKey(1)) arrayOf<NavKey>(ChatKey) else arrayOf(ChatKey, SettingsKey)
    val backStack = rememberNavBackStack(*start)
    val dark = settings.theme.isDark()
    LaunchedEffect(dark) { onDarkThemeChange(dark) }

    ChatTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Screens(backStack, chatViewModel, settingsViewModel)
        }
    }
}

@Composable
private fun Screens(backStack: NavBackStack<NavKey>, chatViewModel: ChatViewModel, settingsViewModel: SettingsViewModel) {
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<ChatKey> {
                ChatRoute(chatViewModel, onOpenSettings = { backStack.add(SettingsKey) })
            }
            entry<SettingsKey> {
                SettingsRoute(settingsViewModel, onDone = { backStack.removeLastOrNull() })
            }
        },
    )
}
