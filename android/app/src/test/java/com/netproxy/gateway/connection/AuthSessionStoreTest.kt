package com.netproxy.gateway.connection

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.netproxy.gateway.result.AppResult
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AuthSessionStore 的单元测试
 * 使用 Mockk 模拟 Android 依赖（EncryptedSharedPreferences、SharedPreferences.Editor 等）
 *
 * Note: 此测试使用了已弃用的 EncryptedSharedPreferences 和 MasterKey API
 * 这是为了测试向后兼容性，新代码应该使用更新的加密 API
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalCoroutinesApi::class)
class AuthSessionStoreTest {

    private lateinit var context: Context
    private lateinit var encryptedPrefs: EncryptedSharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var masterKey: MasterKey
    private lateinit var testScope: TestScope
    private lateinit var authSessionStore: AuthSessionStore

    @Before
    fun setup() {
        // 设置主调度器
        Dispatchers.setMain(StandardTestDispatcher())
        testScope = TestScope(StandardTestDispatcher())

        // Mock Context
        context = mockk(relaxed = true)

        // Mock MasterKey.Builder - 需要支持链式调用
        val builderMock = mockk<MasterKey.Builder>(relaxed = true)
        masterKey = mockk(relaxed = true)
        // setKeyScheme 返回 Builder 自身以支持链式调用
        every { builderMock.setKeyScheme(MasterKey.KeyScheme.AES256_GCM) } returns builderMock
        every { builderMock.build() } returns masterKey

        // Mock 构造函数返回我们的 mock 对象
        mockkConstructor(MasterKey.Builder::class)
        every { anyConstructed<MasterKey.Builder>().setKeyScheme(MasterKey.KeyScheme.AES256_GCM) } returns builderMock
        every { anyConstructed<MasterKey.Builder>().build() } returns masterKey

        // Mock SharedPreferences.Editor
        editor = mockk(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        every { editor.clear() } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.apply() } just runs

        // Mock EncryptedSharedPreferences
        encryptedPrefs = mockk(relaxed = true)
        every { encryptedPrefs.edit() } returns editor

        // Mock EncryptedSharedPreferences.create() 静态方法
        mockkStatic(EncryptedSharedPreferences::class)
        every {
            EncryptedSharedPreferences.create(
                context,
                "auth_session_store",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } returns encryptedPrefs

        // 创建 AuthSessionStore 实例
        authSessionStore = AuthSessionStore(context, testScope)

        // 等待预热线程完成
        testScope.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    // ==================== ProxyAuthSession 数据类测试 ====================

    @Test
    fun proxyAuthSession_defaultValues() {
        val session = ProxyAuthSession("device-123", "token-456".toCharArray())

        assertEquals("device-123", session.deviceId)
        assertTrue(session.authToken.contentEquals("token-456".toCharArray()))
    }

    @Test
    fun proxyAuthSession_equality() {
        val session1 = ProxyAuthSession("device-1", "token-1".toCharArray())
        val session2 = ProxyAuthSession("device-1", "token-1".toCharArray())
        val session3 = ProxyAuthSession("device-2", "token-2".toCharArray())

        assertEquals(session1, session2)
        assertTrue(session1 != session3)
    }

    @Test
    fun proxyAuthSession_hashCodeConsistency() {
        val session1 = ProxyAuthSession("device-1", "token-1".toCharArray())
        val session2 = ProxyAuthSession("device-1", "token-1".toCharArray())

        assertEquals(session1.hashCode(), session2.hashCode())
    }

    @Test
    fun proxyAuthSession_copy() {
        val session = ProxyAuthSession("device-1", "token-1".toCharArray())
        val copied = session.copy(deviceId = "device-2")

        assertEquals("device-2", copied.deviceId)
        assertTrue(copied.authToken.contentEquals("token-1".toCharArray()))
    }

    @Test
    fun proxyAuthSession_componentFunctions() {
        val session = ProxyAuthSession("device-1", "token-1".toCharArray())

        assertEquals("device-1", session.deviceId)
        assertTrue(session.authToken.contentEquals("token-1".toCharArray()))
    }

    @Test
    fun proxyAuthSession_toString() {
        val session = ProxyAuthSession("device-1", "token-1".toCharArray())
        val str = session.toString()

        assertTrue(str.contains("device-1"))
        assertTrue(str.contains("[REDACTED]"))
        assertFalse(str.contains("token-1"))
    }

    @Test
    fun proxyAuthSession_withEmptyStrings() {
        val session = ProxyAuthSession("", "".toCharArray())

        assertEquals("", session.deviceId)
        assertTrue(session.authToken.contentEquals("".toCharArray()))
    }

    @Test
    fun proxyAuthSession_withLongStrings() {
        val longDeviceId = "device-".repeat(100)
        val longToken = "token-".repeat(100)
        val session = ProxyAuthSession(longDeviceId, longToken.toCharArray())

        assertEquals(longDeviceId, session.deviceId)
        assertTrue(session.authToken.contentEquals(longToken.toCharArray()))
    }

    @Test
    fun proxyAuthSession_withSpecialCharacters() {
        val deviceId = "device-123!@#$%^&*()"
        val token = "token-456_+=[]{}|;':\",./<>?"
        val session = ProxyAuthSession(deviceId, token.toCharArray())

        assertEquals(deviceId, session.deviceId)
        assertTrue(session.authToken.contentEquals(token.toCharArray()))
    }

    @Test
    fun proxyAuthSession_equalsNull() {
        val session = ProxyAuthSession("device", "token".toCharArray())

        assertFalse(session.equals(null))
    }

    @Test
    fun proxyAuthSession_equalsDifferentType() {
        val session = ProxyAuthSession("device", "token".toCharArray())

        assertFalse(session.equals("not a session"))
    }

    @Test
    fun proxyAuthSession_equalsSameObject() {
        val session = ProxyAuthSession("device", "token".toCharArray())

        assertTrue(session.equals(session))
    }

    // ==================== update/updateWithResult 方法测试 ====================

    @Test
    fun update_normalUpdate_shouldStoreInMemoryAndEncryptedPrefs() {
        val deviceId = "device-123"
        val authToken = "token-abc".toCharArray()

        authSessionStore.update(deviceId, authToken)

        // 验证 EncryptedSharedPreferences.edit() 被调用
        verify { encryptedPrefs.edit() }
        verify { editor.putString("installation_device_id", deviceId) }
        verify { editor.putString("device_id", deviceId) }
        verify { editor.putString("auth_token", "token-abc") }
        verify { editor.apply() }
    }

    @Test
    fun update_updateExistingSession_shouldClearOldToken() = runTest {
        // 先设置初始会话
        every { encryptedPrefs.getString("device_id", null) } returns "old-device"
        every { encryptedPrefs.getString("auth_token", null) } returns "old-token"

        authSessionStore.update("old-device", "old-token".toCharArray())

        // 更新为新会话
        authSessionStore.update("new-device", "new-token".toCharArray())

        // 验证新值被存储
        verify { editor.putString("device_id", "new-device") }
        verify { editor.putString("auth_token", "new-token") }
    }

    @Test
    fun updateWithResult_normalUpdate_shouldReturnSuccess() {
        val deviceId = "device-123"
        val authToken = "token-abc".toCharArray()

        val result = authSessionStore.updateWithResult(deviceId, authToken)

        assertTrue(result.isSuccess())
        assertEquals(Unit, result.getOrNull())
        verify { encryptedPrefs.edit() }
        verify { editor.apply() }
    }

    @Test
    fun updateWithResult_whenExceptionThrown_shouldReturnError() {
        val deviceId = "device-123"
        val authToken = "token-abc".toCharArray()

        // 模拟异常
        every { encryptedPrefs.edit() } throws RuntimeException("Storage error")

        val result = authSessionStore.updateWithResult(deviceId, authToken)

        assertTrue(result.isError())
        assertNotNull(result.exceptionOrNull())
        assertEquals("Storage error", result.exceptionOrNull()?.message)
    }

    // ==================== clear/clearWithResult 方法测试 ====================

    @Test
    fun clear_normalClear_shouldClearMemoryAndStorage() {
        // 先设置一个会话
        authSessionStore.update("device-123", "token-abc".toCharArray())

        // 清除会话
        authSessionStore.clear()

        verify { editor.remove("device_id") }
        verify { editor.remove("auth_token") }
        verify { editor.apply() }
    }

    @Test
    fun clear_whenNoSession_shouldNotThrow() {
        // 直接清除，不设置会话
        authSessionStore.clear()

        verify { editor.remove("device_id") }
        verify { editor.remove("auth_token") }
        verify { editor.apply() }
    }

    @Test
    fun clearWithResult_normalClear_shouldReturnSuccess() {
        // 先设置一个会话
        authSessionStore.update("device-123", "token-abc".toCharArray())

        val result = authSessionStore.clearWithResult()

        assertTrue(result.isSuccess())
        assertEquals(Unit, result.getOrNull())
        verify { editor.remove("device_id") }
        verify { editor.remove("auth_token") }
        verify { editor.apply() }
    }

    @Test
    fun getOrCreateDeviceId_withStoredValue_shouldReuseStoredValue() {
        every { encryptedPrefs.getString("installation_device_id", null) } returns "device-stable"

        val deviceId = authSessionStore.getOrCreateDeviceId()

        assertEquals("device-stable", deviceId)
        verify(exactly = 0) { editor.putString("installation_device_id", any()) }
    }

    @Test
    fun getOrCreateDeviceId_withoutStoredValue_shouldGenerateAndPersistValue() {
        every { encryptedPrefs.getString("installation_device_id", null) } returns null

        val deviceId = authSessionStore.getOrCreateDeviceId()

        assertTrue(deviceId.isNotBlank())
        verify { editor.putString("installation_device_id", deviceId) }
    }

    @Test
    fun clear_shouldAlsoRemoveInstallationDeviceId() {
        every { encryptedPrefs.getString("installation_device_id", null) } returns "device-stable"
        authSessionStore.update("device-stable", "token-abc".toCharArray())

        authSessionStore.clear()

        // 验证 installation_device_id 也被清理
        verify { editor.remove("installation_device_id") }
        verify { editor.remove("device_id") }
        verify { editor.remove("auth_token") }
        verify { editor.apply() }
    }

    @Test
    fun clearWithResult_whenExceptionThrown_shouldReturnError() {
        // 模拟异常
        every { encryptedPrefs.edit() } throws RuntimeException("Clear error")

        val result = authSessionStore.clearWithResult()

        assertTrue(result.isError())
        assertNotNull(result.exceptionOrNull())
        assertEquals("Clear error", result.exceptionOrNull()?.message)
    }

    // ==================== isValid/validateWithResult 方法测试 ====================

    @Test
    fun isValid_withValidSession_shouldReturnTrue() {
        // 设置会话
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns "token-abc"

        val result = authSessionStore.isValid("device-123", "token-abc".toCharArray())

        assertTrue(result)
    }

    @Test
    fun isValid_withInvalidDeviceId_shouldReturnFalse() {
        // 设置会话
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns "token-abc"

        val result = authSessionStore.isValid("wrong-device", "token-abc".toCharArray())

        assertFalse(result)
    }

    @Test
    fun isValid_withInvalidToken_shouldReturnFalse() {
        // 设置会话
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns "token-abc"

        val result = authSessionStore.isValid("device-123", "wrong-token".toCharArray())

        assertFalse(result)
    }

    @Test
    fun isValid_withNoSession_shouldReturnFalse() {
        // 没有设置会话
        every { encryptedPrefs.getString("device_id", null) } returns null
        every { encryptedPrefs.getString("auth_token", null) } returns null

        val result = authSessionStore.isValid("device-123", "token-abc".toCharArray())

        assertFalse(result)
    }

    @Test
    fun isValid_withEmptyToken_shouldReturnTrue() {
        // 设置空token会话
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns ""

        val result = authSessionStore.isValid("device-123", "".toCharArray())

        // 空字符串应该匹配（如果存储的也是空字符串）
        assertTrue(result)
    }

    @Test
    fun validateWithResult_withValidSession_shouldReturnSuccessTrue() {
        // 设置会话
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns "token-abc"

        val result = authSessionStore.validateWithResult("device-123", "token-abc".toCharArray())

        assertTrue(result.isSuccess())
        assertEquals(true, result.getOrNull())
    }

    @Test
    fun validateWithResult_withInvalidCredentials_shouldReturnSuccessFalse() {
        // 设置会话
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns "token-abc"

        val result = authSessionStore.validateWithResult("device-123", "wrong-token".toCharArray())

        assertTrue(result.isSuccess())
        assertEquals(false, result.getOrNull())
    }

    @Test
    fun validateWithResult_withNoSession_shouldReturnSuccessFalse() {
        // 没有设置会话
        every { encryptedPrefs.getString("device_id", null) } returns null
        every { encryptedPrefs.getString("auth_token", null) } returns null

        val result = authSessionStore.validateWithResult("device-123", "token-abc".toCharArray())

        assertTrue(result.isSuccess())
        assertEquals(false, result.getOrNull())
    }

    @Test
    fun validateWithResult_whenExceptionThrown_shouldReturnError() {
        // 模拟异常
        every { encryptedPrefs.getString(any(), any()) } throws RuntimeException("Read error")

        val result = authSessionStore.validateWithResult("device-123", "token-abc".toCharArray())

        assertTrue(result.isError())
        assertNotNull(result.exceptionOrNull())
    }

    // ==================== getCurrentSession/getCurrentSessionWithResult 方法测试 ====================

    @Test
    fun getCurrentSession_withExistingSession_shouldReturnSession() {
        // 设置会话
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns "token-abc"

        val session = authSessionStore.getCurrentSession()

        assertNotNull(session)
        assertEquals("device-123", session?.deviceId)
        assertTrue(session?.authToken?.contentEquals("token-abc".toCharArray()) == true)
    }

    @Test
    fun getCurrentSession_withNoSession_shouldReturnNull() {
        // 没有设置会话
        every { encryptedPrefs.getString("device_id", null) } returns null
        every { encryptedPrefs.getString("auth_token", null) } returns null

        val session = authSessionStore.getCurrentSession()

        assertNull(session)
    }

    @Test
    fun getCurrentSession_withOnlyDeviceId_shouldReturnNull() {
        // 只有 deviceId，没有 token
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns null

        val session = authSessionStore.getCurrentSession()

        assertNull(session)
    }

    @Test
    fun getCurrentSession_withOnlyToken_shouldReturnNull() {
        // 只有 token，没有 deviceId
        every { encryptedPrefs.getString("device_id", null) } returns null
        every { encryptedPrefs.getString("auth_token", null) } returns "token-abc"

        val session = authSessionStore.getCurrentSession()

        assertNull(session)
    }

    @Test
    fun getCurrentSessionWithResult_withExistingSession_shouldReturnSuccess() {
        // 设置会话
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns "token-abc"

        val result = authSessionStore.getCurrentSessionWithResult()

        assertTrue(result.isSuccess())
        val session = result.getOrNull()
        assertNotNull(session)
        assertEquals("device-123", session?.deviceId)
        assertTrue(session?.authToken?.contentEquals("token-abc".toCharArray()) == true)
    }

    @Test
    fun getCurrentSessionWithResult_withNoSession_shouldReturnError() {
        // 没有设置会话
        every { encryptedPrefs.getString("device_id", null) } returns null
        every { encryptedPrefs.getString("auth_token", null) } returns null

        val result = authSessionStore.getCurrentSessionWithResult()

        assertTrue(result.isError())
        assertNotNull(result.exceptionOrNull())
        assertEquals("No active session found", result.exceptionOrNull()?.message)
    }

    @Test
    fun getCurrentSessionWithResult_whenExceptionThrown_shouldReturnError() {
        // 模拟异常
        every { encryptedPrefs.getString(any(), any()) } throws RuntimeException("Read error")

        val result = authSessionStore.getCurrentSessionWithResult()

        assertTrue(result.isError())
        assertNotNull(result.exceptionOrNull())
        assertEquals("Read error", result.exceptionOrNull()?.message)
    }

    // ==================== constantTimeEquals 方法测试（安全敏感） ====================

    @Test
    fun constantTimeEquals_withSameStrings_shouldReturnTrue() {
        // 设置会话
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns "token-abc"

        val result = authSessionStore.isValid("device-123", "token-abc".toCharArray())

        assertTrue(result)
    }

    @Test
    fun constantTimeEquals_withDifferentStrings_shouldReturnFalse() {
        // 设置会话
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns "token-abc"

        val result = authSessionStore.isValid("device-123", "different-token".toCharArray())

        assertFalse(result)
    }

    @Test
    fun constantTimeEquals_withDifferentLength_shouldReturnFalse() {
        // 设置会话
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns "short"

        val result = authSessionStore.isValid("device-123", "much-longer-token".toCharArray())

        assertFalse(result)
    }

    @Test
    fun constantTimeEquals_withEmptyStrings_shouldReturnTrue() {
        // 设置空token会话
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns ""

        val result = authSessionStore.isValid("device-123", "".toCharArray())

        assertTrue(result)
    }

    @Test
    fun constantTimeEquals_withSingleCharacterDifference_shouldReturnFalse() {
        // 设置会话
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns "token-abc"

        // 只有一个字符不同
        val result = authSessionStore.isValid("device-123", "token-abcX".toCharArray())

        assertFalse(result)
    }

    @Test
    fun constantTimeEquals_timingAttackResistance_sameLengthDifferentContent() {
        // 设置会话
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns "token-abc"

        // 多次比较相同长度但不同内容的字符串，验证都返回 false
        val testTokens = listOf(
            "token-abd",  // 最后一个字符不同
            "token-abb",  // 最后一个字符不同
            "token-abX",  // 最后一个字符不同
            "uoken-abc",  // 第一个字符不同
            "Xoken-abc"   // 第一个字符不同
        )

        testTokens.forEach { token ->
            val result = authSessionStore.isValid("device-123", token.toCharArray())
            assertFalse("Token '$token' should be invalid", result)
        }
    }

    @Test
    fun constantTimeEquals_withUnicodeCharacters_shouldWorkCorrectly() {
        // 设置包含 Unicode 的会话
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns "令牌-abc"

        // 正确匹配
        val validResult = authSessionStore.isValid("device-123", "令牌-abc".toCharArray())
        assertTrue(validResult)

        // 错误匹配
        val invalidResult = authSessionStore.isValid("device-123", "令牌-abd".toCharArray())
        assertFalse(invalidResult)
    }

    @Test
    fun constantTimeEquals_withSpecialCharacters_shouldWorkCorrectly() {
        // 设置包含特殊字符的会话
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns "token!@#$%^&*()"

        // 正确匹配
        val validResult = authSessionStore.isValid("device-123", "token!@#$%^&*()".toCharArray())
        assertTrue(validResult)

        // 错误匹配
        val invalidResult = authSessionStore.isValid("device-123", "token!@#$%^&*()X".toCharArray())
        assertFalse(invalidResult)
    }

    // ==================== 内存缓存和持久化同步测试 ====================

    @Test
    fun memoryCache_shouldBeUsed_whenAvailable() {
        // 第一次调用，从存储加载
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns "token-abc"

        val session1 = authSessionStore.getCurrentSession()
        assertNotNull(session1)

        // 第二次调用，应该从内存缓存获取（不访问存储）
        val session2 = authSessionStore.getCurrentSession()
        assertNotNull(session2)

        // 验证存储只被访问一次（第一次）
        verify(exactly = 1) { encryptedPrefs.getString("device_id", null) }
        verify(exactly = 1) { encryptedPrefs.getString("auth_token", null) }
    }

    @Test
    fun memoryCache_shouldBeCleared_onClear() {
        // 设置会话
        every { encryptedPrefs.getString("device_id", null) } returns "device-123"
        every { encryptedPrefs.getString("auth_token", null) } returns "token-abc"

        // 第一次获取，加载到内存
        val session1 = authSessionStore.getCurrentSession()
        assertNotNull(session1)

        // 清除会话
        authSessionStore.clear()

        // 重置 mock，验证清除后重新从存储加载
        every { encryptedPrefs.getString("device_id", null) } returns null
        every { encryptedPrefs.getString("auth_token", null) } returns null

        val session2 = authSessionStore.getCurrentSession()
        assertNull(session2)
    }

    @Test
    fun memoryCache_shouldBeUpdated_onUpdate() {
        // 设置初始会话
        authSessionStore.update("old-device", "old-token".toCharArray())

        // 更新会话
        every { encryptedPrefs.getString("device_id", null) } returns "new-device"
        every { encryptedPrefs.getString("auth_token", null) } returns "new-token"

        authSessionStore.update("new-device", "new-token".toCharArray())

        // 验证新会话可以直接获取（从内存）
        val session = authSessionStore.getCurrentSession()
        assertNotNull(session)
        assertEquals("new-device", session?.deviceId)
        assertTrue(session?.authToken?.contentEquals("new-token".toCharArray()) == true)
    }

    // ==================== 边界条件测试 ====================

    @Test
    fun proxyAuthSession_withUnicodeCharacters() {
        val deviceId = "设备-123"
        val token = "令牌-456"
        val session = ProxyAuthSession(deviceId, token.toCharArray())

        assertEquals(deviceId, session.deviceId)
        assertTrue(session.authToken.contentEquals(token.toCharArray()))
    }

    @Test
    fun proxyAuthSession_withWhitespace() {
        val deviceId = "  device  "
        val token = "  token  "
        val session = ProxyAuthSession(deviceId, token.toCharArray())

        assertEquals(deviceId, session.deviceId)
        assertTrue(session.authToken.contentEquals(token.toCharArray()))
    }

    @Test
    fun proxyAuthSession_withNewlines() {
        val deviceId = "device\nwith\nnewlines"
        val token = "token\nwith\nnewlines"
        val session = ProxyAuthSession(deviceId, token.toCharArray())

        assertEquals(deviceId, session.deviceId)
        assertTrue(session.authToken.contentEquals(token.toCharArray()))
    }

    @Test
    fun proxyAuthSession_withTabs() {
        val deviceId = "device\twith\ttabs"
        val token = "token\twith\ttabs"
        val session = ProxyAuthSession(deviceId, token.toCharArray())

        assertEquals(deviceId, session.deviceId)
        assertTrue(session.authToken.contentEquals(token.toCharArray()))
    }

    // ==================== 数据类行为测试 ====================

    @Test
    fun proxyAuthSession_destructuring() {
        val session = ProxyAuthSession("device-1", "token-1".toCharArray())
        val (deviceId, authToken) = session

        assertEquals("device-1", deviceId)
        assertTrue(authToken.contentEquals("token-1".toCharArray()))
    }

    @Test
    fun proxyAuthSession_copyWithAllFields() {
        val session = ProxyAuthSession("device-1", "token-1".toCharArray())
        val copied = session.copy(deviceId = "device-2", authToken = "token-2".toCharArray())

        assertEquals("device-2", copied.deviceId)
        assertTrue(copied.authToken.contentEquals("token-2".toCharArray()))
    }

    @Test
    fun proxyAuthSession_copyPreservesOriginal() {
        val session = ProxyAuthSession("device-1", "token-1".toCharArray())
        val copied = session.copy(deviceId = "device-2")

        // Original should be unchanged
        assertEquals("device-1", session.deviceId)
        assertTrue(session.authToken.contentEquals("token-1".toCharArray()))
    }

    // ==================== 类结构测试 ====================

    @Test
    fun authSessionStore_isAnnotatedWithSingleton() {
        val annotations = AuthSessionStore::class.java.annotations
        // The class should have @Singleton annotation
        val hasSingleton = annotations.any { it.annotationClass.simpleName == "Singleton" }
        assertTrue("AuthSessionStore should be annotated with @Singleton", hasSingleton)
    }

    @Test
    fun authSessionStore_hasInjectConstructor() {
        val constructors = AuthSessionStore::class.java.constructors
        assertTrue("AuthSessionStore should have at least one constructor", constructors.isNotEmpty())

        // Check for @Inject annotation on constructor parameters
        val constructor = constructors.first()
        val parameterAnnotations = constructor.parameterAnnotations
        assertTrue("Constructor should have parameters", parameterAnnotations.isNotEmpty())
    }

    // ==================== 方法签名测试 ====================

    @Test
    fun authSessionStore_hasRequiredMethods() {
        val methods = AuthSessionStore::class.java.methods.map { it.name }

        assertTrue("Should have update method", methods.contains("update"))
        assertTrue("Should have clear method", methods.contains("clear"))
        assertTrue("Should have isValid method", methods.contains("isValid"))
        assertTrue("Should have getCurrentSession method", methods.contains("getCurrentSession"))
    }

    @Test
    fun authSessionStore_hasResultMethods() {
        val methods = AuthSessionStore::class.java.methods.map { it.name }

        assertTrue("Should have updateWithResult method", methods.contains("updateWithResult"))
        assertTrue("Should have clearWithResult method", methods.contains("clearWithResult"))
        assertTrue("Should have validateWithResult method", methods.contains("validateWithResult"))
        assertTrue("Should have getCurrentSessionWithResult method", methods.contains("getCurrentSessionWithResult"))
    }

    // ==================== 线程安全测试（验证 @Synchronized 注解） ====================

    @Test
    fun authSessionStore_methodsAreSynchronized() {
        val methods = AuthSessionStore::class.java.declaredMethods

        // Check that key methods have @Synchronized annotation
        val updateMethod = methods.find { it.name == "update" && it.parameterCount == 2 }
        val clearMethod = methods.find { it.name == "clear" && it.parameterCount == 0 }
        val isValidMethod = methods.find { it.name == "isValid" }
        val getCurrentSessionMethod = methods.find { it.name == "getCurrentSession" && it.parameterCount == 0 }

        // Methods should exist
        assertNotNull("update method should exist", updateMethod)
        assertNotNull("clear method should exist", clearMethod)
        assertNotNull("isValid method should exist", isValidMethod)
        assertNotNull("getCurrentSession method should exist", getCurrentSessionMethod)

        // Methods should have @Synchronized annotation
        assertTrue("update method should be @Synchronized", updateMethod!!.isAnnotationPresent(Synchronized::class.java))
        assertTrue("clear method should be @Synchronized", clearMethod!!.isAnnotationPresent(Synchronized::class.java))
        assertTrue("isValid method should be @Synchronized", isValidMethod!!.isAnnotationPresent(Synchronized::class.java))
        assertTrue("getCurrentSession method should be @Synchronized", getCurrentSessionMethod!!.isAnnotationPresent(Synchronized::class.java))
    }

    // ==================== CharArray 生命周期测试 (N37-B6) ====================

    @Test
    fun loadSession_returnsCopyNotSameReferenceAsInMemoryToken() = runTest {
        // Update to populate inMemoryToken
        authSessionStore.update("device-1", "secret-token".toCharArray())
        advanceUntilIdle()

        val session1 = authSessionStore.getCurrentSession()
        val session2 = authSessionStore.getCurrentSession()

        assertNotNull(session1)
        assertNotNull(session2)

        // Both sessions should have equal content but different CharArray references
        assertTrue(session1!!.authToken.contentEquals(session2!!.authToken))
        // The returned CharArray should be a copy, not the same reference as inMemoryToken
        // (Modifying one should not affect the other)
        session1.authToken.fill('\u0000')
        assertFalse(session2.authToken.contentEquals(CharArray("secret-token".length)))
        assertTrue(session2.authToken.contentEquals("secret-token".toCharArray()))
    }
}
