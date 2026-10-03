package com.casualexplorer.chat.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.casualexplorer.chat.MainActivity
import com.casualexplorer.chat.R
import com.casualexplorer.chat.core.AssistantMessage

/** The channel of replies that finish or fail while the app is in the background. */
const val CHANNEL_REPLIES = "replies"

/** The channel of the ongoing notification while a reply streams. */
const val CHANNEL_STREAMING = "streaming"

const val NOTIFICATION_STREAMING = 1
const val NOTIFICATION_REPLY = 2

/** The key of the text typed into a reply notification. */
const val KEY_REPLY_TEXT = "reply_text"

/** The longest preview of a reply a notification shows. */
private const val PREVIEW_CHARS = 500

/** What a notification says about a reply that ended. */
data class ReplyNotice(val title: String, val text: String, val failed: Boolean)

/**
 * The notice for [reply], or null if there is nothing to tell: it is still
 * streaming, or the user stopped it.
 */
fun replyNotice(reply: AssistantMessage, failedTitle: String): ReplyNotice? = when {
    reply.pending || reply.canceled -> null
    reply.failure.isNotEmpty() -> ReplyNotice(failedTitle, reply.failure, failed = true)
    else -> {
        val text = reply.text.trim().ifEmpty { reply.note }
        ReplyNotice(reply.model, if (text.length <= PREVIEW_CHARS) text else text.take(PREVIEW_CHARS - 1).trimEnd() + "…", failed = false)
    }
}

/** Creates the app's channels (minSdk 26 always has them); safe to call again. */
fun createNotificationChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannels(
        listOf(
            NotificationChannel(CHANNEL_REPLIES, context.getString(R.string.channel_replies), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_replies_description)
            },
            NotificationChannel(CHANNEL_STREAMING, context.getString(R.string.channel_streaming), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_streaming_description)
            },
        ),
    )
}

/** Opens the app at the chat. */
private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    0,
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

/** The ongoing notification of [ReplyService], with an action that stops the reply. */
fun streamingNotification(context: Context): Notification {
    val stop = PendingIntent.getService(
        context,
        0,
        Intent(context, ReplyService::class.java).setAction(ReplyService.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return NotificationCompat.Builder(context, CHANNEL_STREAMING)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.notification_streaming))
        .setProgress(0, 0, true)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openApp(context))
        .addAction(0, context.getString(R.string.notification_stop), stop)
        .build()
}

/**
 * Tells of a reply that ended while the app was in the background. A
 * finished reply can be answered from the notification.
 */
fun replyNotification(context: Context, notice: ReplyNotice): Notification {
    val builder = NotificationCompat.Builder(context, CHANNEL_REPLIES)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(notice.title)
        .setContentText(notice.text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
        .setCategory(if (notice.failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_MESSAGE)
        .setContentIntent(openApp(context))
        .setAutoCancel(true)
    if (!notice.failed) {
        // RemoteInput needs a mutable intent, which the system fills in with
        // the typed text; the intent itself is explicit.
        val reply = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReplyReceiver::class.java),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val input = RemoteInput.Builder(KEY_REPLY_TEXT).setLabel(context.getString(R.string.notification_reply_label)).build()
        builder.addAction(
            NotificationCompat.Action.Builder(R.drawable.ic_notification, context.getString(R.string.notification_reply), reply)
                .addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .build(),
        )
    }
    return builder.build()
}
