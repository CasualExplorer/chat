package com.casualexplorer.chat.data

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Reverses the text, so stored keys differ from the plain ones. It can't read anything else. */
class FakeCipher : KeyCipher {
    override fun encrypt(plain: String) = if (plain.isEmpty()) "" else "enc:" + plain.reversed()

    override fun decrypt(stored: String) = when {
        stored.isEmpty() -> ""
        stored.startsWith("enc:") -> stored.removePrefix("enc:").reversed()
        else -> null
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SettingsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun newFile(): File = File(folder.root, "settings-${System.nanoTime()}.preferences_pb")

    private fun TestScope.store(
        file: File = newFile(),
        migrations: List<DataMigration<Preferences>> = emptyList(),
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(migrations = migrations, scope = backgroundScope, produceFile = { file })

    private fun TestScope.repository(store: DataStore<Preferences>) = DataStoreSettingsRepository(store, FakeCipher(), UnconfinedTestDispatcher(testScheduler))

    /** What is stored, by key name. */
    private suspend fun DataStore<Preferences>.raw(): Map<String, Any> = data.first().asMap().mapKeys { it.key.name }

    @Test
    fun emptyStoreGivesTheTerminalAppsDefaults() = runTest {
        assertEquals(UserSettings(), repository(store()).settings.first())
    }

    @Test
    fun keysAreStoredEncryptedAndReadBack() = runTest {
        val store = store()
        val repo = repository(store)
        repo.setApiKey(ApiProvider.Anthropic, " sk-ant ")
        repo.setApiKey(ApiProvider.OpenAI, "sk-oa")
        repo.setBaseUrl(ApiProvider.Anthropic, "https://gateway.example/anthropic/")
        repo.setActiveProvider(ApiProvider.Anthropic)
        repo.setModel(ApiProvider.Anthropic, "claude-opus-5-5")
        repo.setEffort(ApiProvider.OpenAI, "max")
        repo.setTheme(ThemeMode.Dark)
        repo.setDynamicColor(false)
        val read = repo.settings.first()
        assertEquals("sk-ant", read.anthropicKey)
        assertEquals("sk-oa", read.openaiKey)
        assertEquals("https://gateway.example/anthropic", read.anthropicBaseUrl)
        assertEquals(ApiProvider.Anthropic, read.activeProvider)
        assertEquals("claude-opus-5-5", read.anthropicModel)
        assertEquals("max", read.openaiEffort)
        assertEquals(ThemeMode.Dark, read.theme)
        assertFalse(read.dynamicColor)

        val raw = store.raw()
        assertEquals("enc:tna-ks", raw["anthropic_api_key_encrypted"])
        assertFalse("no key is stored in the clear", raw.values.any { it == "sk-oa" || it == "sk-ant" })
    }

    @Test
    fun aKeyThatCanNoLongerBeDecryptedReadsAsEmpty() = runTest {
        val store = store()
        store.updateData { it.toMutablePreferences().apply { this[Keys.OPENAI_KEY] = "written by a lost keystore key" } }
        assertEquals("", repository(store).settings.first().openaiKey)
    }

    @Test
    fun oldSettingsAndKeysAreMigratedOnce() = runTest {
        context.getSharedPreferences(LEGACY_SETTINGS_PREFS, Context.MODE_PRIVATE).edit()
            .putString("start_provider", "anthropic")
            .putString("openai_model", "gpt-6-astra")
            .putString("anthropic_effort", "high")
            .commit()
        var deleted = false
        val secrets = LegacySecretsMigration(
            read = { mapOf("anthropic_api_key" to "sk-ant-old", "openai_api_key" to "") },
            delete = { deleted = true },
            cipher = FakeCipher(),
        )
        val store = store(migrations = listOf(SharedPreferencesMigration(context, LEGACY_SETTINGS_PREFS), secrets))
        val read = repository(store).settings.first()

        assertEquals(ApiProvider.Anthropic, read.activeProvider)
        assertEquals("gpt-6-astra", read.openaiModel)
        assertEquals("high", read.anthropicEffort)
        assertEquals("sk-ant-old", read.anthropicKey)
        assertEquals("", read.openaiKey)
        assertEquals("enc:dlo-tna-ks", store.raw()["anthropic_api_key_encrypted"])
        assertTrue("the old encrypted file is deleted", deleted)
        assertNull(
            "the old plain settings are removed",
            context.getSharedPreferences(LEGACY_SETTINGS_PREFS, Context.MODE_PRIVATE).getString("start_provider", null),
        )
        assertFalse("it runs once", secrets.shouldMigrate(store.data.first()))
    }

    @Test
    fun invalidValuesAreNotSaved() = runTest {
        val repo = repository(store())
        repo.setBaseUrl(ApiProvider.OpenAI, "https://ok.example")
        repo.setEffort(ApiProvider.Anthropic, "huge")
        repo.setModel(ApiProvider.OpenAI, " ")
        // Typed on the way to a valid address: the last valid one stays.
        repo.setBaseUrl(ApiProvider.OpenAI, "ftp://x")
        val read = repo.settings.first()
        assertEquals(UserSettings.DEFAULT_EFFORT, read.anthropicEffort)
        assertEquals(UserSettings.DEFAULT_OPENAI_MODEL, read.openaiModel)
        assertEquals("https://ok.example", read.openaiBaseUrl)
    }

    @Test
    fun invalidStoredValuesFallBackToDefaults() = runTest {
        val store = store()
        store.updateData {
            it.toMutablePreferences().apply {
                this[Keys.ACTIVE_PROVIDER] = "nope"
                this[Keys.ANTHROPIC_EFFORT] = "huge"
                this[Keys.OPENAI_MODEL] = " "
                this[Keys.OPENAI_BASE_URL] = "ftp://x"
            }
        }
        val read = repository(store).settings.first()
        assertEquals(ApiProvider.OpenAI, read.activeProvider)
        assertEquals(UserSettings.DEFAULT_EFFORT, read.anthropicEffort)
        assertEquals(UserSettings.DEFAULT_OPENAI_MODEL, read.openaiModel)
        assertEquals("", read.openaiBaseUrl)
    }

    @Test
    fun clearingAKeyStoresItEmpty() = runTest {
        val repo = repository(store())
        repo.setApiKey(ApiProvider.OpenAI, "sk-oa")
        repo.setApiKey(ApiProvider.OpenAI, "")
        assertEquals("", repo.settings.first().openaiKey)
    }

    @Test
    fun baseUrlsAreNormalized() {
        assertEquals("", normalizeBaseUrl("  "))
        assertEquals("https://a.example/v", normalizeBaseUrl(" https://a.example/v/ "))
        assertEquals("http://10.0.2.2:8080", normalizeBaseUrl("http://10.0.2.2:8080"))
        assertEquals("http://localhost:11434/v1", normalizeBaseUrl("http://localhost:11434/v1/"))
        assertEquals("HTTP://LOCALHOST:11434", normalizeBaseUrl("HTTP://LOCALHOST:11434"))
        assertNull(normalizeBaseUrl("http://api.example.com"))
        assertNull(normalizeBaseUrl("http://10.0.2.2.evil.example"))
        assertNull(normalizeBaseUrl("api.example.com"))
        assertNull(normalizeBaseUrl("https://"))
    }
}
