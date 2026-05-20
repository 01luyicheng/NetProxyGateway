package com.netproxy.gateway.connection

import android.content.Context
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class MqttConnectionManagerConnectCleanupTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var context: Context
    private lateinit var manager: MqttConnectionManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        testScope = TestScope(testDispatcher)

        mockkConstructor(MqttClient::class)
        every { anyConstructed<MqttClient>().setCallback(any()) } just runs
        every { anyConstructed<MqttClient>().disconnect() } just runs
        every { anyConstructed<MqttClient>().close() } just runs

        context = mockk(relaxed = true)
        manager = MqttConnectionManager(context, testScope)
    }

    @After
    fun tearDown() {
        manager.disconnect()
        testScope.advanceUntilIdle()
        testScope.advanceUntilIdle()
        unmockkConstructor(MqttClient::class)
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun connect_whenConnectThrowsAndGenerationChanges_shouldClearClientReferenceAndCloseClient() = testScope.runTest {
        every { anyConstructed<MqttClient>().connect(any<MqttConnectOptions>()) } answers {
            manager.incrementPrivateConnectionGeneration()
            throw RuntimeException("boom")
        }

        manager.connect(deviceId = "device-1", authToken = "token-1")
        testScope.advanceUntilIdle()
        testScope.advanceUntilIdle()

        // 当 connect() 抛出异常时：
        // 1. 本测试模拟的是 connect() 调用时抛出异常，此时客户端已创建成功，localClient 不为 null
        // 2. 异常处理逻辑会在 localClient != null 时调用 disconnect() 和 close()
        // 3. mqttClient 引用会被清除（如果 mqttClient === clientToClose）
        verify(timeout = 2_000) { anyConstructed<MqttClient>().disconnect() }
        verify(timeout = 2_000) { anyConstructed<MqttClient>().close() }
        assertNull(manager.getPrivateMqttClient())
    }

    private fun MqttConnectionManager.incrementPrivateConnectionGeneration() {
        val field = MqttConnectionManager::class.java.getDeclaredField("connectionGeneration")
        field.isAccessible = true
        val atomic = field.get(this) as AtomicLong
        atomic.incrementAndGet()
    }

    private fun MqttConnectionManager.getPrivateMqttClient(): MqttClient? {
        val field = MqttConnectionManager::class.java.getDeclaredField("mqttClient")
        field.isAccessible = true
        return field.get(this) as? MqttClient
    }
}
