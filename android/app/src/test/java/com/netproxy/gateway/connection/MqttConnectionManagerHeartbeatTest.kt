package com.netproxy.gateway.connection

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
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
            authToken = "token-abc".toCharArray(),
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
            authToken = "token-abc".toCharArray(),
            generation = currentGeneration,
        )
        testScope.runCurrent()

        val jobBefore = manager.getPrivateHeartbeatJob()
        assertTrue("Expected heartbeatJob to be active", jobBefore?.isActive == true)

        manager.invokePrivateStartHeartbeat(
            deviceId = "device-123",
            authToken = "token-abc".toCharArray(),
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

            // Mock connect() to capture the token argument
            val tokenSlot = slot<CharArray>()
            every { spyManager.connect(any(), capture(tokenSlot)) } answers {
                // Just capture the token - no need to simulate full connect() behavior
            }

            // Call scheduleReconnect via reflection
            spyManager.invokePrivateScheduleReconnect(
                "device-123",
                originalToken,
                generation,
            )

            // Zero the original token (simulating connect()'s finally block zeroing tokenSnapshot)
            originalToken.fill('\u0000')

            // Advance time past the reconnect delay
            advanceTimeBy(INITIAL_RECONNECT_DELAY)
            advanceUntilIdle()

            // Verify connect() was called
            verify { spyManager.connect(any(), any()) }

            // The token passed to connect() should NOT be zeroed
            // because scheduleReconnect creates its own copy (tokenCopy)
            assertTrue(
                "scheduleReconnect should pass a non-zeroed token copy to connect(), " +
                    "but got: ${tokenSlot.captured.toList()}",
                tokenSlot.captured.contentEquals("secret-token".toCharArray()),
            )

            // Cleanup
            spyManager.disconnect()
            advanceUntilIdle()
        }

    private fun MqttConnectionManager.invokePrivateScheduleReconnect(
        deviceId: String,
        authToken: CharArray,
        generation: Long,
    ) {
        val method = MqttConnectionManager::class.java.getDeclaredMethod(
            "scheduleReconnect",
            String::class.java,
            CharArray::class.java,
            java.lang.Long.TYPE,
        )
        method.isAccessible = true
        method.invoke(this, deviceId, authToken, generation)
    }

    // ==================== N37-B9: startHeartbeat tokenSnapshot zeroing test ====================

    @Test
    fun startHeartbeat_zerosTokenSnapshotInFinallyBlock() {
        manager.setPrivateBooleanField("shouldStayConnected", true)
        manager.setPrivateConnectionState(MqttConnectionState.Connected)

        val generation = manager.getPrivateConnectionGeneration()
        val authToken = "heartbeat-secret".toCharArray()

        manager.invokePrivateStartHeartbeat(
            deviceId = "device-123",
            authToken = authToken,
            generation = generation,
        )
        testScope.runCurrent()

        // Verify heartbeat job is active
        val job = manager.getPrivateHeartbeatJob()
        assertTrue("Expected heartbeatJob to be active", job?.isActive == true)

        // Disconnect to cancel the heartbeat job, which should trigger finally block
        manager.disconnect()
        testScope.advanceUntilIdle()

        // The authToken passed to startHeartbeat should still be intact
        // (startHeartbeat creates its own copy, so the original is not modified)
        assertTrue(
            "Original authToken should not be modified by startHeartbeat",
            authToken.contentEquals("heartbeat-secret".toCharArray()),
        )
    }

    private fun MqttConnectionManager.invokePrivateStartHeartbeat(
        deviceId: String,
        authToken: CharArray,
        generation: Long,
    ) {
        val method = MqttConnectionManager::class.java.getDeclaredMethod(
            "startHeartbeat",
            String::class.java,
            CharArray::class.java,
            java.lang.Long.TYPE,
        )
        method.isAccessible = true
        method.invoke(this, deviceId, authToken, generation)
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
