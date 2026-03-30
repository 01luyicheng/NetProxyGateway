package com.netproxy.gateway.ui.viewmodel

import android.content.Context
import com.netproxy.gateway.connection.AuthSessionStore
import com.netproxy.gateway.connection.MqttConnectionManager
import com.netproxy.gateway.connection.MqttConnectionState
import com.netproxy.gateway.connection.NetworkStateManager
import com.netproxy.gateway.wifi.GatewayWifiManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
        assertEquals("需要蜂窝网络连接", uiState.errorMessage)
        verify(exactly = 0) { mqttConnectionManager.connect(any(), any()) }
    }

    @Test
    fun init_usesStableDeviceIdFromStore() = runTest {
        every { authSessionStore.getOrCreateDeviceId() } returns "persisted-device-id"

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        assertEquals("persisted-device-id", viewModel.uiState.value.deviceId)
    }
}
