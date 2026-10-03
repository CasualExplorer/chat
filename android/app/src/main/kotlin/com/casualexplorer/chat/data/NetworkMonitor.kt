package com.casualexplorer.chat.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/** Whether the device has a network that reaches the internet. */
interface NetworkMonitor {
    val isOnline: Flow<Boolean>
}

/** [NetworkMonitor] from ConnectivityManager callbacks, as Now in Android does it. */
class ConnectivityManagerNetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkMonitor {
    override val isOnline: Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            trySend(false)
            close()
            return@callbackFlow
        }
        // Called for every network matching the request, not just the active
        // one, so it is enough to track whether any is left.
        val callback = object : ConnectivityManager.NetworkCallback() {
            private val networks = mutableSetOf<Network>()

            override fun onAvailable(network: Network) {
                synchronized(networks) { networks += network }
                trySend(true)
            }

            override fun onLost(network: Network) {
                val any = synchronized(networks) {
                    networks -= network
                    networks.isNotEmpty()
                }
                trySend(any)
            }
        }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        manager.registerNetworkCallback(request, callback)
        trySend(manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.conflate().distinctUntilChanged()
}
