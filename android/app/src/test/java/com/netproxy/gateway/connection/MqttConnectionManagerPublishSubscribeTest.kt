package com.netproxy.gateway.connection

import android.content.Context
import com.netproxy.gateway.result.AppResult
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MqttConnectionManagerPublishSubscribeTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var context: Context
    private lateinit var manager: MqttConnectionManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        testScope = TestScope(testDispatcher)
        context = mockk(relaxed = true)
        manager = MqttConnectionManager(context, testScope)
    }

    @After
    fun tearDown() {
        manager.disconnect()
        testScope.advanceUntilIdle()
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun publishWithResult_whenStateIsNotConnected_shouldFailWithIllegalStateAndStateInfo() {
        val client = mockk<MqttClient>(relaxed = true)
        manager.setPrivateMqttClient(client)

        val states = listOf(
            MqttConnectionState.Connecting to "Connecting",
            MqttConnectionState.Disconnected to "Disconnected",
            MqttConnectionState.Error("network down") to "Error(network down)",
        )

        states.forEach { (state, expectedState) ->
            manager.setPrivateConnectionState(state)

            val result = manager.publishWithResult(topic = "topic/test", payload = "payload", qos = 1, logError = false)

            assertTrue(result is AppResult.Error)
            val exception = (result as AppResult.Error).exception
            assertTrue(exception is IllegalStateException)
            assertTrue(exception.message?.contains("currentState=$expectedState") == true)
        }

        verify(exactly = 0) { client.publish(any<String>(), any<MqttMessage>()) }
    }

    @Test
    fun subscribeWithResult_whenStateIsNotConnected_shouldFailWithIllegalStateAndStateInfo() {
        val client = mockk<MqttClient>(relaxed = true)
        manager.setPrivateMqttClient(client)

        val states = listOf(
            MqttConnectionState.Connecting to "Connecting",
            MqttConnectionState.Disconnected to "Disconnected",
            MqttConnectionState.Error("auth failed") to "Error(auth failed)",
        )

        states.forEach { (state, expectedState) ->
            manager.setPrivateConnectionState(state)

            val result = manager.subscribeWithResult(topic = "topic/sub", qos = 0)

            assertTrue(result is AppResult.Error)
            val exception = (result as AppResult.Error).exception
            assertTrue(exception is IllegalStateException)
            assertTrue(exception.message?.contains("currentState=$expectedState") == true)
        }

        verify(exactly = 0) { client.subscribe(any<String>(), any<Int>()) }
    }

    @Test
    fun publishWithResult_whenConnected_shouldDelegateToMqttClient() {
        val client = mockk<MqttClient>()
        var capturedMessage: MqttMessage? = null
        every {
            client.publish("topic/ok", any<MqttMessage>())
        } answers {
            capturedMessage = secondArg()
            Unit
        }

        manager.setPrivateMqttClient(client)
        manager.setPrivateConnectionState(MqttConnectionState.Connected)

        val result = manager.publishWithResult(topic = "topic/ok", payload = "hello", qos = 1)

        assertTrue(result.isSuccess())
        verify(exactly = 1) { client.publish("topic/ok", any<MqttMessage>()) }
        assertEquals(1, capturedMessage?.qos)
        assertArrayEquals("hello".toByteArray(), capturedMessage?.payload)
    }

    @Test
    fun subscribeWithResult_whenConnected_shouldDelegateToMqttClient() {
        val client = mockk<MqttClient>()
        every { client.subscribe("topic/ok", 1) } just runs

        manager.setPrivateMqttClient(client)
        manager.setPrivateConnectionState(MqttConnectionState.Connected)

        val result = manager.subscribeWithResult(topic = "topic/ok", qos = 1)

        assertTrue(result.isSuccess())
        verify(exactly = 1) { client.subscribe("topic/ok", 1) }
    }

    private fun MqttConnectionManager.setPrivateConnectionState(state: MqttConnectionState) {
        val field = MqttConnectionManager::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(this) as MutableStateFlow<MqttConnectionState>
        flow.value = state
    }

    private fun MqttConnectionManager.setPrivateMqttClient(client: MqttClient?) {
        val field = MqttConnectionManager::class.java.getDeclaredField("mqttClient")
        field.isAccessible = true
        field.set(this, client)
    }
}