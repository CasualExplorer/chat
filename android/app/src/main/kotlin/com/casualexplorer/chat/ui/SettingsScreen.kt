package com.casualexplorer.chat.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.casualexplorer.chat.R
import com.casualexplorer.chat.core.ANTHROPIC_BASE_URL
import com.casualexplorer.chat.core.OPENAI_BASE_URL
import com.casualexplorer.chat.data.ThemeMode
import com.casualexplorer.chat.data.UserSettings
import com.casualexplorer.chat.data.normalizeBaseUrl

/** The settings screen, connected to [vm]. [onDone] leaves it. */
@Composable
fun SettingsRoute(vm: SettingsViewModel, onDone: () -> Unit) {
    when (val state = vm.uiState.collectAsStateWithLifecycle().value) {
        // The settings load in moments; the screen waits for them.
        SettingsUiState.Loading -> Box(Modifier.fillMaxSize())
        is SettingsUiState.Loaded -> SettingsScreen(
            settings = state.settings,
            text = vm.text,
            onApiKeyChange = vm::setApiKey,
            onBaseUrlChange = vm::setBaseUrl,
            onThemeChange = vm::setTheme,
            onDynamicColorChange = vm::setDynamicColor,
            onBack = onDone,
        )
    }
}

/**
 * API keys, stored encrypted on the device, the theme, and an optional
 * server for each API. Every change is saved and applies at once, as in Now
 * in Android's settings; there is nothing to confirm. The model, provider
 * and reasoning effort are picked in the chat, which remembers them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: UserSettings,
    text: SettingsText,
    onApiKeyChange: (provider: Int, key: String) -> Unit,
    onBaseUrlChange: (provider: Int, url: String) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var showKeys by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section(stringResource(R.string.api_keys))
            Note(stringResource(R.string.api_keys_note))
            SecretField(stringResource(R.string.anthropic_key), text.anthropicKey, showKeys) { onApiKeyChange(0, it) }
            SecretField(stringResource(R.string.openai_key), text.openaiKey, showKeys) { onApiKeyChange(1, it) }
            SwitchRow(stringResource(R.string.show_keys), null, showKeys) { showKeys = it }

            HorizontalDivider()
            Section(stringResource(R.string.appearance))
            val themes = listOf(
                ThemeMode.System to stringResource(R.string.theme_system),
                ThemeMode.Light to stringResource(R.string.theme_light),
                ThemeMode.Dark to stringResource(R.string.theme_dark),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                themes.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = settings.theme == mode,
                        onClick = { onThemeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, themes.size),
                    ) { Text(label) }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(
                    stringResource(R.string.dynamic_color),
                    stringResource(R.string.dynamic_color_note),
                    settings.dynamicColor,
                    onDynamicColorChange,
                )
            }

            HorizontalDivider()
            Section(stringResource(R.string.servers))
            Note(stringResource(R.string.servers_note))
            UrlField(stringResource(R.string.anthropic_server), text.anthropicBaseUrl, ANTHROPIC_BASE_URL) { onBaseUrlChange(0, it) }
            UrlField(stringResource(R.string.openai_server), text.openaiBaseUrl, OPENAI_BASE_URL) { onBaseUrlChange(1, it) }

            HorizontalDivider()
            Note(stringResource(R.string.privacy_note))
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Section(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
)

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A row with a switch, all of it toggling. */
@Composable
private fun SwitchRow(title: String, note: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val supporting: (@Composable () -> Unit)? = if (note == null) null else { { Text(note) } }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting,
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun SecretField(label: String, value: String, show: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun UrlField(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    // An invalid address isn't saved; the field says why until it is fixed.
    val valid = normalizeBaseUrl(value) != null
    val error: (@Composable () -> Unit)? = if (valid) {
        null
    } else {
        { Text(stringResource(R.string.server_invalid)) }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        isError = !valid,
        supportingText = error,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}
