package com.netproxy.gateway.connection

import com.netproxy.gateway.result.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MqttConnectionManager 核心业务逻辑单元测试
 * 测试可以独立测试的状态类和结果类型，不涉及 MqttClient 构造
 */
class MqttConnectionManagerCoreTest {

    // ==================== MqttConnectionState.Error 业务逻辑测试 ====================

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

    // ==================== 状态类型正确性测试 ====================

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
