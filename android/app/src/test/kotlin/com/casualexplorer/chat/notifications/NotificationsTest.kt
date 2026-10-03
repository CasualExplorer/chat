package com.casualexplorer.chat.notifications

import android.app.Application
import androidx.core.app.NotificationCompat
import com.casualexplorer.chat.core.AssistantMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NotificationsTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun reply(text: String = "", failure: String = "", canceled: Boolean = false, pending: Boolean = false) =
        AssistantMessage(1, "OpenAI", "gpt-5.6-luna", text = text, failure = failure, canceled = canceled, pending = pending)

    @Test
    fun aFinishedReplyIsPreviewedUnderItsModel() {
        assertEquals(ReplyNotice("gpt-5.6-luna", "Paris.", failed = false), replyNotice(reply(text = " Paris. "), "Reply failed"))
        val long = replyNotice(reply(text = "x".repeat(2_000)), "Reply failed")!!
        assertEquals(500, long.text.length)
        assertTrue(long.text.endsWith("…"))
    }

    @Test
    fun aFailureSaysWhy() {
        assertEquals(ReplyNotice("Reply failed", "OpenAI API error 500", failed = true), replyNotice(reply(failure = "OpenAI API error 500"), "Reply failed"))
    }

    @Test
    fun nothingIsToldOfAStoppedOrStreamingReply() {
        assertNull(replyNotice(reply(text = "half", canceled = true), "Reply failed"))
        assertNull(replyNotice(reply(text = "half", pending = true), "Reply failed"))
    }

    @Test
    fun aFinishedReplyCanBeAnsweredInline() {
        createNotificationChannels(context)
        val notification = replyNotification(context, ReplyNotice("gpt-5.6-luna", "Paris.", failed = false))
        assertEquals(CHANNEL_REPLIES, notification.channelId)
        val action = NotificationCompat.getAction(notification, 0)!!
        assertEquals("Reply", action.title)
        assertEquals(KEY_REPLY_TEXT, action.remoteInputs!!.single().resultKey)

        val failed = replyNotification(context, ReplyNotice("Reply failed", "Boom", failed = true))
        assertEquals(0, NotificationCompat.getActionCount(failed))
    }

    @Test
    fun theStreamingNotificationIsOngoingWithStop() {
        createNotificationChannels(context)
        val notification = streamingNotification(context)
        assertEquals(CHANNEL_STREAMING, notification.channelId)
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Stop", NotificationCompat.getAction(notification, 0)!!.title)
    }
}
