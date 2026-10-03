package com.casualexplorer.chat

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.casualexplorer.chat.core.EFFORTS

/**
 * The app's settings, which stand in for the terminal app's environment
 * variables and flags. API keys are kept in EncryptedSharedPreferences, under
 * a key in the Android keystore; the rest is plain SharedPreferences.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secrets: SharedPreferences = openSecrets(context)

    // Read once: providers ask for a key on every request.
    @Volatile
    var anthropicKey: String = secrets.getString(ANTHROPIC_KEY, "").orEmpty()
        private set

    @Volatile
    var openaiKey: String = secrets.getString(OPENAI_KEY, "").orEmpty()
        private set

    fun setKeys(anthropic: String, openai: String) {
        anthropicKey = anthropic.trim()
        openaiKey = openai.trim()
        secrets.edit().putString(ANTHROPIC_KEY, anthropicKey).putString(OPENAI_KEY, openaiKey).apply()
    }

    /** "anthropic" or "openai": the provider the app starts with. */
    var startProvider: String
        get() = prefs.getString("start_provider", "openai") ?: "openai"
        set(value) = prefs.edit().putString("start_provider", value).apply()

    var anthropicModel: String
        get() = prefs.getString("anthropic_model", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ANTHROPIC_MODEL
        set(value) = prefs.edit().putString("anthropic_model", value.trim()).apply()

    var openaiModel: String
        get() = prefs.getString("openai_model", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_OPENAI_MODEL
        set(value) = prefs.edit().putString("openai_model", value.trim()).apply()

    var anthropicEffort: String
        get() = effort("anthropic_effort")
        set(value) = prefs.edit().putString("anthropic_effort", value).apply()

    var openaiEffort: String
        get() = effort("openai_effort")
        set(value) = prefs.edit().putString("openai_effort", value).apply()

    private fun effort(key: String) = prefs.getString(key, null)?.takeIf { it in EFFORTS } ?: DEFAULT_EFFORT

    companion object {
        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-5-5"
        const val DEFAULT_OPENAI_MODEL = "gpt-5.6-luna"
        const val DEFAULT_EFFORT = "medium"
        private const val ANTHROPIC_KEY = "anthropic_api_key"
        private const val OPENAI_KEY = "openai_api_key"
        private const val SECRETS = "secrets"

        private fun openSecrets(context: Context): SharedPreferences {
            fun create(): SharedPreferences {
                val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                return EncryptedSharedPreferences.create(
                    context,
                    SECRETS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
            return try {
                create()
            } catch (e: Exception) {
                // The keystore key is gone (e.g. data restored onto another
                // device), so the stored keys can't be decrypted: start over.
                Log.w("Settings", "Encrypted settings unreadable; clearing them", e)
                context.deleteSharedPreferences(SECRETS)
                create()
            }
        }
    }
}
