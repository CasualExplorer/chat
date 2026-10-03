package com.casualexplorer.chat.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TextButton
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
import com.casualexplorer.chat.core.EFFORTS
import com.casualexplorer.chat.core.OPENAI_BASE_URL
import com.casualexplorer.chat.core.formatEffort
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
            state.settings,
            onSave = {
                vm.save(it)
                onDone()
            },
            onBack = onDone,
        )
    }
}

/**
 * API keys, stored encrypted on the device, the theme, an optional server
 * for each API, and the defaults the terminal app takes as flags: which
 * provider to start with, each provider's model and reasoning effort. Keys,
 * servers and the theme apply at once; the defaults when the app next starts.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(settings: UserSettings, onSave: (UserSettings) -> Unit, onBack: () -> Unit) {
    var anthropicKey by rememberSaveable { mutableStateOf(settings.anthropicKey) }
    var openaiKey by rememberSaveable { mutableStateOf(settings.openaiKey) }
    var showKeys by rememberSaveable { mutableStateOf(false) }
    var theme by rememberSaveable { mutableStateOf(settings.theme) }
    var dynamicColor by rememberSaveable { mutableStateOf(settings.dynamicColor) }
    var anthropicBaseUrl by rememberSaveable { mutableStateOf(settings.anthropicBaseUrl) }
    var openaiBaseUrl by rememberSaveable { mutableStateOf(settings.openaiBaseUrl) }
    var startProvider by rememberSaveable { mutableStateOf(settings.startProvider) }
    var anthropicModel by rememberSaveable { mutableStateOf(settings.anthropicModel) }
    var openaiModel by rememberSaveable { mutableStateOf(settings.openaiModel) }
    var anthropicEffort by rememberSaveable { mutableStateOf(settings.anthropicEffort) }
    var openaiEffort by rememberSaveable { mutableStateOf(settings.openaiEffort) }
    val anthropicUrlValid = normalizeBaseUrl(anthropicBaseUrl) != null
    val openaiUrlValid = normalizeBaseUrl(openaiBaseUrl) != null

    fun save() {
        if (!anthropicUrlValid || !openaiUrlValid) return
        onSave(
            UserSettings(
                anthropicKey = anthropicKey,
                openaiKey = openaiKey,
                anthropicBaseUrl = anthropicBaseUrl,
                openaiBaseUrl = openaiBaseUrl,
                startProvider = startProvider,
                anthropicModel = anthropicModel.ifBlank { UserSettings.DEFAULT_ANTHROPIC_MODEL },
                openaiModel = openaiModel.ifBlank { UserSettings.DEFAULT_OPENAI_MODEL },
                anthropicEffort = anthropicEffort,
                openaiEffort = openaiEffort,
                theme = theme,
                dynamicColor = dynamicColor,
            ),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = ::save, enabled = anthropicUrlValid && openaiUrlValid) {
                        Text(stringResource(R.string.save))
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
            SecretField(stringResource(R.string.anthropic_key), anthropicKey, showKeys) { anthropicKey = it }
            SecretField(stringResource(R.string.openai_key), openaiKey, showKeys) { openaiKey = it }
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
                        selected = theme == mode,
                        onClick = { theme = mode },
                        shape = SegmentedButtonDefaults.itemShape(i, themes.size),
                    ) { Text(label) }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(stringResource(R.string.dynamic_color), stringResource(R.string.dynamic_color_note), dynamicColor) {
                    dynamicColor = it
                }
            }

            HorizontalDivider()
            Section(stringResource(R.string.servers))
            Note(stringResource(R.string.servers_note))
            UrlField(stringResource(R.string.anthropic_server), anthropicBaseUrl, ANTHROPIC_BASE_URL, anthropicUrlValid) { anthropicBaseUrl = it }
            UrlField(stringResource(R.string.openai_server), openaiBaseUrl, OPENAI_BASE_URL, openaiUrlValid) { openaiBaseUrl = it }

            HorizontalDivider()
            Section(stringResource(R.string.defaults))
            Note(stringResource(R.string.defaults_note))
            Text(stringResource(R.string.start_with), style = MaterialTheme.typography.labelLarge)
            val starts = listOf(UserSettings.START_ANTHROPIC to "Anthropic", UserSettings.START_OPENAI to "OpenAI")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                starts.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = startProvider == value,
                        onClick = { startProvider = value },
                        shape = SegmentedButtonDefaults.itemShape(i, starts.size),
                    ) { Text(label) }
                }
            }
            PlainField(stringResource(R.string.anthropic_model), anthropicModel) { anthropicModel = it }
            EffortRow(stringResource(R.string.anthropic_effort), anthropicEffort) { anthropicEffort = it }
            PlainField(stringResource(R.string.openai_model), openaiModel) { openaiModel = it }
            EffortRow(stringResource(R.string.openai_effort), openaiEffort) { openaiEffort = it }

            HorizontalDivider()
            Note(stringResource(R.string.privacy_note))
            Button(onClick = ::save, enabled = anthropicUrlValid && openaiUrlValid, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.save))
            }
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
private fun PlainField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun UrlField(label: String, value: String, placeholder: String, valid: Boolean, onChange: (String) -> Unit) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffortRow(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (effort in EFFORTS) {
                FilterChip(selected = value == effort, onClick = { onChange(effort) }, label = { Text(formatEffort(effort)) })
            }
        }
    }
}
