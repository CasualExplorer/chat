package com.casualexplorer.chat

import android.app.Application
import com.casualexplorer.chat.notifications.createNotificationChannels
import dagger.hilt.android.HiltAndroidApp

/** The root of the app's Hilt dependency graph. */
@HiltAndroidApp
class ChatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
    }
}
