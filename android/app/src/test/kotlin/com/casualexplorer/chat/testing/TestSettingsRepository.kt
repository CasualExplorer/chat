package com.casualexplorer.chat.testing

import com.casualexplorer.chat.core.EFFORTS
import com.casualexplorer.chat.data.ApiProvider
import com.casualexplorer.chat.data.ApiProvider.Anthropic
import com.casualexplorer.chat.data.DataStoreSettingsRepository
import com.casualexplorer.chat.data.SettingsRepository
import com.casualexplorer.chat.data.ThemeMode
import com.casualexplorer.chat.data.UserSettings
import com.casualexplorer.chat.data.normalizeBaseUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Settings in memory, with the same rules as [DataStoreSettingsRepository] for what is saved. */
class TestSettingsRepository(initial: UserSettings = UserSettings()) : SettingsRepository {
    val flow = MutableStateFlow(initial)
    override val settings: Flow<UserSettings> = flow

    override suspend fun setApiKey(provider: ApiProvider, key: String) = flow.update {
        if (provider == Anthropic) it.copy(anthropicKey = key.trim()) else it.copy(openaiKey = key.trim())
    }

    override suspend fun setBaseUrl(provider: ApiProvider, url: String) {
        val normalized = normalizeBaseUrl(url) ?: return
        flow.update { if (provider == Anthropic) it.copy(anthropicBaseUrl = normalized) else it.copy(openaiBaseUrl = normalized) }
    }

    override suspend fun setActiveProvider(provider: ApiProvider) = flow.update { it.copy(activeProvider = provider) }

    override suspend fun setModel(provider: ApiProvider, model: String) {
        if (model.isBlank()) return
        flow.update { if (provider == Anthropic) it.copy(anthropicModel = model.trim()) else it.copy(openaiModel = model.trim()) }
    }

    override suspend fun setEffort(provider: ApiProvider, effort: String) {
        if (effort !in EFFORTS) return
        flow.update { if (provider == Anthropic) it.copy(anthropicEffort = effort) else it.copy(openaiEffort = effort) }
    }

    override suspend fun setTheme(theme: ThemeMode) = flow.update { it.copy(theme = theme) }

    override suspend fun setDynamicColor(enabled: Boolean) = flow.update { it.copy(dynamicColor = enabled) }
}
