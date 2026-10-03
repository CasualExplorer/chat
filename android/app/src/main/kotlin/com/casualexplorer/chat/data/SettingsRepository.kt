package com.casualexplorer.chat.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.casualexplorer.chat.core.EFFORTS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The app's settings, which stand in for the terminal app's environment variables and flags. */
interface SettingsRepository {
    val settings: Flow<UserSettings>

    suspend fun save(settings: UserSettings)
}

/**
 * Settings in Preferences DataStore. The API keys are stored encrypted with
 * [cipher]; a key that can no longer be decrypted reads as empty.
 */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: KeyCipher,
) : SettingsRepository {
    override val settings: Flow<UserSettings> = dataStore.data.map { p ->
        val defaults = UserSettings()
        UserSettings(
            anthropicKey = p[Keys.ANTHROPIC_KEY]?.let(cipher::decrypt).orEmpty(),
            openaiKey = p[Keys.OPENAI_KEY]?.let(cipher::decrypt).orEmpty(),
            anthropicBaseUrl = p[Keys.ANTHROPIC_BASE_URL]?.let(::normalizeBaseUrl).orEmpty(),
            openaiBaseUrl = p[Keys.OPENAI_BASE_URL]?.let(::normalizeBaseUrl).orEmpty(),
            startProvider = p[Keys.START_PROVIDER]
                ?.takeIf { it == UserSettings.START_ANTHROPIC || it == UserSettings.START_OPENAI }
                ?: defaults.startProvider,
            anthropicModel = p[Keys.ANTHROPIC_MODEL]?.takeIf { it.isNotBlank() } ?: defaults.anthropicModel,
            openaiModel = p[Keys.OPENAI_MODEL]?.takeIf { it.isNotBlank() } ?: defaults.openaiModel,
            anthropicEffort = p[Keys.ANTHROPIC_EFFORT]?.takeIf { it in EFFORTS } ?: defaults.anthropicEffort,
            openaiEffort = p[Keys.OPENAI_EFFORT]?.takeIf { it in EFFORTS } ?: defaults.openaiEffort,
            theme = ThemeMode.entries.firstOrNull { it.name == p[Keys.THEME] } ?: defaults.theme,
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
        )
    }.distinctUntilChanged()

    override suspend fun save(settings: UserSettings) {
        dataStore.edit { p ->
            p[Keys.ANTHROPIC_KEY] = cipher.encrypt(settings.anthropicKey.trim())
            p[Keys.OPENAI_KEY] = cipher.encrypt(settings.openaiKey.trim())
            p[Keys.ANTHROPIC_BASE_URL] = normalizeBaseUrl(settings.anthropicBaseUrl).orEmpty()
            p[Keys.OPENAI_BASE_URL] = normalizeBaseUrl(settings.openaiBaseUrl).orEmpty()
            p[Keys.START_PROVIDER] = settings.startProvider
            p[Keys.ANTHROPIC_MODEL] = settings.anthropicModel.trim()
            p[Keys.OPENAI_MODEL] = settings.openaiModel.trim()
            p[Keys.ANTHROPIC_EFFORT] = settings.anthropicEffort
            p[Keys.OPENAI_EFFORT] = settings.openaiEffort
            p[Keys.THEME] = settings.theme.name
            p[Keys.DYNAMIC_COLOR] = settings.dynamicColor
        }
    }
}

/**
 * The stored keys. The plain ones have the names the app's SharedPreferences
 * used, so SharedPreferencesMigration carries them over as they are.
 */
internal object Keys {
    val START_PROVIDER = stringPreferencesKey("start_provider")
    val ANTHROPIC_MODEL = stringPreferencesKey("anthropic_model")
    val OPENAI_MODEL = stringPreferencesKey("openai_model")
    val ANTHROPIC_EFFORT = stringPreferencesKey("anthropic_effort")
    val OPENAI_EFFORT = stringPreferencesKey("openai_effort")
    val ANTHROPIC_BASE_URL = stringPreferencesKey("anthropic_base_url")
    val OPENAI_BASE_URL = stringPreferencesKey("openai_base_url")
    val ANTHROPIC_KEY = stringPreferencesKey("anthropic_api_key_encrypted")
    val OPENAI_KEY = stringPreferencesKey("openai_api_key_encrypted")
    val THEME = stringPreferencesKey("theme")
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val SECRETS_MIGRATED = booleanPreferencesKey("legacy_secrets_migrated")
}

/** The SharedPreferences file the app kept its plain settings in. */
const val LEGACY_SETTINGS_PREFS = "settings"

/**
 * Moves the API keys from the EncryptedSharedPreferences the app used before
 * into DataStore, re-encrypted with [cipher], once. [read] returns the old
 * keys by name ("anthropic_api_key", "openai_api_key"); [delete] removes the
 * old file afterwards.
 */
class LegacySecretsMigration(
    private val read: () -> Map<String, String>,
    private val delete: () -> Unit,
    private val cipher: KeyCipher,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = currentData[Keys.SECRETS_MIGRATED] != true

    override suspend fun migrate(currentData: Preferences): Preferences {
        val old = read()
        return currentData.toMutablePreferences().apply {
            old["anthropic_api_key"]?.trim()?.takeIf { it.isNotEmpty() }?.let { this[Keys.ANTHROPIC_KEY] = cipher.encrypt(it) }
            old["openai_api_key"]?.trim()?.takeIf { it.isNotEmpty() }?.let { this[Keys.OPENAI_KEY] = cipher.encrypt(it) }
            this[Keys.SECRETS_MIGRATED] = true
        }.toPreferences()
    }

    override suspend fun cleanUp() = delete()

    companion object {
        private const val FILE = "secrets"

        /** The migration from this device's old EncryptedSharedPreferences, if there are any. */
        fun forDevice(context: Context, cipher: KeyCipher) = LegacySecretsMigration(
            read = { readEncryptedPrefs(context) },
            delete = { context.deleteSharedPreferences(FILE) },
            cipher = cipher,
        )

        @Suppress("DEPRECATION") // security-crypto is only used to read the old file once.
        private fun readEncryptedPrefs(context: Context): Map<String, String> {
            // Opening the file would create a keystore key for nothing on a
            // fresh install.
            if (!File(context.dataDir, "shared_prefs/$FILE.xml").exists()) return emptyMap()
            return try {
                val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                val prefs = EncryptedSharedPreferences.create(
                    context,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
            } catch (e: Exception) {
                // The keystore key is gone (e.g. data restored onto another
                // device): the old keys can't be read, so they are dropped.
                Log.w("Settings", "Old encrypted settings unreadable; dropping them", e)
                emptyMap()
            }
        }
    }
}
