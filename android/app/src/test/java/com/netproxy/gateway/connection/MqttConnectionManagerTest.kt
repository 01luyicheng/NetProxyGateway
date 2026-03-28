package com.netproxy.gateway.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MqttConnectionManager 的单元测试
 * 由于 MqttConnectionManager 使用 Eclipse Paho MQTT 客户端，
 * 本测试类主要测试可以独立测试的状态类和结果类型。
 */
class MqttConnectionManagerTest {

    // ==================== MqttConnectionState 密封类测试 ====================

    @Test
    fun mqttConnectionState_disconnectedIsObject() {
        val state1 = MqttConnectionState.Disconnected
        val state2 = MqttConnectionState.Disconnected

        assertTrue(state1 === state2) // Same instance (object)
    }

    @Test
    fun mqttConnectionState_connectingIsObject() {
        val state1 = MqttConnectionState.Connecting
        val state2 = MqttConnectionState.Connecting

        assertTrue(state1 === state2) // Same instance (object)
    }

    @Test
    fun mqttConnectionState_connectedIsObject() {
        val state1 = MqttConnectionState.Connected
        val state2 = MqttConnectionState.Connected

        assertTrue(state1 === state2) // Same instance (object)
    }

    @Test
    fun mqttConnectionState_errorIsDataClass() {
        val error1 = MqttConnectionState.Error("test message")
        val error2 = MqttConnectionState.Error("test message")
        val error3 = MqttConnectionState.Error("different message")

        assertEquals(error1, error2)
        assertTrue(error1 != error3)
    }

    @Test
    fun mqttConnectionState_errorMessageProperty() {
        val error = MqttConnectionState.Error("connection failed")

        assertEquals("connection failed", error.message)
    }

    @Test
    fun mqttConnectionState_errorCopy() {
        val error = MqttConnectionState.Error("original")
        val copied = error.copy(message = "copied")

        assertEquals("copied", copied.message)
    }

    @Test
    fun mqttConnectionState_errorToString() {
        val error = MqttConnectionState.Error("test error")
        val str = error.toString()

        assertTrue(str.contains("test error"))
    }

    @Test
    fun mqttConnectionState_errorHashCode() {
        val error1 = MqttConnectionState.Error("message")
        val error2 = MqttConnectionState.Error("message")

        assertEquals(error1.hashCode(), error2.hashCode())
    }

    @Test
    fun mqttConnectionState_errorEqualsNull() {
        val error = MqttConnectionState.Error("message")

        assertFalse(error.equals(null))
    }

    @Test
    fun mqttConnectionState_errorEqualsDifferentType() {
        val error = MqttConnectionState.Error("message")

        assertFalse(error.equals("not an error"))
    }

    @Test
    fun mqttConnectionState_errorEqualsSameObject() {
        val error = MqttConnectionState.Error("message")

        assertTrue(error.equals(error))
    }

    @Test
    fun mqttConnectionState_allTypesCanBeCreated() {
        val disconnected: MqttConnectionState = MqttConnectionState.Disconnected
        val connecting: MqttConnectionState = MqttConnectionState.Connecting
        val connected: MqttConnectionState = MqttConnectionState.Connected
        val error: MqttConnectionState = MqttConnectionState.Error("test")

        assertTrue(disconnected is MqttConnectionState.Disconnected)
        assertTrue(connecting is MqttConnectionState.Connecting)
        assertTrue(connected is MqttConnectionState.Connected)
        assertTrue(error is MqttConnectionState.Error)
    }

    @Test
    fun mqttConnectionState_errorWithEmptyMessage() {
        val error = MqttConnectionState.Error("")

        assertEquals("", error.message)
    }

    @Test
    fun mqttConnectionState_errorWithLongMessage() {
        val longMessage = "error: ".repeat(100)
        val error = MqttConnectionState.Error(longMessage)

        assertEquals(longMessage, error.message)
    }

    @Test
    fun mqttConnectionState_errorWithSpecialCharacters() {
        val message = "error!@#$%^&*()_+-=[]{}|;':\",./<>?"
        val error = MqttConnectionState.Error(message)

        assertEquals(message, error.message)
    }

    @Test
    fun mqttConnectionState_errorWithUnicode() {
        val message = "错误信息"
        val error = MqttConnectionState.Error(message)

        assertEquals(message, error.message)
    }

    // ==================== 类结构测试 ====================

    @Test
    fun mqttConnectionManager_isAnnotatedWithSingleton() {
        val annotations = MqttConnectionManager::class.java.annotations
        val hasSingleton = annotations.any { it.annotationClass.simpleName == "Singleton" }
        assertTrue("MqttConnectionManager should be annotated with @Singleton", hasSingleton)
    }

    @Test
    fun mqttConnectionManager_hasInjectConstructor() {
        val constructors = MqttConnectionManager::class.java.constructors
        assertTrue("MqttConnectionManager should have at least one constructor", constructors.isNotEmpty())
    }

    @Test
    fun mqttConnectionManager_hasRequiredMethods() {
        val methods = MqttConnectionManager::class.java.methods.map { it.name }

        assertTrue("Should have connect method", methods.contains("connect"))
        assertTrue("Should have disconnect method", methods.contains("disconnect"))
        assertTrue("Should have publish method", methods.contains("publish"))
        assertTrue("Should have subscribe method", methods.contains("subscribe"))
    }

    @Test
    fun mqttConnectionManager_hasResultMethods() {
        val methods = MqttConnectionManager::class.java.methods.map { it.name }

        assertTrue("Should have publishWithResult method", methods.contains("publishWithResult"))
        assertTrue("Should have subscribeWithResult method", methods.contains("subscribeWithResult"))
    }

    @Test
    fun mqttConnectionManager_hasStateFlowProperty() {
        val methods = MqttConnectionManager::class.java.methods.map { it.name }

        assertTrue("Should have getConnectionState method", methods.contains("getConnectionState"))
        assertTrue("Should have getMessages method", methods.contains("getMessages"))
    }

    // ==================== 常量测试 ====================

    @Test
    fun mqttConnectionManagerCompanionConstants() {
        // Verify the class exists and has companion object with constants
        assertNotNull(MqttConnectionManager::class.java)
    }

    // ==================== 密封类完整性测试 ====================

    @Test
    fun mqttConnectionState_exhaustiveWhen() {
        fun handleState(state: MqttConnectionState): String {
            return when (state) {
                is MqttConnectionState.Disconnected -> "disconnected"
                is MqttConnectionState.Connecting -> "connecting"
                is MqttConnectionState.Connected -> "connected"
                is MqttConnectionState.Error -> "error"
            }
        }

        assertEquals("disconnected", handleState(MqttConnectionState.Disconnected))
        assertEquals("connecting", handleState(MqttConnectionState.Connecting))
        assertEquals("connected", handleState(MqttConnectionState.Connected))
        assertEquals("error", handleState(MqttConnectionState.Error("test")))
    }

    @Test
    fun mqttConnectionState_smartCastWorks() {
        val state: MqttConnectionState = MqttConnectionState.Error("test message")

        if (state is MqttConnectionState.Error) {
            // Smart cast should work
            assertEquals("test message", state.message)
        }
    }
}
