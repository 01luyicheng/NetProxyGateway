package com.netproxy.gateway.di

import com.netproxy.gateway.connection.MqttConnectionState
import com.netproxy.gateway.ui.viewmodel.UiState
import com.netproxy.gateway.vpn.VpnState
import com.netproxy.gateway.vpn.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ModuleCoordinator 的单元测试
 * 测试模块间通信和状态同步功能
 */
class ModuleCoordinatorTest {

    private lateinit var coordinatorScope: CoroutineScope
    private lateinit var moduleCoordinator: ModuleCoordinator

    @Before
    fun setup() {
        coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        moduleCoordinator = ModuleCoordinator(coordinatorScope)
    }

    // ==================== 初始状态测试 ====================

    @Test
    fun connectionState_initialValueIsDisconnected() {
        assertEquals(MqttConnectionState.Disconnected, moduleCoordinator.connectionState.value)
    }

    @Test
    fun vpnState_initialValueIsDefault() {
        val initialState = moduleCoordinator.vpnState.value
        assertEquals(VpnState.STOPPED, initialState.state)
        assertEquals(0, initialState.connectedClients)
        assertNull(initialState.errorMessage)
    }

    @Test
    fun wifiState_initialValueIsDisconnected() {
        assertEquals(ModuleCoordinator.WiFiState.Disconnected, moduleCoordinator.wifiState.value)
    }

    @Test
    fun uiState_initialValueIsDefault() {
        val initialState = moduleCoordinator.uiState.value
        assertEquals(false, initialState.isConnected)
        assertEquals(false, initialState.isPaired)
        assertEquals("", initialState.deviceId)
    }

    // ==================== 状态更新测试 ====================

    @Test
    fun updateConnectionState_updatesState() {
        val newState = MqttConnectionState.Connected
        moduleCoordinator.updateConnectionState(newState)

        assertEquals(newState, moduleCoordinator.connectionState.value)
    }

    @Test
    fun updateVpnState_updatesState() {
        val newState = VpnStatus(state = VpnState.RUNNING, connectedClients = 5)
        moduleCoordinator.updateVpnState(newState)

        assertEquals(newState, moduleCoordinator.vpnState.value)
    }

    @Test
    fun updateWifiState_connected_updatesState() {
        moduleCoordinator.updateWifiState(true, "TestSSID")

        val wifiState = moduleCoordinator.wifiState.value
        assertTrue(wifiState is ModuleCoordinator.WiFiState.Connected)
        assertEquals("TestSSID", (wifiState as ModuleCoordinator.WiFiState.Connected).ssid)
    }

    @Test
    fun updateWifiState_disconnected_updatesState() {
        moduleCoordinator.updateWifiState(false, null)

        val wifiState = moduleCoordinator.wifiState.value
        assertEquals(ModuleCoordinator.WiFiState.Disconnected, wifiState)
    }

    @Test
    fun updateUiState_updatesState() {
        val newState = UiState(isConnected = true, deviceId = "test-device")

        moduleCoordinator.updateUiState(newState)

        assertEquals(newState, moduleCoordinator.uiState.value)
    }

    // ==================== 连接状态流转测试 ====================

    @Test
    fun updateConnectionState_throughAllStates() {
        moduleCoordinator.updateConnectionState(MqttConnectionState.Connecting)
        assertEquals(MqttConnectionState.Connecting, moduleCoordinator.connectionState.value)

        moduleCoordinator.updateConnectionState(MqttConnectionState.Connected)
        assertEquals(MqttConnectionState.Connected, moduleCoordinator.connectionState.value)

        moduleCoordinator.updateConnectionState(MqttConnectionState.Error("test error"))
        assertTrue(moduleCoordinator.connectionState.value is MqttConnectionState.Error)

        moduleCoordinator.updateConnectionState(MqttConnectionState.Disconnected)
        assertEquals(MqttConnectionState.Disconnected, moduleCoordinator.connectionState.value)
    }

    @Test
    fun updateVpnState_withErrorMessage() {
        val errorState = VpnStatus(
            state = VpnState.ERROR,
            errorMessage = "Test error message",
            connectedClients = 0
        )

        moduleCoordinator.updateVpnState(errorState)

        assertEquals(errorState, moduleCoordinator.vpnState.value)
    }

    @Test
    fun updateWifiState_withEmptySsid() {
        moduleCoordinator.updateWifiState(true, "")

        val wifiState = moduleCoordinator.wifiState.value
        assertTrue(wifiState is ModuleCoordinator.WiFiState.Connected)
        assertEquals("", (wifiState as ModuleCoordinator.WiFiState.Connected).ssid)
    }

    // ==================== 模块注册测试 ====================

    @Test
    fun registerModule_storesModule() {
        val testModule = "test-module"

        moduleCoordinator.registerModule("test", testModule)

        val retrieved = moduleCoordinator.getModule<String>("test")
        assertEquals(testModule, retrieved)
    }

    @Test
    fun getModule_returnsNullForUnregisteredModule() {
        val retrieved = moduleCoordinator.getModule<String>("nonexistent")
        assertNull(retrieved)
    }

    @Test
    fun registerMultipleModules_allRetrievable() {
        moduleCoordinator.registerModule("module1", "value1")
        moduleCoordinator.registerModule("module2", 42)
        moduleCoordinator.registerModule("module3", listOf("a", "b"))

        assertEquals("value1", moduleCoordinator.getModule<String>("module1"))
        assertEquals(42, moduleCoordinator.getModule<Int>("module2"))
        assertEquals(listOf("a", "b"), moduleCoordinator.getModule<List<String>>("module3"))
    }

    @Test
    fun registerModule_overwritesExisting() {
        moduleCoordinator.registerModule("test", "first")
        moduleCoordinator.registerModule("test", "second")

        assertEquals("second", moduleCoordinator.getModule<String>("test"))
    }

    // ==================== WiFi 状态密封类测试 ====================

    @Test
    fun wifiState_sealedClassEquality() {
        val connected1 = ModuleCoordinator.WiFiState.Connected("SSID")
        val connected2 = ModuleCoordinator.WiFiState.Connected("SSID")
        val connected3 = ModuleCoordinator.WiFiState.Connected("Different")

        assertEquals(connected1, connected2)
        assertTrue(connected1 != connected3)
        assertTrue(ModuleCoordinator.WiFiState.Disconnected != connected1)
    }

    @Test
    fun wifiState_connectedProperties() {
        val connected = ModuleCoordinator.WiFiState.Connected("MyNetwork")

        assertEquals("MyNetwork", connected.ssid)
    }

    @Test
    fun wifiState_connectedCopy() {
        val connected = ModuleCoordinator.WiFiState.Connected("Original")
        val copied = connected.copy(ssid = "Copied")

        assertEquals("Copied", copied.ssid)
    }

    @Test
    fun wifiState_connectedComponentFunction() {
        val connected = ModuleCoordinator.WiFiState.Connected("TestSSID")
        val (ssid) = connected

        assertEquals("TestSSID", ssid)
    }

    @Test
    fun wifiState_connectedToString() {
        val connected = ModuleCoordinator.WiFiState.Connected("Test")
        val str = connected.toString()

        assertTrue(str.contains("Test"))
    }

    @Test
    fun wifiState_disconnectedToString() {
        val disconnected = ModuleCoordinator.WiFiState.Disconnected
        val str = disconnected.toString()

        assertTrue(str.contains("Disconnected"))
    }

    // ==================== 事件发布测试 ====================

    @Test
    fun onAppStarted_doesNotThrow() {
        // Should not throw
        moduleCoordinator.onAppStarted()
    }

    @Test
    fun onAppStopped_doesNotThrow() {
        // Should not throw
        moduleCoordinator.onAppStopped()
    }

    @Test
    fun publishEvent_doesNotThrow() {
        // Should not throw
        moduleCoordinator.publishEvent(AppEvent.AppStarted)
        moduleCoordinator.publishEvent(AppEvent.AppStopped)
        moduleCoordinator.publishEvent(AppEvent.MessageReceived("topic", "payload"))
    }

    // ==================== AppEvent 密封类测试 ====================

    @Test
    fun appEvent_connectionStateChange() {
        val event = AppEvent.ConnectionStateChange(MqttConnectionState.Connected)

        assertEquals(MqttConnectionState.Connected, event.state)
    }

    @Test
    fun appEvent_vpnStateChange() {
        val status = VpnStatus(state = VpnState.RUNNING)
        val event = AppEvent.VpnStateChange(status)

        assertEquals(status, event.state)
    }

    @Test
    fun appEvent_wifiStateChange_connected() {
        val event = AppEvent.WiFiStateChange(true, "SSID")

        assertEquals(true, event.connected)
        assertEquals("SSID", event.ssid)
    }

    @Test
    fun appEvent_wifiStateChange_disconnected() {
        val event = AppEvent.WiFiStateChange(false, null)

        assertEquals(false, event.connected)
        assertNull(event.ssid)
    }

    @Test
    fun appEvent_messageReceived() {
        val event = AppEvent.MessageReceived("test/topic", "test payload")

        assertEquals("test/topic", event.topic)
        assertEquals("test payload", event.payload)
    }

    @Test
    fun appEvent_appStartedIsObject() {
        val event1 = AppEvent.AppStarted
        val event2 = AppEvent.AppStarted

        assertTrue(event1 === event2)
    }

    @Test
    fun appEvent_appStoppedIsObject() {
        val event1 = AppEvent.AppStopped
        val event2 = AppEvent.AppStopped

        assertTrue(event1 === event2)
    }

    // ==================== 数据类行为测试 ====================

    @Test
    fun appEvent_connectionStateChangeEquality() {
        val event1 = AppEvent.ConnectionStateChange(MqttConnectionState.Connected)
        val event2 = AppEvent.ConnectionStateChange(MqttConnectionState.Connected)
        val event3 = AppEvent.ConnectionStateChange(MqttConnectionState.Disconnected)

        assertEquals(event1, event2)
        assertTrue(event1 != event3)
    }

    @Test
    fun appEvent_connectionStateChangeCopy() {
        val event = AppEvent.ConnectionStateChange(MqttConnectionState.Connected)
        val copied = event.copy(state = MqttConnectionState.Disconnected)

        assertEquals(MqttConnectionState.Disconnected, copied.state)
    }

    @Test
    fun appEvent_wifiStateChangeEquality() {
        val event1 = AppEvent.WiFiStateChange(true, "SSID")
        val event2 = AppEvent.WiFiStateChange(true, "SSID")
        val event3 = AppEvent.WiFiStateChange(false, null)

        assertEquals(event1, event2)
        assertTrue(event1 != event3)
    }

    @Test
    fun appEvent_messageReceivedEquality() {
        val event1 = AppEvent.MessageReceived("topic", "payload")
        val event2 = AppEvent.MessageReceived("topic", "payload")
        val event3 = AppEvent.MessageReceived("other", "payload")

        assertEquals(event1, event2)
        assertTrue(event1 != event3)
    }

    @Test
    fun appEvent_messageReceivedCopy() {
        val event = AppEvent.MessageReceived("original", "payload")
        val copied = event.copy(topic = "copied")

        assertEquals("copied", copied.topic)
        assertEquals("payload", copied.payload)
    }

    // ==================== 类结构测试 ====================

    @Test
    fun moduleCoordinator_isAnnotatedWithSingleton() {
        val annotations = ModuleCoordinator::class.java.annotations
        val hasSingleton = annotations.any { it.annotationClass.simpleName == "Singleton" }
        assertTrue("ModuleCoordinator should be annotated with @Singleton", hasSingleton)
    }

    @Test
    fun moduleCoordinator_hasInjectConstructor() {
        val constructors = ModuleCoordinator::class.java.constructors
        assertTrue("ModuleCoordinator should have at least one constructor", constructors.isNotEmpty())
    }

    @Test
    fun moduleCoordinator_hasRequiredMethods() {
        val methods = ModuleCoordinator::class.java.methods.map { it.name }

        assertTrue("Should have publishEvent method", methods.contains("publishEvent"))
        assertTrue("Should have updateConnectionState method", methods.contains("updateConnectionState"))
        assertTrue("Should have updateVpnState method", methods.contains("updateVpnState"))
        assertTrue("Should have updateWifiState method", methods.contains("updateWifiState"))
        assertTrue("Should have updateUiState method", methods.contains("updateUiState"))
        assertTrue("Should have registerModule method", methods.contains("registerModule"))
        assertTrue("Should have getModule method", methods.contains("getModule"))
    }

    @Test
    fun moduleCoordinator_hasStateFlowProperties() {
        val methods = ModuleCoordinator::class.java.methods.map { it.name }

        assertTrue("Should have getConnectionState method", methods.contains("getConnectionState"))
        assertTrue("Should have getVpnState method", methods.contains("getVpnState"))
        assertTrue("Should have getWifiState method", methods.contains("getWifiState"))
        assertTrue("Should have getUiState method", methods.contains("getUiState"))
        assertTrue("Should have getEvents method", methods.contains("getEvents"))
    }

    // ==================== 边界条件测试 ====================

    @Test
    fun updateWifiState_withNullSsid() {
        moduleCoordinator.updateWifiState(true, null)

        val wifiState = moduleCoordinator.wifiState.value
        assertTrue(wifiState is ModuleCoordinator.WiFiState.Connected)
        assertEquals("", (wifiState as ModuleCoordinator.WiFiState.Connected).ssid)
    }

    @Test
    fun updateVpnState_withNegativeClients() {
        val status = VpnStatus(state = VpnState.RUNNING, connectedClients = -1)
        moduleCoordinator.updateVpnState(status)

        assertEquals(-1, moduleCoordinator.vpnState.value.connectedClients)
    }

    @Test
    fun updateVpnState_withMaxIntClients() {
        val status = VpnStatus(state = VpnState.RUNNING, connectedClients = Int.MAX_VALUE)
        moduleCoordinator.updateVpnState(status)

        assertEquals(Int.MAX_VALUE, moduleCoordinator.vpnState.value.connectedClients)
    }

    @Test
    fun updateConnectionState_errorWithEmptyMessage() {
        moduleCoordinator.updateConnectionState(MqttConnectionState.Error(""))

        val state = moduleCoordinator.connectionState.value
        assertTrue(state is MqttConnectionState.Error)
        assertEquals("", (state as MqttConnectionState.Error).message)
    }

    @Test
    fun updateConnectionState_errorWithLongMessage() {
        val longMessage = "error: ".repeat(100)
        moduleCoordinator.updateConnectionState(MqttConnectionState.Error(longMessage))

        val state = moduleCoordinator.connectionState.value
        assertTrue(state is MqttConnectionState.Error)
        assertEquals(longMessage, (state as MqttConnectionState.Error).message)
    }

    // ==================== 事件流存在性测试 ====================

    @Test
    fun eventsFlow_exists() {
        val events = moduleCoordinator.events
        assertNotNull(events)
    }

    @Test
    fun connectionStateFlow_exists() {
        val state = moduleCoordinator.connectionState
        assertNotNull(state)
    }

    @Test
    fun vpnStateFlow_exists() {
        val state = moduleCoordinator.vpnState
        assertNotNull(state)
    }

    @Test
    fun wifiStateFlow_exists() {
        val state = moduleCoordinator.wifiState
        assertNotNull(state)
    }

    @Test
    fun uiStateFlow_exists() {
        val state = moduleCoordinator.uiState
        assertNotNull(state)
    }
}
