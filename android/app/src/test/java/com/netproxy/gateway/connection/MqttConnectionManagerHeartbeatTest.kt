package com.netproxy.gateway.connection

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import com.netproxy.gateway.utils.securelyClear
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
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
            generation = currentGeneration,
        )
        testScope.runCurrent()

        val jobBefore = manager.getPrivateHeartbeatJob()
        assertTrue("Expected heartbeatJob to be active", jobBefore?.isActive == true)

        manager.invokePrivateStartHeartbeat(
            deviceId = "device-123",
            generation = currentGeneration + 1,
        )
        testScope.runCurrent()

        val jobAfter = manager.getPrivateHeartbeatJob()
        assertSame(jobBefore, jobAfter)
        assertTrue("Expected heartbeatJob to remain active", jobAfter?.isActive == true)
    }

    // ==================== N37-B7: scheduleReconnect token copy test ====================

    @Test
    fun scheduleReconnect_createsOwnTokenCopy_originalZeroingDoesNotAffectReconnect() =
        testScope.runTest {
            val spyManager = spyk(MqttConnectionManager(context, testScope))

            spyManager.setPrivateBooleanField("shouldStayConnected", true)
            spyManager.setPrivateConnectionState(MqttConnectionState.Connected)

            val generation = spyManager.getPrivateConnectionGeneration()
            val originalToken = "secret-token".toCharArray()

            // Set activeTokenSnapshot so scheduleReconnect can copy from it
            val activeTokenField =
                MqttConnectionManager::class.java.getDeclaredField("activeTokenSnapshot")
            activeTokenField.isAccessible = true
            activeTokenField.set(spyManager, originalToken.copyOf())

            // Mock connect() to capture the token argument
            var capturedToken: CharArray? = null
            every { spyManager.connect(any(), any()) } answers {
                // Capture a copy immediately before any zeroing happens
                capturedToken = secondArg<CharArray>().copyOf()
                // Simulate what connect() does: copy the token immediately
                val tokenSnapshot = secondArg<CharArray>().copyOf()
                // Set activeTokenSnapshot via reflection so disconnect can clean up
                val field =
                    MqttConnectionManager::class.java.getDeclaredField("activeTokenSnapshot")
                field.isAccessible = true
                field.set(spyManager, tokenSnapshot)
            }

            // Call scheduleReconnect via reflection
            spyManager.invokePrivateScheduleReconnect(
                "device-123",
                generation,
            )

            // Zero the original token (simulating disconnect or reconnect cleanup)
            originalToken.securelyClear()

            // Advance time past the reconnect delay
            advanceTimeBy(INITIAL_RECONNECT_DELAY)
            advanceUntilIdle()

            // Verify connect() was called
            verify { spyManager.connect(any(), any()) }

            // The token passed to connect() should NOT be zeroed
            assertNotNull(capturedToken)
            assertTrue(
                "scheduleReconnect should pass a non-zeroed token copy to connect(), " +
                    "but got: ${capturedToken!!.toList()}",
                capturedToken!!.contentEquals("secret-token".toCharArray()),
            )

            // Cleanup
            spyManager.disconnect()
            advanceUntilIdle()
        }

    private fun MqttConnectionManager.invokePrivateScheduleReconnect(
        deviceId: String,
        generation: Long,
    ) {
        val method = MqttConnectionManager::class.java.getDeclaredMethod(
            "scheduleReconnect",
            String::class.java,
            java.lang.Long.TYPE,
        )
        method.isAccessible = true
        method.invoke(this, deviceId, generation)
    }

    private fun MqttConnectionManager.invokePrivateStartHeartbeat(
        deviceId: String,
        generation: Long,
    ) {
        val method = MqttConnectionManager::class.java.getDeclaredMethod(
            "startHeartbeat",
            String::class.java,
            java.lang.Long.TYPE,
        )
        method.isAccessible = true
        method.invoke(this, deviceId, generation)
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
        private const val INITIAL_RECONNECT_DELAY = 5_000L
    }
}
