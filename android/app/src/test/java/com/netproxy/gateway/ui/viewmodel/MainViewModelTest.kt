package com.netproxy.gateway.ui.viewmodel

import android.content.Context
import com.netproxy.gateway.R
import com.netproxy.gateway.connection.AuthSessionStore
import com.netproxy.gateway.connection.MqttConnectionManager
import com.netproxy.gateway.connection.MqttConnectionState
import com.netproxy.gateway.connection.NetworkStateManager
import com.netproxy.gateway.wifi.GatewayWifiManager
import com.netproxy.gateway.wifi.WifiNetwork
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var networkStateManager: NetworkStateManager
    private lateinit var mqttConnectionManager: MqttConnectionManager
    private lateinit var wifiManager: GatewayWifiManager
    private lateinit var authSessionStore: AuthSessionStore

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        networkStateManager = mockk(relaxed = true)
        mqttConnectionManager = mockk(relaxed = true)
        wifiManager = mockk(relaxed = true)
        authSessionStore = mockk(relaxed = true)

        every { networkStateManager.networkState } returns emptyFlow()
        every { mqttConnectionManager.connectionState } returns MutableStateFlow(MqttConnectionState.Disconnected)
        every { wifiManager.getCurrentConnection() } returns null
        every { authSessionStore.getOrCreateDeviceId() } returns "device-stable"
        every { mqttConnectionManager.connect(any(), any()) } just runs
        every { authSessionStore.update(any(), any()) } just runs
        every { authSessionStore.clear() } just runs

        every { context.getString(R.string.error_cellular_required) } returns "__ERR_CELLULAR_REQUIRED__"
        every { context.getString(R.string.error_vpn_permission_required) } returns "__ERR_VPN_PERMISSION_REQUIRED__"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun pairWithCode_cellularConnected_doesNotMarkPairedBeforeMqttConnected() = runTest {
        every { networkStateManager.isCellularConnected() } returns true
        val stateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
        every { mqttConnectionManager.connectionState } returns stateFlow

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)

        viewModel.pairWithCode("123456")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals("123456", uiState.peerId)
        assertFalse(uiState.isPaired)
        verify(exactly = 1) { mqttConnectionManager.connect("device-stable", "123456") }
    }

    @Test
    fun pairWithCode_marksPairedAfterMqttConnectedState() = runTest {
        every { networkStateManager.isCellularConnected() } returns true
        val stateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
        every { mqttConnectionManager.connectionState } returns stateFlow

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)

        viewModel.pairWithCode("654321")
        stateFlow.value = MqttConnectionState.Connected
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isPaired)
    }

    @Test
    fun pairWithCode_withoutCellular_setsErrorAndSkipsMqttConnect() = runTest {
        every { networkStateManager.isCellularConnected() } returns false

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)

        viewModel.pairWithCode("111111")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertFalse(uiState.isPaired)
        assertEquals("__ERR_CELLULAR_REQUIRED__", uiState.errorMessage)
        verify(exactly = 1) { context.getString(R.string.error_cellular_required) }
        verify(exactly = 0) { mqttConnectionManager.connect(any(), any()) }
    }

    @Test
    fun init_usesStableDeviceIdFromStore() = runTest {
        every { authSessionStore.getOrCreateDeviceId() } returns "persisted-device-id"

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        assertEquals("persisted-device-id", viewModel.uiState.value.deviceId)
    }

    @Test
    fun scanWifi_updatesWifiNetworksFromScanResultsFlow() = runTest {
        val cached = listOf(sampleWifiNetwork("cached"))
        val fresh = listOf(sampleWifiNetwork("fresh"))
        val scanFlow = MutableSharedFlow<List<WifiNetwork>>(replay = 1)
        scanFlow.tryEmit(cached)

        every { wifiManager.wifiScanResults } returns scanFlow
        every { wifiManager.startScan() } answers {
            backgroundScope.launch { scanFlow.emit(fresh) }
            true
        }

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)

        viewModel.scanWifi()
        advanceUntilIdle()

        assertEquals(fresh, viewModel.uiState.value.wifiNetworks)
        verify(exactly = 1) { wifiManager.startScan() }
    }

    @Test
    fun scanWifi_onTimeout_fallsBackToGetScanResults() = runTest {
        val cached = listOf(sampleWifiNetwork("cached"))
        val fallback = listOf(sampleWifiNetwork("fallback"))
        val scanFlow = MutableSharedFlow<List<WifiNetwork>>(replay = 1)
        scanFlow.tryEmit(cached)

        every { wifiManager.wifiScanResults } returns scanFlow
        every { wifiManager.startScan() } returns true
        every { wifiManager.getScanResults() } returns fallback

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)

        viewModel.scanWifi()
        testScheduler.advanceTimeBy(10_001)
        advanceUntilIdle()

        assertEquals(fallback, viewModel.uiState.value.wifiNetworks)
        verify(exactly = 1) { wifiManager.getScanResults() }
    }

    private fun sampleWifiNetwork(ssid: String): WifiNetwork {
        return WifiNetwork(
            ssid = ssid,
            bssid = "00:11:22:33:44:55",
            signalStrength = -50,
            frequency = 2437,
            capabilities = "[WPA2-PSK-CCMP]",
            isSecure = true
        )
    }
}
