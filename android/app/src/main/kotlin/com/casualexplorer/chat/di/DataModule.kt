package com.casualexplorer.chat.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.casualexplorer.chat.data.ChatRepository
import com.casualexplorer.chat.data.ConnectivityManagerNetworkMonitor
import com.casualexplorer.chat.data.DataStoreSettingsRepository
import com.casualexplorer.chat.data.DefaultChatRepository
import com.casualexplorer.chat.data.KeyCipher
import com.casualexplorer.chat.data.KeystoreCipher
import com.casualexplorer.chat.data.LEGACY_SETTINGS_PREFS
import com.casualexplorer.chat.data.LegacySecretsMigration
import com.casualexplorer.chat.data.NetworkMonitor
import com.casualexplorer.chat.data.SettingsRepository
import com.casualexplorer.chat.notifications.ReplyKeepAlive
import com.casualexplorer.chat.notifications.ServiceReplyKeepAlive
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun settingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    abstract fun keyCipher(impl: KeystoreCipher): KeyCipher

    @Binds
    abstract fun chatRepository(impl: DefaultChatRepository): ChatRepository

    @Binds
    abstract fun replyKeepAlive(impl: ServiceReplyKeepAlive): ReplyKeepAlive

    @Binds
    abstract fun networkMonitor(impl: ConnectivityManagerNetworkMonitor): NetworkMonitor

    companion object {
        /**
         * The settings store. On first use it takes over the plain settings
         * and the encrypted keys the app kept in SharedPreferences before.
         */
        @Provides
        @Singleton
        fun settingsDataStore(@ApplicationContext context: Context, cipher: KeyCipher): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                migrations = listOf(
                    SharedPreferencesMigration(context, LEGACY_SETTINGS_PREFS),
                    LegacySecretsMigration.forDevice(context, cipher),
                ),
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { context.preferencesDataStoreFile("user_settings") },
            )
    }
}
