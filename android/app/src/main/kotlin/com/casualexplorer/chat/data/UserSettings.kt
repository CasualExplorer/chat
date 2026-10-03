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
    /** [ANTHROPIC] or [OPENAI]. */
    val activeProvider: String = OPENAI,
    val anthropicModel: String = DEFAULT_ANTHROPIC_MODEL,
    val openaiModel: String = DEFAULT_OPENAI_MODEL,
    val anthropicEffort: String = DEFAULT_EFFORT,
    val openaiEffort: String = DEFAULT_EFFORT,
    val theme: ThemeMode = ThemeMode.System,
    /** Material You colours from the wallpaper, on Android 12 and later. */
    val dynamicColor: Boolean = true,
) {
    /** Whether provider [index] (0 Anthropic, 1 OpenAI) has a key. */
    fun hasKey(index: Int) = if (index == 0) anthropicKey.isNotEmpty() else openaiKey.isNotEmpty()

    /** The index of the provider in use (0 Anthropic, 1 OpenAI). */
    val activeIndex: Int get() = if (activeProvider == ANTHROPIC) 0 else 1

    companion object {
        const val ANTHROPIC = "anthropic"
        const val OPENAI = "openai"
        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-5-5"
        const val DEFAULT_OPENAI_MODEL = "gpt-5.6-luna"
        const val DEFAULT_EFFORT = "medium"
    }
}

/** Light, dark, or following the system. */
enum class ThemeMode { System, Light, Dark }

/**
 * A server address as stored: trimmed, without a trailing slash, blank for
 * the official API. It returns null if [url] is neither blank nor an
 * http(s) URL.
 */
fun normalizeBaseUrl(url: String): String? {
    val trimmed = url.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    val lower = trimmed.lowercase()
    if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null
    if (trimmed.substringAfter("://").isEmpty()) return null
    return trimmed
}
