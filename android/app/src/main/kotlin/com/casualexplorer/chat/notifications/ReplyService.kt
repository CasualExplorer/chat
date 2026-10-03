package com.casualexplorer.chat.notifications

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.casualexplorer.chat.R
import com.casualexplorer.chat.core.AssistantMessage
import com.casualexplorer.chat.data.ChatRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Keeps the app running while a reply streams, so a long reply finishes when
 * the app is in the background, with an ongoing notification that can stop
 * it. When the reply ends while the app is in the background, it posts the
 * reply (or why it failed) as a notification, then stops.
 *
 * The reply itself streams in [ChatRepository]; this service only keeps the
 * process alive, as a data sync foreground service (a network transfer).
 */
@AndroidEntryPoint
class ReplyService : Service() {
    @Inject
    lateinit var chat: ChatRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            chat.session.cancel()
            return START_NOT_STICKY
        }
        // A service started with startForegroundService must call
        // startForeground promptly, even if the reply has already ended.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_STREAMING,
            streamingNotification(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (watching == null) watching = scope.launch { watchReply() }
        return START_NOT_STICKY
    }

    private suspend fun watchReply() {
        val state = chat.session.state
        // The service is started as a reply starts; the state may take a
        // moment to show it.
        val started = withTimeoutOrNull(STARTUP_WAIT_MS) { state.first { it.streaming } }
        if (started != null) {
            val ended = state.first { !it.streaming }
            val reply = ended.messages.lastOrNull() as? AssistantMessage
            val inBackground = !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (reply != null && inBackground) notifyEnded(reply)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notifyEnded(reply: AssistantMessage) {
        val notice = replyNotice(reply, getString(R.string.notification_failed)) ?: return
        if (!canNotify(this)) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_REPLY, replyNotification(this, notice))
    }

    /** Android 15 and later limit how long a data sync service runs; the reply is stopped then. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        chat.session.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.casualexplorer.chat.action.STOP_REPLY"
        private const val STARTUP_WAIT_MS = 2_000L

        /**
         * Starts the service for a reply that has just started. From the
         * background this can be refused (Android 12 and later), and the
         * reply then streams without it.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ReplyService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException, or the app may
                // not start services from the background.
                android.util.Log.w("ReplyService", "Couldn't start; the reply streams without it", e)
            }
        }
    }
}

/** Whether notifications can be posted: Android 13 and later need the user's permission. */
fun canNotify(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
