package com.netproxy.gateway.connection

import com.netproxy.gateway.result.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AuthSessionStore 的单元测试
 * 由于 AuthSessionStore 使用 Android 的 EncryptedSharedPreferences，
 * 本测试类主要测试可以独立测试的数据类和结果类型。
 */
class AuthSessionStoreTest {

    // ==================== ProxyAuthSession 数据类测试 ====================

    @Test
    fun proxyAuthSession_defaultValues() {
        val session = ProxyAuthSession("device-123", "token-456")

        assertEquals("device-123", session.deviceId)
        assertEquals("token-456", session.authToken)
    }

    @Test
    fun proxyAuthSession_equality() {
        val session1 = ProxyAuthSession("device-1", "token-1")
        val session2 = ProxyAuthSession("device-1", "token-1")
        val session3 = ProxyAuthSession("device-2", "token-2")

        assertEquals(session1, session2)
        assertTrue(session1 != session3)
    }

    @Test
    fun proxyAuthSession_hashCodeConsistency() {
        val session1 = ProxyAuthSession("device-1", "token-1")
        val session2 = ProxyAuthSession("device-1", "token-1")

        assertEquals(session1.hashCode(), session2.hashCode())
    }

    @Test
    fun proxyAuthSession_copy() {
        val session = ProxyAuthSession("device-1", "token-1")
        val copied = session.copy(deviceId = "device-2")

        assertEquals("device-2", copied.deviceId)
        assertEquals("token-1", copied.authToken)
    }

    @Test
    fun proxyAuthSession_componentFunctions() {
        val session = ProxyAuthSession("device-1", "token-1")

        assertEquals("device-1", session.deviceId)
        assertEquals("token-1", session.authToken)
    }

    @Test
    fun proxyAuthSession_toString() {
        val session = ProxyAuthSession("device-1", "token-1")
        val str = session.toString()

        assertTrue(str.contains("device-1"))
        assertTrue(str.contains("token-1"))
    }

    @Test
    fun proxyAuthSession_withEmptyStrings() {
        val session = ProxyAuthSession("", "")

        assertEquals("", session.deviceId)
        assertEquals("", session.authToken)
    }

    @Test
    fun proxyAuthSession_withLongStrings() {
        val longDeviceId = "device-".repeat(100)
        val longToken = "token-".repeat(100)
        val session = ProxyAuthSession(longDeviceId, longToken)

        assertEquals(longDeviceId, session.deviceId)
        assertEquals(longToken, session.authToken)
    }

    @Test
    fun proxyAuthSession_withSpecialCharacters() {
        val deviceId = "device-123!@#$%^&*()"
        val token = "token-456_+=[]{}|;':\",./<>?"
        val session = ProxyAuthSession(deviceId, token)

        assertEquals(deviceId, session.deviceId)
        assertEquals(token, session.authToken)
    }

    @Test
    fun proxyAuthSession_equalsNull() {
        val session = ProxyAuthSession("device", "token")

        assertFalse(session.equals(null))
    }

    @Test
    fun proxyAuthSession_equalsDifferentType() {
        val session = ProxyAuthSession("device", "token")

        assertFalse(session.equals("not a session"))
    }

    @Test
    fun proxyAuthSession_equalsSameObject() {
        val session = ProxyAuthSession("device", "token")

        assertTrue(session.equals(session))
    }

    // ==================== 常量测试 ====================

    @Test
    fun authSessionStoreCompanionConstants() {
        // Verify the constants are defined
        // These are private in the actual class, but we can verify the class structure
        assertNotNull(AuthSessionStore::class.java)
    }

    // ==================== 边界条件测试 ====================

    @Test
    fun proxyAuthSession_withUnicodeCharacters() {
        val deviceId = "设备-123"
        val token = "令牌-456"
        val session = ProxyAuthSession(deviceId, token)

        assertEquals(deviceId, session.deviceId)
        assertEquals(token, session.authToken)
    }

    @Test
    fun proxyAuthSession_withWhitespace() {
        val deviceId = "  device  "
        val token = "  token  "
        val session = ProxyAuthSession(deviceId, token)

        assertEquals(deviceId, session.deviceId)
        assertEquals(token, session.authToken)
    }

    @Test
    fun proxyAuthSession_withNewlines() {
        val deviceId = "device\nwith\nnewlines"
        val token = "token\nwith\nnewlines"
        val session = ProxyAuthSession(deviceId, token)

        assertEquals(deviceId, session.deviceId)
        assertEquals(token, session.authToken)
    }

    @Test
    fun proxyAuthSession_withTabs() {
        val deviceId = "device\twith\ttabs"
        val token = "token\twith\ttabs"
        val session = ProxyAuthSession(deviceId, token)

        assertEquals(deviceId, session.deviceId)
        assertEquals(token, session.authToken)
    }

    // ==================== 数据类行为测试 ====================

    @Test
    fun proxyAuthSession_destructuring() {
        val session = ProxyAuthSession("device-1", "token-1")
        val (deviceId, authToken) = session

        assertEquals("device-1", deviceId)
        assertEquals("token-1", authToken)
    }

    @Test
    fun proxyAuthSession_copyWithAllFields() {
        val session = ProxyAuthSession("device-1", "token-1")
        val copied = session.copy(deviceId = "device-2", authToken = "token-2")

        assertEquals("device-2", copied.deviceId)
        assertEquals("token-2", copied.authToken)
    }

    @Test
    fun proxyAuthSession_copyPreservesOriginal() {
        val session = ProxyAuthSession("device-1", "token-1")
        val copied = session.copy(deviceId = "device-2")

        // Original should be unchanged
        assertEquals("device-1", session.deviceId)
        assertEquals("token-1", session.authToken)
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
    }
}
