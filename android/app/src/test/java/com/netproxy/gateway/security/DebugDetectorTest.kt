package com.netproxy.gateway.security

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Method

class DebugDetectorTest {

    /**
     * 模拟 android.os.SystemProperties#get(String) 的反射目标方法，
     * 使用可配置的 Map 返回值，用于测试反射成功路径。
     */
    object FakeSystemProperties {
        var values: Map<String, String> = emptyMap()

        @JvmStatic
        fun get(key: String): String = values[key] ?: ""
    }

    /**
     * 模拟反射调用失败的目标方法（如 hidden API 被阻断时抛出的异常）。
     * 可选择只对指定的属性抛出异常，其余属性正常返回，
     * 用于验证单条属性异常不会中断后续属性检查。
     */
    object ThrowingSystemProperties {
        var throwOnKeys: Set<String> = emptySet()
        var values: Map<String, String> = emptyMap()

        @JvmStatic
        fun get(key: String): String {
            if (throwOnKeys.isEmpty() || throwOnKeys.contains(key)) {
                throw NoSuchMethodException("hidden API blocked for $key")
            }
            return values[key] ?: ""
        }
    }

    private fun fakeGetMethod(): Method =
        FakeSystemProperties::class.java.getMethod("get", String::class.java)

    private fun throwingGetMethod(): Method =
        ThrowingSystemProperties::class.java.getMethod("get", String::class.java)

    private val currentPid = 9999 // Mock current process PID for testing

    @Test
    fun parsePtraceStatus_returnsTrue_whenTracerPidIsNonZero() {
        val status = """
            Name:   app_process
            TracerPid: 1234
            PPid:   567
        """.trimIndent()

        assertTrue(DebugDetector.parsePtraceStatus(status, currentPid))
    }

    @Test
    fun parsePtraceStatus_returnsFalse_whenTracerPidIsZeroAndPpidIsNormal() {
        // Normal zygote parent (PPid > 100) should not trigger detection
        val status = """
            Name:   app_process
            TracerPid: 0
            PPid:   567
        """.trimIndent()

        assertFalse(DebugDetector.parsePtraceStatus(status, currentPid))
    }

    @Test
    fun parsePtraceStatus_returnsFalse_whenTracerPidMissingOrInvalid() {
        val missingTracerPid = """
            Name:   app_process
            PPid:   567
        """.trimIndent()
        val invalidTracerPid = """
            Name:   app_process
            TracerPid: abc
            PPid:   567
        """.trimIndent()

        assertFalse(DebugDetector.parsePtraceStatus(missingTracerPid, currentPid))
        assertFalse(DebugDetector.parsePtraceStatus(invalidTracerPid, currentPid))
    }

    @Test
    fun parsePtraceStatus_returnsTrue_whenPpidIsOne() {
        // PPid = 1 (init process) indicates process started directly by init
        // This could be a sign of debugging
        val status = """
            Name:   app_process
            TracerPid: 0
            PPid:   1
        """.trimIndent()

        assertTrue(DebugDetector.parsePtraceStatus(status, currentPid))
    }

    @Test
    fun parsePtraceStatus_returnsFalse_whenPpidIsZero() {
        // PPid = 0 is unusual but not necessarily debugging
        val statusWithZeroPpid = """
            Name:   app_process
            TracerPid: 0
            PPid:   0
        """.trimIndent()

        assertFalse(DebugDetector.parsePtraceStatus(statusWithZeroPpid, currentPid))
    }

    @Test
    fun parsePtraceStatus_returnsTrue_whenBothTracerPidAndPpidAreSuspicious() {
        val status = """
            Name:   app_process
            TracerPid: 1234
            PPid:   1
        """.trimIndent()

        assertTrue(DebugDetector.parsePtraceStatus(status, currentPid))
    }

    @Test
    fun parsePtraceStatus_returnsFalse_whenNormalAppScenario() {
        // Normal Android app: TracerPid=0, PPid=zygote (e.g., 123)
        val status = """
            Name:   app_process
            TracerPid: 0
            PPid:   123
        """.trimIndent()

        assertFalse(DebugDetector.parsePtraceStatus(status, currentPid))
    }

    @Test
    fun checkDebugBuild_returnsTrue_inDebugUnitTestBuild() {
        assertTrue(DebugDetector.checkDebugBuild())
    }

    @Test
    fun resolveDebugBuildState_returnsFalse_whenReleaseSignalsAndNotDebuggable() {
        val result = DebugDetector.resolveDebugBuildState(
            isBuildConfigDebug = false,
            applicationInfoFlagsProvider = { 0 }
        )

        assertFalse(result)
    }

    @Test
    fun resolveDebugBuildState_returnsFalse_whenFlagsAreNullInNonDebugBuild() {
        val result = DebugDetector.resolveDebugBuildState(
            isBuildConfigDebug = false,
            applicationInfoFlagsProvider = { null }
        )

        assertFalse(result)
    }

    @Test
    fun resolveDebugBuildState_keepsTrue_whenBuildConfigDebugEvenIfFlagsProviderThrows() {
        val result = DebugDetector.resolveDebugBuildState(
            isBuildConfigDebug = true,
            applicationInfoFlagsProvider = { throw IllegalStateException("boom") }
        )

        assertTrue(result)
    }

    @Test
    fun resolveDebugBuildState_returnsTrue_whenApplicationInfoHasDebuggableFlag() {
        val result = DebugDetector.resolveDebugBuildState(
            isBuildConfigDebug = false,
            applicationInfoFlagsProvider = { ApplicationInfo.FLAG_DEBUGGABLE }
        )

        assertTrue(result)
    }

    // ==================== checkDebugProperties() / resolveDebugPropertiesState() ====================

    @Test
    fun checkDebugProperties_doesNotThrow_inUnitTestEnvironment() {
        // 单元测试环境下反射与 getprop 子进程均不可用，
        // 验证异常被正确吞掉且不会向上抛出
        assertFalse(DebugDetector.checkDebugProperties())
    }

    @Test
    fun resolveDebugPropertiesState_returnsTrue_whenReflectionSucceedsAndRoDebuggableIsOne() {
        // 路径1：反射成功读取调试属性
        FakeSystemProperties.values = mapOf(
            "ro.debuggable" to "1",
            "ro.secure" to "1",
            "persist.sys.usb.config" to "mtp"
        )

        val result = DebugDetector.resolveDebugPropertiesState(
            getMethodProvider = { fakeGetMethod() },
            processPropertyReader = { fail("反射成功时不应回退到 getprop 子进程"); null }
        )

        assertTrue(result)
    }

    @Test
    fun resolveDebugPropertiesState_returnsFalse_whenReflectionSucceedsWithSecureValues() {
        FakeSystemProperties.values = mapOf(
            "ro.debuggable" to "0",
            "ro.secure" to "1",
            "persist.sys.usb.config" to "mtp"
        )

        val result = DebugDetector.resolveDebugPropertiesState(
            getMethodProvider = { fakeGetMethod() },
            processPropertyReader = { fail("反射成功时不应回退到 getprop 子进程"); null }
        )

        assertFalse(result)
    }

    @Test
    fun resolveDebugPropertiesState_fallsBackToGetprop_whenReflectionCompletelyUnavailable() {
        // 路径2：反射整体不可用（如 ClassNotFoundException / NoSuchMethodException /
        // hidden API 被阻断），fallback 到 getprop 子进程
        val requestedProps = mutableListOf<String>()
        val fallbackValues = mapOf(
            "ro.debuggable" to "0",
            "ro.secure" to "1",
            "persist.sys.usb.config" to "adb,mtp"
        )

        val result = DebugDetector.resolveDebugPropertiesState(
            getMethodProvider = { throw ClassNotFoundException("android.os.SystemProperties") },
            processPropertyReader = { prop ->
                requestedProps.add(prop)
                fallbackValues[prop]
            }
        )

        assertTrue(result)
        assertEquals(listOf("ro.debuggable", "ro.secure", "persist.sys.usb.config"), requestedProps)
    }

    @Test
    fun resolveDebugPropertiesState_returnsFalse_whenReflectionUnavailableAndFallbackAlsoFails() {
        val result = DebugDetector.resolveDebugPropertiesState(
            getMethodProvider = { throw NoSuchMethodException("get") },
            processPropertyReader = { null }
        )

        assertFalse(result)
    }

    @Test
    fun resolveDebugPropertiesState_continuesCheckingRemainingProperties_whenSinglePropertyReflectionThrows() {
        // 路径3：单条属性反射异常不会中断后续属性检查
        ThrowingSystemProperties.throwOnKeys = setOf("ro.debuggable")
        ThrowingSystemProperties.values = mapOf("persist.sys.usb.config" to "adb0")
        val fallbackCalls = mutableListOf<String>()

        val result = DebugDetector.resolveDebugPropertiesState(
            getMethodProvider = { throwingGetMethod() },
            processPropertyReader = { prop ->
                fallbackCalls.add(prop)
                null
            }
        )

        assertTrue(result)
        // 仅对反射失败的属性触发 getprop 回退，其它属性仍通过反射成功检测
        assertEquals(listOf("ro.debuggable"), fallbackCalls)
    }

    @Test
    fun readDebugPropertyValue_returnsReflectionValue_whenReflectionSucceeds() {
        FakeSystemProperties.values = mapOf("ro.debuggable" to "1")

        val value = DebugDetector.readDebugPropertyValue(
            prop = "ro.debuggable",
            getMethod = fakeGetMethod(),
            processPropertyReader = { fail("反射成功时不应回退到 getprop 子进程"); null }
        )

        assertEquals("1", value)
    }

    @Test
    fun readDebugPropertyValue_fallsBackToProcessReader_whenReflectionThrows() {
        ThrowingSystemProperties.throwOnKeys = setOf("ro.debuggable")

        val value = DebugDetector.readDebugPropertyValue(
            prop = "ro.debuggable",
            getMethod = throwingGetMethod(),
            processPropertyReader = { "1" }
        )

        assertEquals("1", value)
    }

    @Test
    fun readDebugPropertyValue_returnsNull_whenGetMethodIsNullAndProcessReaderReturnsNull() {
        val value = DebugDetector.readDebugPropertyValue(
            prop = "ro.debuggable",
            getMethod = null,
            processPropertyReader = { null }
        )

        assertNull(value)
    }
}
