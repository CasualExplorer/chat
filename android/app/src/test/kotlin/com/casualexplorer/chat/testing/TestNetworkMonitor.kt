package com.casualexplorer.chat.testing

import com.casualexplorer.chat.data.NetworkMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Online until [online] says otherwise. */
class TestNetworkMonitor : NetworkMonitor {
    val online = MutableStateFlow(true)
    override val isOnline: Flow<Boolean> = online
}
