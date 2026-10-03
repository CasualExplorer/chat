package com.casualexplorer.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.casualexplorer.chat.ui.ChatScreen
import com.casualexplorer.chat.ui.ChatTheme
import com.casualexplorer.chat.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChatTheme { App() }
        }
    }
}

@Composable
private fun App(vm: ChatViewModel = viewModel()) {
    // Settings opens first until there is a key to use.
    var settingsOpen by rememberSaveable { mutableStateOf(!vm.hasKey(0) && !vm.hasKey(1)) }
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    if (settingsOpen) {
        SettingsScreen(vm, onBack = { settingsOpen = false })
    } else {
        ChatScreen(vm, input, onInputChange = { input = it }, onOpenSettings = { settingsOpen = true })
    }
}
