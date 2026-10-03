package com.casualexplorer.chat

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.casualexplorer.chat.data.ChatRepository
import dagger.Lazy

/**
 * Stops the reply being written when the user leaves the app, which is
 * used only in the foreground. Observe the process lifecycle with it: that
 * stops once no activity is showing, but not for a rotation.
 */
class StopReplyOnLeave(private val chat: Lazy<ChatRepository>) : DefaultLifecycleObserver {
    override fun onStop(owner: LifecycleOwner) {
        chat.get().cancel()
    }
}
