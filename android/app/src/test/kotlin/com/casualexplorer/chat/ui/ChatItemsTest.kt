package com.casualexplorer.chat.ui

import com.casualexplorer.chat.core.AssistantMessage
import com.casualexplorer.chat.core.UserMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ChatItemsTest {
    private val zone = ZoneOffset.UTC
    private val day1 = LocalDate.of(2026, 10, 2)
    private val day2 = LocalDate.of(2026, 10, 3)

    private fun at(day: LocalDate, hour: Int, minute: Int) = day.atTime(hour, minute).toInstant(zone).toEpochMilli()

    private fun reply(id: Long, at: Long) = AssistantMessage(id, "OpenAI", "gpt", text = "r", pending = false, startMs = at)

    @Test
    fun datesSeparateDaysAndMessagesFromTheSameSideGroup() {
        val messages = listOf(
            UserMessage(1, "a", at(day1, 22, 0)),
            reply(2, at(day1, 22, 1)),
            UserMessage(3, "b", at(day2, 9, 0)),
            UserMessage(4, "c", at(day2, 9, 3)), // a resend, 3 minutes later
            UserMessage(5, "d", at(day2, 9, 30)), // too late to group
            reply(6, at(day2, 9, 31)),
        )
        val items = chatItems(messages, zone)
        assertEquals(
            listOf("day-$day1", "message-1", "message-2", "day-$day2", "message-3", "message-4", "message-5", "message-6"),
            items.map { it.key },
        )
        val groups = items.filterIsInstance<ChatItem.Message>().associate { it.message.id to (it.firstInGroup to it.lastInGroup) }
        assertEquals(true to true, groups[1])
        assertEquals(true to false, groups[3])
        assertEquals(false to true, groups[4])
        assertEquals(true to true, groups[5])
    }

    @Test
    fun messagesWithoutATimeGetNoHeader() {
        val items = chatItems(listOf(UserMessage(1, "a"), reply(2, 0)), zone)
        assertEquals(listOf("message-1", "message-2"), items.map { it.key })
    }
}
