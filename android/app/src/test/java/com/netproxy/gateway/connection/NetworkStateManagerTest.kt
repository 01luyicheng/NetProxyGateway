package com.netproxy.gateway.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetworkStateManagerTest {

    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifiNetwork: Network
    private lateinit var cellularNetwork: Network

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        connectivityManager = mockk(relaxed = true)
        wifiNetwork = mockk(relaxed = true)
        cellularNetwork = mockk(relaxed = true)

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
    }

    @Test
    fun getStateAfterNetworkLost_returnsCurrentActiveNetworkState() {
        every { connectivityManager.activeNetwork } returns cellularNetwork
        every { connectivityManager.getNetworkCapabilities(cellularNetwork) } returns capabilities(
            transport = NetworkCapabilities.TRANSPORT_CELLULAR,
            validated = true,
            internet = true
        )

        val manager = NetworkStateManager(context)
        val state = manager.getStateAfterNetworkLost()

        assertTrue(state.isConnected)
        assertTrue(state.isValidated)
        assertEquals(NetworkType.Cellular, state.networkType)
        assertEquals(cellularNetwork, state.network)
    }

    @Test
    fun getCurrentNetworkState_withoutAvailableCapabilities_returnsDisconnectedState() {
        every { connectivityManager.activeNetwork } returns null

        val manager = NetworkStateManager(context)
        val state = manager.getCurrentNetworkState(null)

        assertFalse(state.isConnected)
        assertFalse(state.isValidated)
        assertEquals(NetworkType.None, state.networkType)
        assertEquals(null, state.network)
    }

    @Test
    fun getCurrentNetworkState_withExplicitNetwork_usesThatNetworkInsteadOfActiveNetwork() {
        every { connectivityManager.activeNetwork } returns cellularNetwork
        every { connectivityManager.getNetworkCapabilities(wifiNetwork) } returns capabilities(
            transport = NetworkCapabilities.TRANSPORT_WIFI,
            validated = false,
            internet = true
        )
        every { connectivityManager.getNetworkCapabilities(cellularNetwork) } returns capabilities(
            transport = NetworkCapabilities.TRANSPORT_CELLULAR,
            validated = true,
            internet = true
        )

        val manager = NetworkStateManager(context)
        val state = manager.getCurrentNetworkState(wifiNetwork)

        assertTrue(state.isConnected)
        assertFalse(state.isValidated)
        assertEquals(NetworkType.Wifi, state.networkType)
        assertEquals(wifiNetwork, state.network)
    }

    private fun capabilities(
        transport: Int,
        validated: Boolean,
        internet: Boolean
    ): NetworkCapabilities {
        val capabilities = mockk<NetworkCapabilities>()
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns (transport == NetworkCapabilities.TRANSPORT_WIFI)
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns (transport == NetworkCapabilities.TRANSPORT_CELLULAR)
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } returns (transport == NetworkCapabilities.TRANSPORT_ETHERNET)
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns internet
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns validated
        return capabilities
    }
}
