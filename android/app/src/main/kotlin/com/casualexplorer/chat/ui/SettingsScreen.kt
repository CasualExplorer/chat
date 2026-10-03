package com.casualexplorer.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.casualexplorer.chat.core.ANTHROPIC_BASE_URL
import com.casualexplorer.chat.core.EFFORTS
import com.casualexplorer.chat.core.OPENAI_BASE_URL
import com.casualexplorer.chat.core.formatEffort
import com.casualexplorer.chat.data.UserSettings
import com.casualexplorer.chat.data.normalizeBaseUrl

private val body = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 20.sp)
private val small = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp)

/** The settings screen, connected to [vm]. [onDone] leaves it. */
@Composable
fun SettingsRoute(vm: SettingsViewModel, onDone: () -> Unit) {
    when (val state = vm.uiState.collectAsStateWithLifecycle().value) {
        // The settings load in moments; the screen waits for them.
        SettingsUiState.Loading -> Box(Modifier.fillMaxSize().background(Palette.BgBase))
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
 * API keys, stored encrypted on the device, an optional server for each API,
 * and the defaults the terminal app takes as flags: which provider to start
 * with, each provider's model and reasoning effort. Keys and servers apply
 * at once; the defaults when the app next starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: UserSettings, onSave: (UserSettings) -> Unit, onBack: () -> Unit) {
    var anthropicKey by rememberSaveable { mutableStateOf(settings.anthropicKey) }
    var openaiKey by rememberSaveable { mutableStateOf(settings.openaiKey) }
    var showKeys by rememberSaveable { mutableStateOf(false) }
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
            ),
        )
    }

    Scaffold(
        containerColor = Palette.BgBase,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.BgBase),
                    title = { Text("Settings", style = body, color = Palette.Primary) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.FgMoreSubtle)
                        }
                    },
                    actions = { TextButton(onClick = ::save) { Text("Save", style = body, color = Palette.SuccessMostSubtle) } },
                )
                HorizontalDivider(color = Palette.Separator)
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("API keys")
            Note("Stored on this device only, encrypted with a key in the Android keystore. Sent only to the provider's API.")
            KeyField("Anthropic API key", anthropicKey, showKeys) { anthropicKey = it }
            KeyField("OpenAI API key", openaiKey, showKeys) { openaiKey = it }
            TextButton(onClick = { showKeys = !showKeys }) {
                Text(if (showKeys) "Hide keys" else "Show keys", style = small, color = Palette.FgMoreSubtle)
            }

            HorizontalDivider(color = Palette.Separator)
            Section("Servers")
            Note("Leave blank for the official APIs. Use another address only for a proxy or gateway that serves the same API.")
            UrlField("Anthropic server", anthropicBaseUrl, ANTHROPIC_BASE_URL, anthropicUrlValid) { anthropicBaseUrl = it }
            UrlField("OpenAI server", openaiBaseUrl, OPENAI_BASE_URL, openaiUrlValid) { openaiBaseUrl = it }

            HorizontalDivider(color = Palette.Separator)
            Section("Defaults")
            Note("Used when the app starts. In a chat, the model bar switches model, provider and effort.")
            Label("Start with")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Anthropic", startProvider == UserSettings.START_ANTHROPIC) { startProvider = UserSettings.START_ANTHROPIC }
                Choice("OpenAI", startProvider == UserSettings.START_OPENAI) { startProvider = UserSettings.START_OPENAI }
            }
            PlainField("Anthropic model", anthropicModel) { anthropicModel = it }
            EffortRow("Anthropic reasoning effort", anthropicEffort) { anthropicEffort = it }
            PlainField("OpenAI model", openaiModel) { openaiModel = it }
            EffortRow("OpenAI reasoning effort", openaiEffort) { openaiEffort = it }

            HorizontalDivider(color = Palette.Separator)
            Note(
                "OpenAI requests use store: false. Long conversations are compacted server-side, and prompts are cached.",
            )
            Button(
                onClick = ::save,
                enabled = anthropicUrlValid && openaiUrlValid,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Primary, contentColor = Palette.OnPrimary),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save", style = body) }
        }
    }
}

@Composable
private fun Section(text: String) = Text(text, style = body, color = Palette.Info)

@Composable
private fun Label(text: String) = Text(text, style = small, color = Palette.FgMoreSubtle)

@Composable
private fun Note(text: String) = Text(text, style = small, color = Palette.FgMostSubtle)

@Composable
private fun KeyField(label: String, value: String, show: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = small) },
        singleLine = true,
        textStyle = body,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        colors = sheetFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PlainField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = small) },
        singleLine = true,
        textStyle = body,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        colors = sheetFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun UrlField(label: String, value: String, placeholder: String, valid: Boolean, onChange: (String) -> Unit) {
    val error: (@Composable () -> Unit)? = if (valid) {
        null
    } else {
        { Text("Enter an http:// or https:// address, or leave it blank.", style = small) }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = small) },
        placeholder = { Text(placeholder, style = body, color = Palette.FgMostSubtle) },
        isError = !valid,
        supportingText = error,
        singleLine = true,
        textStyle = body,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        colors = sheetFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EffortRow(label: String, value: String, onChange: (String) -> Unit) {
    Label(label)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (effort in EFFORTS) Choice(formatEffort(effort), value == effort) { onChange(effort) }
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, style = small) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Palette.Primary,
            selectedLabelColor = Palette.OnPrimary,
            labelColor = Palette.FgSubtle,
        ),
    )
}
