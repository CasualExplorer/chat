package com.casualexplorer.chat

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.casualexplorer.chat.data.ChatRepository
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** The root of the app's Hilt dependency graph. */
@HiltAndroidApp
class ChatApplication : Application() {
    @Inject
    lateinit var chat: Lazy<ChatRepository>

    override fun onCreate() {
        super.onCreate()
        val process = ProcessLifecycleOwner.get()
        process.lifecycle.addObserver(StopReplyOnLeave(chat, process.lifecycleScope))
    }
}
