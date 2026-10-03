package com.casualexplorer.chat.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * A scope for the life of the process. It runs on the main thread, which the
 * chat session relies on to change its state from one thread; its blocking
 * work (network, disk) runs on the [Dispatcher]s.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * A coroutine dispatcher for blocking or CPU-heavy work, injected rather than
 * named in place, as in Now in Android, so tests can swap in a test dispatcher.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val dispatcher: ChatDispatchers)

enum class ChatDispatchers {
    /** CPU work, such as decrypting the API keys. */
    Default,

    /** Blocking I/O: network reads and disk. */
    IO,
}

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {
    @Provides
    @Dispatcher(ChatDispatchers.IO)
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(ChatDispatchers.Default)
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
