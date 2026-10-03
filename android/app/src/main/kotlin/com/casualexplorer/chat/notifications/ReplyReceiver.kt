package com.casualexplorer.chat.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.casualexplorer.chat.data.ChatRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Sends what was typed into a reply notification as the next message of the
 * open conversation, as the input would, then clears the notification.
 */
@AndroidEntryPoint
class ReplyReceiver : BroadcastReceiver() {
    @Inject
    lateinit var chat: ChatRepository

    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY_TEXT)?.toString().orEmpty()
        val state = chat.session.state.value
        val canSend = text.isNotBlank() && chat.settings.value?.hasKey(state.active) == true
        if (canSend && chat.session.submit(text)) ReplyService.start(context)
        // A notification answered inline must be updated or removed, or it
        // keeps showing its progress spinner.
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_REPLY)
    }
}
