package com.netproxy.gateway.connection

import android.content.Context
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * C82 批 2: 验证 MqttConnectionManager 中 5 处 securelyClear() 调用点的零化行为。
 *
 * 覆盖调用点：
 *  - connect() 同步块中替换前的旧 activeTokenSnapshot 清零 (MqttConnectionManager.kt:287)
 *  - connect() finally 中 tokenSnapshot 清零 (MqttConnectionManager.kt:497)
 *  - connect() finally 中 connectOptions.password 清零 [CR14-1 关键] (MqttConnectionManager.kt:502)
 *  - scheduleReconnect() finally 中 tokenCopy 清零 (MqttConnectionManager.kt:545)
 *  - disconnect() 中 activeTokenSnapshot 清零 (MqttConnectionManager.kt:696)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MqttConnectionManagerZeroingTest {

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
        unmockkAll()
        Dispatchers.resetMain()
    }

    // ==================== 调用点 1: connect() 同步块 :287 ====================

    @Test
    fun connect_zerosActiveTokenSnapshotBeforeReplace() = testScope.runTest {
        val oldToken = "old-secret-token".toCharArray()
        val activeTokenField =
            MqttConnectionManager::class.java.getDeclaredField("activeTokenSnapshot")
        activeTokenField.isAccessible = true
        activeTokenField.set(manager, oldToken)

        every { anyConstructed<MqttClient>().connect(any<MqttConnectOptions>()) } answers {
            manager.incrementPrivateConnectionGeneration()
            throw RuntimeException("test")
        }

        manager.connect(deviceId = "device-1", authToken = "new-token".toCharArray())

        // synchronized 块在 connect() 调用线程上同步执行：
        // 先 securelyClear 旧 snapshot，再以 authToken.copyOf() 替换。
        assertTrue(
            "旧 activeTokenSnapshot 应在替换前被零化，但实际为: ${oldToken.toList()}",
            oldToken.all { it == '\u0000' },
        )

        val newSnapshot = activeTokenField.get(manager) as CharArray?
        assertNotNull("替换后 activeTokenSnapshot 不应为 null", newSnapshot)
        assertArrayEquals(
            "新 activeTokenSnapshot 应为 authToken 的副本",
            "new-token".toCharArray(),
            newSnapshot,
        )

        advanceUntilIdle()
    }

    // ==================== 调用点 2: connect() finally :497 ====================

    @Test
    fun connect_finally_zerosTokenSnapshot() = testScope.runTest {
        // 通过 mockkConstructor 拦截 MqttConnectOptions.setPassword，
        // 捕获传入的 tokenSnapshot 引用（Paho 不再做 Arrays.copyOf）。
        // finally 中 tokenSnapshot.securelyClear() 应零化该引用。
        mockkConstructor(MqttConnectOptions::class)
        val passwordSlot = slot<CharArray>()
        every { anyConstructed<MqttConnectOptions>().setPassword(capture(passwordSlot)) } just runs

        every { anyConstructed<MqttClient>().connect(any<MqttConnectOptions>()) } answers {
            manager.incrementPrivateConnectionGeneration()
            throw RuntimeException("test")
        }

        manager.connect(deviceId = "device-1", authToken = "secret-token".toCharArray())
        advanceUntilIdle()

        // safeCloseMqttClient 在 Dispatchers.IO 上执行（catch 块内），advanceUntilIdle 不会等待真实 IO 线程。
        // 用 verify(timeout) 等待 close() 被调用，标志着 IO 块执行完毕；随后 advanceUntilIdle 处理协程恢复，
        // 触发 return@launch 与 finally 执行。
        verify(timeout = 2_000) { anyConstructed<MqttClient>().close() }
        advanceUntilIdle()

        assertTrue("应捕获到 setPassword 调用", passwordSlot.isCaptured)
        assertTrue(
            "tokenSnapshot 应在 connect() finally 中被零化，但实际为: ${passwordSlot.captured.toList()}",
            passwordSlot.captured.all { it == '\u0000' },
        )

        unmockkConstructor(MqttConnectOptions::class)
    }

    // ==================== 调用点 3: connect() finally :502 (CR14-1 关键) ====================

    @Test
    fun connect_finally_zerosConnectOptionsPasswordCopy() = testScope.runTest {
        // 使用真实 MqttConnectOptions（不 mockkConstructor），让 Paho 内部 setPassword 做 clone。
        // 通过 mock MqttClient.connect 捕获 options 实例，反射读取 Paho 私有字段 password。
        // finally 中 connectOptions?.password?.securelyClear() 应零化 Paho 内部拷贝。
        var capturedOptions: MqttConnectOptions? = null
        every { anyConstructed<MqttClient>().connect(any<MqttConnectOptions>()) } answers {
            capturedOptions = firstArg()
            manager.incrementPrivateConnectionGeneration()
            throw RuntimeException("test")
        }

        manager.connect(deviceId = "device-1", authToken = "secret-token".toCharArray())
        advanceUntilIdle()

        // 等待 safeCloseMqttClient（在 Dispatchers.IO 上）完成，再推进协程恢复以触发 finally。
        verify(timeout = 2_000) { anyConstructed<MqttClient>().close() }
        advanceUntilIdle()

        assertNotNull("应捕获到 MqttConnectOptions 实例", capturedOptions)

        val passwordField = MqttConnectOptions::class.java.getDeclaredField("password")
        passwordField.isAccessible = true
        val pahoPasswordCopy = passwordField.get(capturedOptions) as? CharArray
        assertNotNull(
            "Paho 内部 password 字段不应为 null（setPassword 应已 clone）",
            pahoPasswordCopy,
        )
        assertTrue(
            "Paho 内部 password 拷贝应在 finally 中被零化 (CR14-1)，但实际为: ${pahoPasswordCopy!!.toList()}",
            pahoPasswordCopy.all { it == '\u0000' },
        )
    }

    // ==================== 调用点 4: scheduleReconnect() finally :545 ====================

    @Test
    fun scheduleReconnect_finally_zerosTokenCopy() = testScope.runTest {
        val spyManager = spyk(MqttConnectionManager(context, testScope))
        spyManager.setPrivateBooleanField("shouldStayConnected", true)
        spyManager.setPrivateConnectionState(MqttConnectionState.Connected)

        val generation = spyManager.getPrivateConnectionGeneration()
        val originalToken = "secret-token".toCharArray()

        val activeTokenField =
            MqttConnectionManager::class.java.getDeclaredField("activeTokenSnapshot")
        activeTokenField.isAccessible = true
        activeTokenField.set(spyManager, originalToken.copyOf())

        // 捕获 connect() 的第二个参数引用（不做 copyOf），用于验证 finally 零化。
        var capturedTokenRef: CharArray? = null
        every { spyManager.connect(any(), any()) } answers {
            capturedTokenRef = secondArg<CharArray>()
            // 维护 activeTokenSnapshot 以便 disconnect 清理
            val field = MqttConnectionManager::class.java.getDeclaredField("activeTokenSnapshot")
            field.isAccessible = true
            field.set(spyManager, secondArg<CharArray>().copyOf())
        }

        spyManager.invokePrivateScheduleReconnect("device-123", generation)

        advanceTimeBy(INITIAL_RECONNECT_DELAY)
        advanceUntilIdle()

        assertNotNull("应捕获到 connect() 调用的 token 参数", capturedTokenRef)
        assertTrue(
            "tokenCopy 应在 scheduleReconnect() finally 中被零化，但实际为: ${capturedTokenRef!!.toList()}",
            capturedTokenRef!!.all { it == '\u0000' },
        )

        spyManager.disconnect()
        advanceUntilIdle()
    }

    // ==================== 调用点 5: disconnect() :696 ====================

    @Test
    fun disconnect_zerosActiveTokenSnapshot() {
        val token = "secret-token".toCharArray()
        val activeTokenField =
            MqttConnectionManager::class.java.getDeclaredField("activeTokenSnapshot")
        activeTokenField.isAccessible = true
        activeTokenField.set(manager, token)

        manager.disconnect()

        assertTrue(
            "activeTokenSnapshot 应在 disconnect() 中被零化，但实际为: ${token.toList()}",
            token.all { it == '\u0000' },
        )
        assertNull(
            "activeTokenSnapshot 应在 disconnect() 后被置为 null",
            activeTokenField.get(manager),
        )
    }

    // ==================== 反射辅助 ====================

    private fun MqttConnectionManager.incrementPrivateConnectionGeneration() {
        val field = MqttConnectionManager::class.java.getDeclaredField("connectionGeneration")
        field.isAccessible = true
        val atomic = field.get(this) as AtomicLong
        atomic.incrementAndGet()
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
        val atomic = field.get(this) as AtomicLong
        return atomic.get()
    }

    private companion object {
        private const val INITIAL_RECONNECT_DELAY = 5_000L
    }
}
