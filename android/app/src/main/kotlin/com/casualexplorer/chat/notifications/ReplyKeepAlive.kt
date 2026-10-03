package com.casualexplorer.chat.notifications

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Keeps a reply that has just started going if the app leaves the screen. */
fun interface ReplyKeepAlive {
    fun replyStarted()
}

/** Starts [ReplyService] for the reply. */
class ServiceReplyKeepAlive @Inject constructor(@ApplicationContext private val context: Context) : ReplyKeepAlive {
    override fun replyStarted() = ReplyService.start(context)
}
