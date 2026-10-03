package com.casualexplorer.chat.data

import com.casualexplorer.chat.core.EFFORTS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Settings in memory, with the same rules as [DataStoreSettingsRepository] for what is saved. */
class FakeSettingsRepository(initial: UserSettings = UserSettings()) : SettingsRepository {
    val flow = MutableStateFlow(initial)
    override val settings: Flow<UserSettings> = flow

    override suspend fun setApiKey(provider: Int, key: String) = flow.update {
        if (provider == 0) it.copy(anthropicKey = key.trim()) else it.copy(openaiKey = key.trim())
    }

    override suspend fun setBaseUrl(provider: Int, url: String) {
        val normalized = normalizeBaseUrl(url) ?: return
        flow.update { if (provider == 0) it.copy(anthropicBaseUrl = normalized) else it.copy(openaiBaseUrl = normalized) }
    }

    override suspend fun setActiveProvider(provider: Int) = flow.update {
        it.copy(activeProvider = if (provider == 0) UserSettings.ANTHROPIC else UserSettings.OPENAI)
    }

    override suspend fun setModel(provider: Int, model: String) {
        if (model.isBlank()) return
        flow.update { if (provider == 0) it.copy(anthropicModel = model.trim()) else it.copy(openaiModel = model.trim()) }
    }

    override suspend fun setEffort(provider: Int, effort: String) {
        if (effort !in EFFORTS) return
        flow.update { if (provider == 0) it.copy(anthropicEffort = effort) else it.copy(openaiEffort = effort) }
    }

    override suspend fun setTheme(theme: ThemeMode) = flow.update { it.copy(theme = theme) }

    override suspend fun setDynamicColor(enabled: Boolean) = flow.update { it.copy(dynamicColor = enabled) }
}
