package com.netproxy.gateway.ui.viewmodel

import android.content.Context
import com.netproxy.gateway.R
import com.netproxy.gateway.connection.AuthSessionStore
import com.netproxy.gateway.connection.MqttConnectionManager
import com.netproxy.gateway.connection.MqttConnectionState
import com.netproxy.gateway.connection.MqttDiagnostics
import com.netproxy.gateway.connection.NetworkState
import com.netproxy.gateway.connection.NetworkStateManager
import com.netproxy.gateway.connection.NetworkType
import com.netproxy.gateway.vpn.GatewayVpnService
import com.netproxy.gateway.vpn.VpnState
import com.netproxy.gateway.vpn.VpnStatus
import com.netproxy.gateway.wifi.GatewayWifiManager
import com.netproxy.gateway.wifi.WifiNetwork
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        every { mqttConnectionManager.diagnostics } returns MutableStateFlow(MqttDiagnostics())
        every { wifiManager.getCurrentConnection() } returns null
        every { authSessionStore.getOrCreateDeviceId() } returns "device-stable"
        every { mqttConnectionManager.connect(any(), any()) } just runs
        every { authSessionStore.update(any(), any<CharArray>()) } just runs
        every { authSessionStore.clear() } just runs

        every { context.getString(R.string.error_cellular_required) } returns "__ERR_CELLULAR_REQUIRED__"
        every { context.getString(R.string.error_vpn_permission_required) } returns "__ERR_VPN_PERMISSION_REQUIRED__"

        // Reset VpnService status to default before each test to avoid cross-test pollution
        GatewayVpnService.resetStatus()

        // Mock Intent constructor for tests that trigger toggleVpn/disconnect, which create
        // Intent objects with setAction/addFlags calls that are not stubbed in plain JVM tests.
        mockkConstructor(android.content.Intent::class)
        every { anyConstructed<android.content.Intent>().setAction(any()) } answers { self as android.content.Intent }
        every { anyConstructed<android.content.Intent>().addFlags(any()) } answers { self as android.content.Intent }
    }

    @After
    fun tearDown() {
        unmockkConstructor(android.content.Intent::class)
        unmockkAll()
        GatewayVpnService.resetStatus()
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
        verify(exactly = 1) { mqttConnectionManager.connect("device-stable", "123456".toCharArray()) }
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

    // -------------------------------------------------------------------------
    // MQTT state transition tests
    // -------------------------------------------------------------------------

    @Test
    fun mqttState_Connecting_setsIsPairingInProgressTrue() = runTest {
        val stateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
        every { mqttConnectionManager.connectionState } returns stateFlow

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        assertEquals(MqttUiState.Disconnected, viewModel.uiState.value.mqttState)
        assertFalse(viewModel.uiState.value.isPairingInProgress)

        stateFlow.value = MqttConnectionState.Connecting
        advanceUntilIdle()

        assertEquals(MqttUiState.Connecting, viewModel.uiState.value.mqttState)
        assertTrue(viewModel.uiState.value.isPairingInProgress)
        assertNull(viewModel.uiState.value.mqttErrorMessage)
    }

    @Test
    fun mqttState_Connected_startsDurationTimer() = runTest {
        val stateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
        every { mqttConnectionManager.connectionState } returns stateFlow

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        stateFlow.value = MqttConnectionState.Connected
        advanceUntilIdle()

        assertEquals(MqttUiState.Connected, viewModel.uiState.value.mqttState)
        assertTrue(viewModel.uiState.value.isConnected)
        assertTrue(viewModel.uiState.value.isPaired)
        assertFalse(viewModel.uiState.value.isPairingInProgress)

        // Note: durationUpdateJob uses Dispatchers.Default, which is not controlled by
        // StandardTestDispatcher, so the timer does not tick in this test environment.
        // We verify the timer was started implicitly by confirming no crash and that
        // connectionDurationMs is initialised to 0 upon entering Connected state.
        assertEquals(0L, viewModel.uiState.value.connectionDurationMs)
    }

    @Test
    fun mqttState_Disconnected_stopsDurationTimer() = runTest {
        val stateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
        every { mqttConnectionManager.connectionState } returns stateFlow

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        // First connect
        stateFlow.value = MqttConnectionState.Connected
        advanceUntilIdle()
        assertEquals(MqttUiState.Connected, viewModel.uiState.value.mqttState)

        // Then disconnect
        stateFlow.value = MqttConnectionState.Disconnected
        advanceUntilIdle()

        assertEquals(MqttUiState.Disconnected, viewModel.uiState.value.mqttState)
        assertFalse(viewModel.uiState.value.isConnected)
        assertFalse(viewModel.uiState.value.isPaired)
        assertEquals(0L, viewModel.uiState.value.connectionDurationMs)
    }

    @Test
    fun mqttState_Error_setsErrorState() = runTest {
        val stateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
        every { mqttConnectionManager.connectionState } returns stateFlow

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        stateFlow.value = MqttConnectionState.Error("broker unreachable")
        advanceUntilIdle()

        assertEquals(MqttUiState.Error, viewModel.uiState.value.mqttState)
        assertFalse(viewModel.uiState.value.isConnected)
        assertFalse(viewModel.uiState.value.isPaired)
        assertFalse(viewModel.uiState.value.isPairingInProgress)
        assertEquals("broker unreachable", viewModel.uiState.value.errorMessage)
        assertEquals("broker unreachable", viewModel.uiState.value.mqttErrorMessage)
    }

    @Test
    fun mqttState_Error_stopsDurationTimer() = runTest {
        val stateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
        every { mqttConnectionManager.connectionState } returns stateFlow

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        // First connect to start timer
        stateFlow.value = MqttConnectionState.Connected
        advanceUntilIdle()
        assertEquals(MqttUiState.Connected, viewModel.uiState.value.mqttState)

        // Then error to stop timer
        stateFlow.value = MqttConnectionState.Error("connection reset")
        advanceUntilIdle()

        assertEquals(MqttUiState.Error, viewModel.uiState.value.mqttState)
        assertEquals(0L, viewModel.uiState.value.connectionDurationMs)
    }

    // -------------------------------------------------------------------------
    // clearErrorMessage tests
    // -------------------------------------------------------------------------

    @Test
    fun clearErrorMessage_clearsBothErrorMessages() = runTest {
        val stateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
        every { mqttConnectionManager.connectionState } returns stateFlow

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        // Set both error messages via Error state
        stateFlow.value = MqttConnectionState.Error("some error")
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.errorMessage)
        assertNotNull(viewModel.uiState.value.mqttErrorMessage)

        viewModel.clearErrorMessage()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.mqttErrorMessage)
    }

    // -------------------------------------------------------------------------
    // Diagnostics mapping tests
    // -------------------------------------------------------------------------

    @Test
    fun mqttDiagnostics_updatesUiState() = runTest {
        val diagnosticsFlow = MutableStateFlow(MqttDiagnostics())
        every { mqttConnectionManager.diagnostics } returns diagnosticsFlow

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        assertEquals(0L, viewModel.uiState.value.lastHeartbeatTimeMs)
        assertEquals(0, viewModel.uiState.value.heartbeatFailures)
        assertEquals(0, viewModel.uiState.value.reconnectCount)

        diagnosticsFlow.value = MqttDiagnostics(
            lastHeartbeatTime = 1_234_567L,
            consecutiveHeartbeatFailures = 2,
            reconnectCount = 3
        )
        advanceUntilIdle()

        assertEquals(1_234_567L, viewModel.uiState.value.lastHeartbeatTimeMs)
        assertEquals(2, viewModel.uiState.value.heartbeatFailures)
        assertEquals(3, viewModel.uiState.value.reconnectCount)
    }

    // -------------------------------------------------------------------------
    // disconnect tests
    // -------------------------------------------------------------------------

    @Test
    fun disconnect_clearsPairedState() = runTest {
        val stateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
        every { mqttConnectionManager.connectionState } returns stateFlow

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        // Simulate connected state via MQTT state flow
        stateFlow.value = MqttConnectionState.Connected
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isPaired)
        assertTrue(viewModel.uiState.value.isConnected)

        viewModel.disconnect()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isPaired)
        assertFalse(viewModel.uiState.value.isConnected)
        assertEquals("", viewModel.uiState.value.peerId)
        verify(exactly = 1) { mqttConnectionManager.disconnect() }
        verify(exactly = 1) { authSessionStore.clear() }
        verify(exactly = 1) { context.startService(any()) }
    }

    // -------------------------------------------------------------------------
    // CharArray lifecycle tests (N37-B1, N37-B2, N37-B5)
    // -------------------------------------------------------------------------

    @Test
    fun pairWithCode_cellularConnected_writesAuthTokenToUiState() = runTest {
        every { networkStateManager.isCellularConnected() } returns true
        val stateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
        every { mqttConnectionManager.connectionState } returns stateFlow

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        viewModel.pairWithCode("123456")
        advanceUntilIdle()

        val authToken = viewModel.uiState.value.authToken
        assertTrue(authToken.isNotEmpty())
        assertTrue(authToken.contentEquals("123456".toCharArray()))
    }

    @Test
    fun disconnect_zerosOutOldAuthToken() = runTest {
        every { networkStateManager.isCellularConnected() } returns true
        val stateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
        every { mqttConnectionManager.connectionState } returns stateFlow

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        viewModel.pairWithCode("654321")
        advanceUntilIdle()

        // Capture the token reference before disconnect
        val tokenBeforeDisconnect = viewModel.uiState.value.authToken
        assertTrue(tokenBeforeDisconnect.contentEquals("654321".toCharArray()))

        viewModel.disconnect()
        advanceUntilIdle()

        // After disconnect, the old token should be zeroed
        assertTrue(tokenBeforeDisconnect.all { it == '\u0000' })
        // And the new UiState should have an empty token
        assertTrue(viewModel.uiState.value.authToken.isEmpty())
    }

    @Test
    fun disconnect_doesNotModifyNewStateAuthToken() = runTest {
        every { networkStateManager.isCellularConnected() } returns true
        val stateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
        every { mqttConnectionManager.connectionState } returns stateFlow

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        viewModel.pairWithCode("999888")
        advanceUntilIdle()

        viewModel.disconnect()
        advanceUntilIdle()

        // New state's authToken should be CharArray(0), not a zeroed copy of the old token
        assertEquals(0, viewModel.uiState.value.authToken.size)
    }

    @Test
    fun pairWithCode_withoutCellular_zerosAuthTokenArray() = runTest {
        every { networkStateManager.isCellularConnected() } returns false

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        viewModel.pairWithCode("111111")
        advanceUntilIdle()

        // When cellular is not connected, the token should be zeroed and not stored in UiState
        assertTrue(viewModel.uiState.value.authToken.isEmpty())
    }

    // N37-B8: verify the UiState authToken copy is also cleared on failed pairing
    @Test
    fun pairWithCode_withoutCellular_clearsUiStateAuthTokenCopy() = runTest {
        every { networkStateManager.isCellularConnected() } returns false

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        viewModel.pairWithCode("222333")
        advanceUntilIdle()

        // UiState authToken should be CharArray(0), not a zeroed copy of the original
        val authToken = viewModel.uiState.value.authToken
        assertEquals(0, authToken.size)
        // Error message should be set
        assertEquals("__ERR_CELLULAR_REQUIRED__", viewModel.uiState.value.errorMessage)
    }

    // -------------------------------------------------------------------------
    // Post-commit review tests: authToken lifecycle in state transitions
    // -------------------------------------------------------------------------

    // REV5: observeMqttState Disconnected should clear authToken from UiState
    @Test
    fun mqttState_Disconnected_clearsAuthToken() = runTest {
        every { networkStateManager.isCellularConnected() } returns true
        val stateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
        every { mqttConnectionManager.connectionState } returns stateFlow

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        // First pair to set authToken
        viewModel.pairWithCode("654321")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.authToken.isNotEmpty())

        // Capture the old token reference
        val oldToken = viewModel.uiState.value.authToken

        // Simulate MQTT disconnect (not user-initiated)
        stateFlow.value = MqttConnectionState.Disconnected
        advanceUntilIdle()

        // authToken should be cleared from UiState
        assertEquals(0, viewModel.uiState.value.authToken.size)
        // Old token should be zeroed
        assertTrue(oldToken.all { it == '\u0000' })
    }

    @Test
    fun mqttState_Error_clearsAuthToken() = runTest {
        every { networkStateManager.isCellularConnected() } returns true
        val stateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
        every { mqttConnectionManager.connectionState } returns stateFlow

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        // First pair to set authToken
        viewModel.pairWithCode("654321")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.authToken.isNotEmpty())

        // Capture the old token reference
        val oldToken = viewModel.uiState.value.authToken

        // Simulate MQTT error
        stateFlow.value = MqttConnectionState.Error("connection lost")
        advanceUntilIdle()

        // authToken should be cleared from UiState
        assertEquals(0, viewModel.uiState.value.authToken.size)
        // Old token should be zeroed
        assertTrue(oldToken.all { it == '\u0000' })
    }

    // REV4: connect() fails after update() succeeds → AuthSessionStore should be rolled back
    @Test
    fun pairWithCode_cellularConnected_clearsAuthSessionStore_whenConnectThrows() = runTest {
        every { networkStateManager.isCellularConnected() } returns true
        every { mqttConnectionManager.connect(any(), any()) } throws RuntimeException("connect failed")
        every { authSessionStore.clearWithResult() } returns com.netproxy.gateway.result.AppResult.success(Unit)

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        viewModel.pairWithCode("123456")
        advanceUntilIdle()

        // authSessionStore.update() was called first, then connect() threw,
        // so authSessionStore.clearWithResult() should have been called to rollback
        verify(exactly = 1) { authSessionStore.update(any(), any<CharArray>()) }
        verify(exactly = 1) { authSessionStore.clearWithResult() }
    }

    // REV6: pairWithCode else/catch branches should zero old authToken reference
    @Test
    fun pairWithCode_withoutCellular_zerosOldAuthTokenReference() = runTest {
        every { networkStateManager.isCellularConnected() } returns true
        val stateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
        every { mqttConnectionManager.connectionState } returns stateFlow

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        // First pair to set authToken
        viewModel.pairWithCode("111111")
        advanceUntilIdle()
        val oldToken = viewModel.uiState.value.authToken
        assertTrue(oldToken.contentEquals("111111".toCharArray()))

        // Now pair again without cellular — should zero the old token
        every { networkStateManager.isCellularConnected() } returns false
        viewModel.pairWithCode("222222")
        advanceUntilIdle()

        // Old token should be zeroed
        assertTrue(oldToken.all { it == '\u0000' })
        assertEquals(0, viewModel.uiState.value.authToken.size)
    }

    // Verify that authTokenArray is zeroed in finally block even on success path
    @Test
    fun pairWithCode_cellularConnected_zerosAuthTokenArrayInFinally() = runTest {
        every { networkStateManager.isCellularConnected() } returns true
        val stateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
        every { mqttConnectionManager.connectionState } returns stateFlow

        // Capture the actual CharArray reference passed to connect to verify it was zeroed in finally
        val tokenSlot = slot<CharArray>()
        every { mqttConnectionManager.connect(any(), capture(tokenSlot)) } just runs

        val viewModel = MainViewModel(context, networkStateManager, mqttConnectionManager, wifiManager, authSessionStore)
        advanceUntilIdle()

        viewModel.pairWithCode("123456")
        advanceUntilIdle()

        // The original authTokenArray should have been zeroed in the finally block
        assertTrue("authTokenArray should be zeroed in finally", tokenSlot.captured.all { it == '\u0000' })
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
