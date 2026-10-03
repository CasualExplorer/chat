package com.casualexplorer.chat.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.casualexplorer.chat.MainActivityUiState
import com.casualexplorer.chat.MainActivityViewModel
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
 * Each screen's ViewModel belongs to its back stack entry, as in Now in
 * Android: the chat's stays while Settings is open over it, and Settings'
 * is cleared when it closes.
 */
@Composable
fun ChatApp(viewModel: MainActivityViewModel, onDarkThemeChange: (Boolean) -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // The settings load in moments, behind the launch screen; nothing is
    // drawn until then, so the first screen can depend on them.
    val settings = (uiState as? MainActivityUiState.Success)?.settings ?: return
    // Settings opens over the chat until there is a key to use.
    val start = if (settings.hasAnyKey) arrayOf<NavKey>(ChatKey) else arrayOf(ChatKey, SettingsKey)
    val backStack = rememberNavBackStack(*start)
    val dark = settings.theme.isDark()
    LaunchedEffect(dark) { onDarkThemeChange(dark) }

    ChatTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Screens(backStack)
        }
    }
}

@Composable
private fun Screens(backStack: NavBackStack<NavKey>) {
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<ChatKey> {
                ChatRoute(onOpenSettings = { backStack.add(SettingsKey) })
            }
            entry<SettingsKey> {
                SettingsRoute(onDone = { backStack.removeLastOrNull() })
            }
        },
    )
}
