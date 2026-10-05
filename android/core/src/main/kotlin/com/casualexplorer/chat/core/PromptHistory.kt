package com.casualexplorer.chat.core

// Prompt history navigation follows Crush's (github.com/charmbracelet/crush,
// internal/ui/model/history.go), Copyright 2025-2026 Charmbracelet, Inc.,
// used under FSL-1.1-MIT.

/**
 * The messages sent this session, newest first, which Up and Down step
 * through in the input. Each step returns the text to show, or null when
 * there is nowhere to go.
 */
class PromptHistory {
    private val messages = ArrayList<String>()

    /** The message shown, or -1 for the draft. */
    var index = -1
        private set

    /** What was typed before stepping into the history. */
    private var draft = ""

    fun add(text: String) {
        if (messages.isEmpty() || messages[0] != text) messages.add(0, text)
        index = -1
        draft = ""
    }

    /** The previous (older) message; [current] is kept as the draft when leaving it. */
    fun previous(current: String): String? {
        if (index + 1 >= messages.size) return null
        if (index == -1) draft = current
        index++
        return messages[index]
    }

    /** The next (newer) message, or the draft past the newest. */
    fun next(): String? {
        if (index < 0) return null
        index--
        return if (index < 0) draft else messages[index]
    }

    /** Goes back to the draft, if a past message is shown. */
    fun escape(): String? {
        if (index < 0) return null
        index = -1
        return draft
    }

    /** Editing the input leaves the history, keeping the edit as the draft. */
    fun edited(text: String) {
        index = -1
        draft = text
    }
}
