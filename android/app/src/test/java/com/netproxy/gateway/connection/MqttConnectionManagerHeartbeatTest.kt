package com.netproxy.gateway.connection

import android.content.Context
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MqttConnectionManagerHeartbeatTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var context: Context
    private lateinit var manager: MqttConnectionManager

    @Before
    fun setup() {
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
    fun startHeartbeat_whenPublishReturnsErrorButDoesNotThrow_shouldSetErrorAfterMaxFailures() {
        manager.setPrivateBooleanField("shouldStayConnected", true)
        manager.setPrivateConnectionState(MqttConnectionState.Connected)

        val generation = manager.getPrivateConnectionGeneration()
        manager.invokePrivateStartHeartbeat(
            deviceId = "device-123",
            authToken = "token-abc",
            generation = generation,
        )

        testScope.runCurrent()

        testScope.advanceTimeBy(HEARTBEAT_INTERVAL_MS * MAX_FAILURES)
        testScope.runCurrent()

        val state = manager.connectionState.value
        assertTrue(
            "Expected connectionState to be Error after $MAX_FAILURES heartbeat publish failures, but was: $state",
            state is MqttConnectionState.Error,
        )
    }

    @Test
    fun startHeartbeat_whenGenerationIsStale_shouldNotCancelExistingHeartbeatJob() {
        manager.setPrivateBooleanField("shouldStayConnected", true)
        manager.setPrivateConnectionState(MqttConnectionState.Connected)

        val currentGeneration = manager.getPrivateConnectionGeneration()
        manager.invokePrivateStartHeartbeat(
            deviceId = "device-123",
            authToken = "token-abc",
            generation = currentGeneration,
        )
        testScope.runCurrent()

        val jobBefore = manager.getPrivateHeartbeatJob()
        assertTrue("Expected heartbeatJob to be active", jobBefore?.isActive == true)

        manager.invokePrivateStartHeartbeat(
            deviceId = "device-123",
            authToken = "token-abc",
            generation = currentGeneration + 1,
        )
        testScope.runCurrent()

        val jobAfter = manager.getPrivateHeartbeatJob()
        assertSame(jobBefore, jobAfter)
        assertTrue("Expected heartbeatJob to remain active", jobAfter?.isActive == true)
    }

    private fun MqttConnectionManager.invokePrivateStartHeartbeat(
        deviceId: String,
        authToken: String,
        generation: Long,
    ) {
        val method = MqttConnectionManager::class.java.getDeclaredMethod(
            "startHeartbeat",
            String::class.java,
            CharArray::class.java,
            java.lang.Long.TYPE,
        )
        method.isAccessible = true
        method.invoke(this, deviceId, authToken.toCharArray(), generation)
    }

    private fun MqttConnectionManager.setPrivateBooleanField(fieldName: String, value: Boolean) {
        val field = MqttConnectionManager::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.setBoolean(this, value)
    }

    private fun MqttConnectionManager.setPrivateConnectionState(state: MqttConnectionState) {
        val field = MqttConnectionManager::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(this) as MutableStateFlow<MqttConnectionState>
        flow.value = state
    }

    private fun MqttConnectionManager.getPrivateConnectionGeneration(): Long {
        val field = MqttConnectionManager::class.java.getDeclaredField("connectionGeneration")
        field.isAccessible = true
        val atomic = field.get(this) as java.util.concurrent.atomic.AtomicLong
        return atomic.get()
    }

    private fun MqttConnectionManager.getPrivateHeartbeatJob(): Job? {
        val field = MqttConnectionManager::class.java.getDeclaredField("heartbeatJob")
        field.isAccessible = true
        return field.get(this) as? Job
    }

    private companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val MAX_FAILURES = 3
    }
}
