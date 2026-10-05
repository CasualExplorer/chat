package com.casualexplorer.chat.data

/**
 * What the terminal app takes from the environment and flags: the API keys,
 * an optional server for each API, the provider in use, and each provider's
 * model and reasoning effort. The provider, models and efforts are the ones
 * last picked in the chat, so the app reopens with them.
 */
data class UserSettings(
    val anthropicKey: String = "",
    val openaiKey: String = "",
    /** Blank for the official API. */
    val anthropicBaseUrl: String = "",
    /** Blank for the official API. */
    val openaiBaseUrl: String = "",
    val activeProvider: ApiProvider = ApiProvider.OpenAI,
    val anthropicModel: String = DEFAULT_ANTHROPIC_MODEL,
    val openaiModel: String = DEFAULT_OPENAI_MODEL,
    val anthropicEffort: String = DEFAULT_EFFORT,
    val openaiEffort: String = DEFAULT_EFFORT,
    val theme: ThemeMode = ThemeMode.System,
    /** Material You colours from the wallpaper, on Android 12 and later. */
    val dynamicColor: Boolean = true,
) {
    fun hasKey(provider: ApiProvider) = key(provider).isNotEmpty()

    /** Whether either provider has a key. */
    val hasAnyKey: Boolean get() = ApiProvider.entries.any { hasKey(it) }

    fun key(provider: ApiProvider) = when (provider) {
        ApiProvider.Anthropic -> anthropicKey
        ApiProvider.OpenAI -> openaiKey
    }

    /** The provider's server; blank for the official API. */
    fun baseUrl(provider: ApiProvider) = when (provider) {
        ApiProvider.Anthropic -> anthropicBaseUrl
        ApiProvider.OpenAI -> openaiBaseUrl
    }

    fun model(provider: ApiProvider) = when (provider) {
        ApiProvider.Anthropic -> anthropicModel
        ApiProvider.OpenAI -> openaiModel
    }

    fun effort(provider: ApiProvider) = when (provider) {
        ApiProvider.Anthropic -> anthropicEffort
        ApiProvider.OpenAI -> openaiEffort
    }

    companion object {
        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-5-5"
        const val DEFAULT_OPENAI_MODEL = "gpt-5.6-luna"
        const val DEFAULT_EFFORT = "medium"
    }
}

/**
 * The APIs the app talks to. [index] is the provider's place in the chat
 * session's list (ChatState.active, ModelChoice.provider); [storedName] is
 * how the settings store it.
 */
enum class ApiProvider(val index: Int, val storedName: String) {
    Anthropic(0, "anthropic"),
    OpenAI(1, "openai"),
    ;

    companion object {
        /** The provider at [index] in the chat session's list. */
        fun fromIndex(index: Int): ApiProvider = entries.first { it.index == index }

        fun fromStoredName(name: String?): ApiProvider? = entries.firstOrNull { it.storedName == name }
    }
}

/** Light, dark, or following the system. */
enum class ThemeMode { System, Light, Dark }

/** Hosts that may use plain http, matching res/xml/network_security_config.xml (local gateways and the emulator's host). */
private val CLEARTEXT_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

/**
 * A server address as stored: trimmed, without a trailing slash, blank for
 * the official API. It returns null if [url] is neither blank nor an https
 * URL, or an http URL for a local host; the API key would otherwise go in
 * plain text (and the platform blocks cleartext to other hosts anyway).
 */
fun normalizeBaseUrl(url: String): String? {
    val trimmed = url.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    val lower = trimmed.lowercase()
    val rest = trimmed.substringAfter("://")
    if (rest.isEmpty()) return null
    return when {
        lower.startsWith("https://") -> trimmed
        lower.startsWith("http://") -> {
            val host = rest.substringBefore('/').substringBefore('?').substringBefore(':')
            if (host.lowercase() in CLEARTEXT_HOSTS) trimmed else null
        }
        else -> null
    }
}
