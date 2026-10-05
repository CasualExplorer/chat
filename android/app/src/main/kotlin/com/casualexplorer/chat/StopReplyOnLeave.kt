package com.casualexplorer.chat

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.casualexplorer.chat.data.ChatRepository
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the app may be left before the reply being written is stopped. */
const val LEAVE_GRACE_MS = 60_000L

/**
 * Stops the reply being written once the user has been away from the app,
 * which is used only in the foreground, for [graceMs]: a quick look at
 * another app doesn't lose it. Observe the process lifecycle with it: that
 * stops once no activity is showing, but not for a rotation. [scope] runs
 * on the main thread, as the chat's actions must.
 */
class StopReplyOnLeave(
    private val chat: Lazy<ChatRepository>,
    private val scope: CoroutineScope,
    private val graceMs: Long = LEAVE_GRACE_MS,
) : DefaultLifecycleObserver {
    private var stopping: Job? = null

    override fun onStop(owner: LifecycleOwner) {
        stopping?.cancel()
        stopping = scope.launch {
            delay(graceMs)
            chat.get().cancel()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        stopping?.cancel()
        stopping = null
    }
}
