package com.casualexplorer.chat.ui

import androidx.compose.runtime.Immutable
import com.casualexplorer.chat.core.AssistantMessage
import com.casualexplorer.chat.core.ChatMessage
import com.casualexplorer.chat.core.UserMessage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One row of the message list. */
@Immutable
sealed interface ChatItem {
    /** A stable key for the list. */
    val key: String

    /** A date between the messages of different days. */
    data class DateHeader(val day: LocalDate) : ChatItem {
        override val key get() = "day-$day"
    }

    /**
     * A message. Consecutive messages from the same side within
     * [GROUP_WINDOW_MS] form a group, drawn closer together with one
     * timestamp, under the last.
     */
    data class Message(val message: ChatMessage, val firstInGroup: Boolean, val lastInGroup: Boolean) : ChatItem {
        override val key get() = "message-${message.id}"
    }
}

/** How close in time messages from the same side must be to group. */
const val GROUP_WINDOW_MS = 5 * 60_000L

/** When [this] message was sent; 0 if unknown. */
val ChatMessage.sentAt: Long
    get() = when (this) {
        is UserMessage -> createdAt
        is AssistantMessage -> startMs
    }

/** The rows for [messages], oldest first, with date headers in the time zone [zone]. */
fun chatItems(messages: List<ChatMessage>, zone: ZoneId): List<ChatItem> {
    val items = ArrayList<ChatItem>(messages.size + 4)
    var lastDay: LocalDate? = null
    for ((i, message) in messages.withIndex()) {
        val at = message.sentAt
        if (at > 0) {
            val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            if (day != lastDay) items += ChatItem.DateHeader(day)
            lastDay = day
        }
        val previous = messages.getOrNull(i - 1)
        val next = messages.getOrNull(i + 1)
        items += ChatItem.Message(
            message,
            firstInGroup = previous == null || !sameGroup(previous, message, zone),
            lastInGroup = next == null || !sameGroup(message, next, zone),
        )
    }
    return items
}

private fun sameGroup(a: ChatMessage, b: ChatMessage, zone: ZoneId): Boolean {
    if ((a is UserMessage) != (b is UserMessage)) return false
    if (a.sentAt <= 0 || b.sentAt <= 0) return true
    val sameDay = Instant.ofEpochMilli(a.sentAt).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b.sentAt).atZone(zone).toLocalDate()
    return sameDay && b.sentAt - a.sentAt <= GROUP_WINDOW_MS
}
