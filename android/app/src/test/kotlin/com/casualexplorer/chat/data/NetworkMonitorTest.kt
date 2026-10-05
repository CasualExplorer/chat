package com.casualexplorer.chat.data

import android.app.Application
import android.net.ConnectivityManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NetworkMonitorTest {
    @Test
    fun followsNetworksComingAndGoing() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val values = mutableListOf<Boolean>()
        val collecting = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ConnectivityManagerNetworkMonitor(context).isOnline.collect { values += it }
        }

        val callback = shadowOf(manager).networkCallbacks.single()
        val wifi = ShadowNetwork.newInstance(1)
        val cell = ShadowNetwork.newInstance(2)
        callback.onAvailable(wifi)
        callback.onAvailable(cell)
        callback.onLost(wifi)
        assertEquals("still online on the other network", true, values.last())
        callback.onLost(cell)
        assertEquals(false, values.last())
        assertTrue(values.contains(true))

        collecting.cancel()
        testScheduler.advanceUntilIdle()
        assertTrue("the callback is unregistered", shadowOf(manager).networkCallbacks.isEmpty())
    }
}
