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
import java.util.concurrent.ConcurrentHashMap

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
    fun getBestNetworkState_withNoActiveNetworks_returnsDisconnectedState() {
        val manager = NetworkStateManager(context)
        val state = manager.getBestNetworkState()

        assertFalse(state.isConnected)
        assertFalse(state.isValidated)
        assertEquals(NetworkType.None, state.networkType)
        assertEquals(null, state.network)
    }

    @Test
    fun getBestNetworkState_withRemainingCellular_returnsCellularState() {
        val manager = NetworkStateManager(context)
        val activeNetworksField = NetworkStateManager::class.java.getDeclaredField("activeNetworks")
        activeNetworksField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val activeNetworks = activeNetworksField.get(manager) as ConcurrentHashMap<Network, NetworkCapabilities>
        activeNetworks[cellularNetwork] = capabilities(
            transport = NetworkCapabilities.TRANSPORT_CELLULAR,
            validated = true,
            internet = true
        )

        val state = manager.getBestNetworkState()

        assertTrue(state.isConnected)
        assertTrue(state.isValidated)
        assertEquals(NetworkType.Cellular, state.networkType)
        assertEquals(cellularNetwork, state.network)
    }

    @Test
    fun getBestNetworkState_withMultipleNetworks_returnsHighestPriority() {
        val wifiCapabilities = capabilities(
            transport = NetworkCapabilities.TRANSPORT_WIFI,
            validated = true,
            internet = true
        )
        val cellularCapabilities = capabilities(
            transport = NetworkCapabilities.TRANSPORT_CELLULAR,
            validated = true,
            internet = true
        )

        val manager = NetworkStateManager(context)
        val activeNetworksField = NetworkStateManager::class.java.getDeclaredField("activeNetworks")
        activeNetworksField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val activeNetworks = activeNetworksField.get(manager) as ConcurrentHashMap<Network, NetworkCapabilities>
        activeNetworks[wifiNetwork] = wifiCapabilities
        activeNetworks[cellularNetwork] = cellularCapabilities

        val state = manager.getBestNetworkState()

        assertTrue(state.isConnected)
        assertTrue(state.isValidated)
        assertEquals(NetworkType.Wifi, state.networkType)
        assertEquals(wifiNetwork, state.network)
    }

    @Test
    fun getBestNetworkState_afterRemovingWifi_returnsCellularState() {
        val wifiCapabilities = capabilities(
            transport = NetworkCapabilities.TRANSPORT_WIFI,
            validated = true,
            internet = true
        )
        val cellularCapabilities = capabilities(
            transport = NetworkCapabilities.TRANSPORT_CELLULAR,
            validated = true,
            internet = true
        )

        val manager = NetworkStateManager(context)
        val activeNetworksField = NetworkStateManager::class.java.getDeclaredField("activeNetworks")
        activeNetworksField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val activeNetworks = activeNetworksField.get(manager) as ConcurrentHashMap<Network, NetworkCapabilities>
        activeNetworks[wifiNetwork] = wifiCapabilities
        activeNetworks[cellularNetwork] = cellularCapabilities

        // Remove WiFi
        activeNetworks.remove(wifiNetwork)
        val state = manager.getBestNetworkState()

        assertTrue(state.isConnected)
        assertTrue(state.isValidated)
        assertEquals(NetworkType.Cellular, state.networkType)
        assertEquals(cellularNetwork, state.network)
    }

    @Test
    fun getBestNetworkState_withEthernet_returnsEthernetState() {
        val ethernetNetwork = mockk<Network>(relaxed = true)
        val manager = NetworkStateManager(context)
        val activeNetworksField = NetworkStateManager::class.java.getDeclaredField("activeNetworks")
        activeNetworksField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val activeNetworks = activeNetworksField.get(manager) as ConcurrentHashMap<Network, NetworkCapabilities>
        activeNetworks[ethernetNetwork] = capabilities(
            transport = NetworkCapabilities.TRANSPORT_ETHERNET,
            validated = true,
            internet = true
        )

        val state = manager.getBestNetworkState()

        assertTrue(state.isConnected)
        assertTrue(state.isValidated)
        assertEquals(NetworkType.Ethernet, state.networkType)
        assertEquals(ethernetNetwork, state.network)
    }

    @Test
    fun getBestNetworkState_priorityOrder_ethernetOverWifiOverCellular() {
        val ethernetNetwork = mockk<Network>(relaxed = true)
        val wifiCapabilities = capabilities(
            transport = NetworkCapabilities.TRANSPORT_WIFI,
            validated = true,
            internet = true
        )
        val cellularCapabilities = capabilities(
            transport = NetworkCapabilities.TRANSPORT_CELLULAR,
            validated = true,
            internet = true
        )
        val ethernetCapabilities = capabilities(
            transport = NetworkCapabilities.TRANSPORT_ETHERNET,
            validated = true,
            internet = true
        )

        val manager = NetworkStateManager(context)
        val activeNetworksField = NetworkStateManager::class.java.getDeclaredField("activeNetworks")
        activeNetworksField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val activeNetworks = activeNetworksField.get(manager) as ConcurrentHashMap<Network, NetworkCapabilities>

        // Add all three networks
        activeNetworks[ethernetNetwork] = ethernetCapabilities
        activeNetworks[cellularNetwork] = cellularCapabilities
        activeNetworks[wifiNetwork] = wifiCapabilities

        val state = manager.getBestNetworkState()

        // Ethernet should win due to highest priority (Ethernet > WiFi > Cellular)
        assertEquals(NetworkType.Ethernet, state.networkType)
        assertEquals(ethernetNetwork, state.network)
    }

    @Test
    fun getBestNetworkState_withUnvalidatedNetwork_returnsUnvalidatedState() {
        val manager = NetworkStateManager(context)
        val activeNetworksField = NetworkStateManager::class.java.getDeclaredField("activeNetworks")
        activeNetworksField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val activeNetworks = activeNetworksField.get(manager) as ConcurrentHashMap<Network, NetworkCapabilities>
        activeNetworks[wifiNetwork] = capabilities(
            transport = NetworkCapabilities.TRANSPORT_WIFI,
            validated = false,
            internet = true
        )

        val state = manager.getBestNetworkState()

        assertTrue(state.isConnected)
        assertFalse(state.isValidated)
        assertEquals(NetworkType.Wifi, state.networkType)
        assertEquals(wifiNetwork, state.network)
    }

    @Test
    fun getBestNetworkState_withMixedValidation_prefersHigherPriority() {
        val wifiCapabilities = capabilities(
            transport = NetworkCapabilities.TRANSPORT_WIFI,
            validated = false,
            internet = true
        )
        val cellularCapabilities = capabilities(
            transport = NetworkCapabilities.TRANSPORT_CELLULAR,
            validated = true,
            internet = true
        )

        val manager = NetworkStateManager(context)
        val activeNetworksField = NetworkStateManager::class.java.getDeclaredField("activeNetworks")
        activeNetworksField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val activeNetworks = activeNetworksField.get(manager) as ConcurrentHashMap<Network, NetworkCapabilities>
        activeNetworks[wifiNetwork] = wifiCapabilities
        activeNetworks[cellularNetwork] = cellularCapabilities

        val state = manager.getBestNetworkState()

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
