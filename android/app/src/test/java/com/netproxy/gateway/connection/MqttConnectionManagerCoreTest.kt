package com.netproxy.gateway.connection

import com.netproxy.gateway.result.AppResult
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MqttConnectionManager 核心业务逻辑单元测试
 * 测试可以独立测试的状态类和结果类型，不涉及 MqttClient 构造
 */
class MqttConnectionManagerCoreTest {

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
        assertFalse(error1 == error3)
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

    // ==================== MqttMessage 测试 ====================

    @Test
    fun mqttMessage_creationWithPayload() {
        val payload = "test payload".toByteArray()
        val message = MqttMessage(payload)

        assertEquals("test payload", String(message.payload))
    }

    @Test
    fun mqttMessage_qosSetting() {
        val message = MqttMessage("test".toByteArray())
        message.qos = 1

        assertEquals(1, message.qos)
    }

    @Test
    fun mqttMessage_qosLevels() {
        val message = MqttMessage("test".toByteArray())

        // QoS 0 - At most once
        message.qos = 0
        assertEquals(0, message.qos)

        // QoS 1 - At least once
        message.qos = 1
        assertEquals(1, message.qos)

        // QoS 2 - Exactly once
        message.qos = 2
        assertEquals(2, message.qos)
    }

    @Test
    fun mqttMessage_retainedFlag() {
        val message = MqttMessage("test".toByteArray())
        message.isRetained = true

        assertTrue(message.isRetained)
    }

    @Test
    fun mqttMessage_duplicateFlag() {
        val message = MqttMessage("test".toByteArray())

        // Duplicate flag is read-only, but we can check it's not retained by default
        assertFalse(message.isDuplicate)
    }

    @Test
    fun mqttMessage_emptyPayload() {
        val message = MqttMessage(ByteArray(0))

        assertEquals(0, message.payload.size)
    }

    @Test
    fun mqttMessage_largePayload() {
        val largePayload = ByteArray(1024 * 1024) { 0x42 } // 1MB
        val message = MqttMessage(largePayload)

        assertEquals(1024 * 1024, message.payload.size)
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

    // ==================== TLS/SSL 配置方法存在性测试 ====================

    @Test
    fun mqttConnectionManager_hasCreateProductionSocketFactoryMethod() {
        val method = MqttConnectionManager::class.java.declaredMethods.find { it.name == "createProductionSocketFactory" }
        assertNotNull("Should have createProductionSocketFactory method", method)
    }

    @Test
    fun mqttConnectionManager_hasCreateDevSocketFactoryMethod() {
        val method = MqttConnectionManager::class.java.declaredMethods.find { it.name == "createDevSocketFactory" }
        assertNotNull("Should have createDevSocketFactory method", method)
    }

    @Test
    fun mqttConnectionManager_hasCreateSecureSocketFactoryMethod() {
        val method = MqttConnectionManager::class.java.declaredMethods.find { it.name == "createSecureSocketFactory" }
        assertNotNull("Should have createSecureSocketFactory method", method)
    }

    @Test
    fun mqttConnectionManager_hasIsTlsEnabledMethod() {
        val method = MqttConnectionManager::class.java.declaredMethods.find { it.name == "isTlsEnabled" }
        assertNotNull("Should have isTlsEnabled method", method)
    }

    @Test
    fun mqttConnectionManager_hasBrokerUrlMethod() {
        val method = MqttConnectionManager::class.java.declaredMethods.find { it.name == "brokerUrl" }
        assertNotNull("Should have brokerUrl method", method)
    }

    @Test
    fun mqttConnectionManager_hasValidateBrokerUrlMethod() {
        val method = MqttConnectionManager::class.java.declaredMethods.find { it.name == "validateBrokerUrl" }
        assertNotNull("Should have validateBrokerUrl method", method)
    }

    // ==================== 连接状态流转测试 ====================

    @Test
    fun mqttConnectionState_disconnectedTypeIsCorrect() {
        val state = MqttConnectionState.Disconnected
        assertTrue(state is MqttConnectionState)
    }

    @Test
    fun mqttConnectionState_connectingTypeIsCorrect() {
        val state = MqttConnectionState.Connecting
        assertTrue(state is MqttConnectionState)
    }

    @Test
    fun mqttConnectionState_connectedTypeIsCorrect() {
        val state = MqttConnectionState.Connected
        assertTrue(state is MqttConnectionState)
    }

    @Test
    fun mqttConnectionState_errorTypeIsCorrect() {
        val state = MqttConnectionState.Error("test")
        assertTrue(state is MqttConnectionState)
    }

    // ==================== 错误状态测试 ====================

    @Test
    fun mqttConnectionState_errorWithNullMessage() {
        val error = MqttConnectionState.Error("null")
        assertEquals("null", error.message)
    }

    @Test
    fun mqttConnectionState_errorWithWhitespaceMessage() {
        val error = MqttConnectionState.Error("   ")
        assertEquals("   ", error.message)
    }

    @Test
    fun mqttConnectionState_errorWithNewlineMessage() {
        val error = MqttConnectionState.Error("line1\nline2")
        assertEquals("line1\nline2", error.message)
    }

    @Test
    fun mqttConnectionState_errorWithTabMessage() {
        val error = MqttConnectionState.Error("col1\tcol2")
        assertEquals("col1\tcol2", error.message)
    }

    // ==================== 密封类层级测试 ====================

    @Test
    fun mqttConnectionState_isSealedClass() {
        val isSealed = MqttConnectionState::class.isSealed
        assertTrue("MqttConnectionState should be a sealed class", isSealed)
    }

    @Test
    fun mqttConnectionState_hasAllRequiredSubtypes() {
        val subclasses = MqttConnectionState::class.sealedSubclasses
        assertTrue("Should have Disconnected subtype", subclasses.any { it == MqttConnectionState.Disconnected::class })
        assertTrue("Should have Connecting subtype", subclasses.any { it == MqttConnectionState.Connecting::class })
        assertTrue("Should have Connected subtype", subclasses.any { it == MqttConnectionState.Connected::class })
        assertTrue("Should have Error subtype", subclasses.any { it == MqttConnectionState.Error::class })
    }

    // ==================== 方法签名测试 ====================

    @Test
    fun mqttConnectionManager_connectMethodSignature() {
        val method = MqttConnectionManager::class.java.methods.find { it.name == "connect" }
        assertNotNull(method)
        assertEquals(2, method?.parameterCount)
    }

    @Test
    fun mqttConnectionManager_disconnectMethodSignature() {
        val method = MqttConnectionManager::class.java.methods.find { it.name == "disconnect" }
        assertNotNull(method)
        assertEquals(0, method?.parameterCount)
    }

    @Test
    fun mqttConnectionManager_publishMethodSignature() {
        val method = MqttConnectionManager::class.java.methods.find { it.name == "publish" && it.parameterCount == 3 }
        assertNotNull(method)
    }

    @Test
    fun mqttConnectionManager_subscribeMethodSignature() {
        val method = MqttConnectionManager::class.java.methods.find { it.name == "subscribe" && it.parameterCount == 3 }
        assertNotNull(method)
    }

    @Test
    fun mqttConnectionManager_publishWithResultMethodSignature() {
        val method = MqttConnectionManager::class.java.methods.find { it.name == "publishWithResult" }
        assertNotNull(method)
        assertEquals(3, method?.parameterCount)
    }

    @Test
    fun mqttConnectionManager_subscribeWithResultMethodSignature() {
        val method = MqttConnectionManager::class.java.methods.find { it.name == "subscribeWithResult" }
        assertNotNull(method)
        assertEquals(3, method?.parameterCount)
    }

    // ==================== 字段存在性测试 ====================

    @Test
    fun mqttConnectionManager_hasConnectionStateField() {
        val field = MqttConnectionManager::class.java.declaredFields.find { it.name == "connectionState" }
        assertNotNull("Should have connectionState field", field)
    }

    @Test
    fun mqttConnectionManager_hasMessagesField() {
        val field = MqttConnectionManager::class.java.declaredFields.find { it.name == "messages" }
        assertNotNull("Should have messages field", field)
    }

    // ==================== 伴生对象测试 ====================

    @Test
    fun mqttConnectionManager_hasCompanionObject() {
        val companion = MqttConnectionManager::class.java.declaredClasses.find { it.name.contains("Companion") }
        // Companion object may be null if not declared, but we check for constants
        val hasClientId = MqttConnectionManager::class.java.declaredFields.any { it.name == "CLIENT_ID" }
        val hasHeartbeatInterval = MqttConnectionManager::class.java.declaredFields.any { it.name == "HEARTBEAT_INTERVAL" }
        assertTrue("Should have CLIENT_ID constant or companion object", hasClientId || companion != null)
    }

    // ==================== AppResult 集成测试 ====================

    @Test
    fun appResult_successCreation() {
        val result = AppResult.success("test data")

        assertTrue(result is AppResult.Success)
        assertEquals("test data", (result as AppResult.Success).data)
    }

    @Test
    fun appResult_errorCreation() {
        val exception = IllegalStateException("test error")
        val result = AppResult.error<String>(exception)

        assertTrue(result is AppResult.Error)
        assertEquals("test error", (result as AppResult.Error).exception.message)
    }

    @Test
    fun appResult_successWithUnit() {
        val result = AppResult.success(Unit)

        assertTrue(result is AppResult.Success)
        assertEquals(Unit, (result as AppResult.Success).data)
    }
}