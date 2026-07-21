package com.netproxy.gateway.security

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        // 路径1：反射成功读取调试属性；REV47 后仍会并行调用 getprop 作为独立校验路径
        FakeSystemProperties.values = mapOf(
            "ro.debuggable" to "1",
            "ro.secure" to "1",
            "persist.sys.usb.config" to "mtp"
        )
        val processCalls = mutableListOf<String>()

        val result = DebugDetector.resolveDebugPropertiesState(
            getMethodProvider = { fakeGetMethod() },
            processPropertyReader = { prop ->
                processCalls.add(prop)
                null
            }
        )

        assertTrue(result)
        // 反射已在 ro.debuggable 识别 debug，后续属性被短路，但 getprop 仍被调用一次
        assertEquals(listOf("ro.debuggable"), processCalls)
    }

    @Test
    fun resolveDebugPropertiesState_returnsFalse_whenBothReflectionAndGetpropAreSecure() {
        FakeSystemProperties.values = mapOf(
            "ro.debuggable" to "0",
            "ro.secure" to "1",
            "persist.sys.usb.config" to "mtp"
        )
        val processCalls = mutableListOf<String>()

        val result = DebugDetector.resolveDebugPropertiesState(
            getMethodProvider = { fakeGetMethod() },
            processPropertyReader = { prop ->
                processCalls.add(prop)
                null
            }
        )

        assertFalse(result)
        assertEquals(listOf("ro.debuggable", "ro.secure", "persist.sys.usb.config"), processCalls)
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
        // 路径3：单条属性反射异常不会中断后续属性检查；REV47 后 getprop 会作为独立路径检查所有属性
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
        // REV47 后 union 检测：每条属性都会同时走反射与 getprop，因此三个属性都会触发 getprop
        assertEquals(listOf("ro.debuggable", "ro.secure", "persist.sys.usb.config"), fallbackCalls)
    }

    @Test
    fun readPropertyValueViaReflection_returnsValue_whenReflectionSucceeds() {
        FakeSystemProperties.values = mapOf("ro.debuggable" to "1")

        val value = DebugDetector.readPropertyValueViaReflection(
            prop = "ro.debuggable",
            getMethod = fakeGetMethod()
        )

        assertEquals("1", value)
    }

    @Test
    fun readPropertyValueViaReflection_returnsNull_whenReflectionThrows() {
        ThrowingSystemProperties.throwOnKeys = setOf("ro.debuggable")

        val value = DebugDetector.readPropertyValueViaReflection(
            prop = "ro.debuggable",
            getMethod = throwingGetMethod()
        )

        assertNull(value)
    }

    // ==================== readProcessOutput() ====================

    @Test
    fun readProcessOutput_returnsFirstLine_whenProcessOutputsLine() {
        val command = if (isWindows()) {
            listOf("cmd", "/c", "echo hello")
        } else {
            listOf("sh", "-c", "echo hello")
        }

        val result = DebugDetector.readProcessOutput(command)

        assertEquals("hello", result)
    }

    @Test(timeout = 10000)
    fun readProcessOutput_returnsNull_whenProcessHangsWithoutOutput() {
        // 模拟 getprop 子进程卡住且不输出换行的情况。
        // 修复前 readLine() 会无限阻塞；修复后应在 PROCESS_TIMEOUT_SECONDS 内超时并返回 null。
        val command = if (isWindows()) {
            listOf("cmd", "/c", "ping -n 100 127.0.0.1 > nul")
        } else {
            listOf("sleep", "100")
        }

        val startTime = System.currentTimeMillis()
        val result = DebugDetector.readProcessOutput(command)
        val elapsedMs = System.currentTimeMillis() - startTime

        assertNull(result)
        // 超时时间为 3 秒，允许 4 秒调度/清理余量（实现里读取超时后还会 waitFor 一次）
        assertTrue("Expected timeout but completed in ${elapsedMs}ms", elapsedMs < 7000)
    }

    @Test
    fun readProcessOutput_returnsNull_whenProcessNotFound() {
        val result = DebugDetector.readProcessOutput(listOf("nonexistent-command-xyz"))

        assertNull(result)
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true
    }

    @Test
    fun readPropertyValueViaReflection_returnsNull_whenGetMethodIsNull() {
        val value = DebugDetector.readPropertyValueViaReflection(
            prop = "ro.debuggable",
            getMethod = null
        )

        assertNull(value)
    }

    @Test
    fun resolveDebugPropertiesState_detectsDebugViaProcess_whenReflectionIsHooked() {
        // Simulate Frida hook: reflection returns safe values, but getprop returns debug values
        FakeSystemProperties.values = mapOf(
            "ro.debuggable" to "0",   // Hooked: returns "0" (safe)
            "ro.secure" to "1",        // Hooked: returns "1" (safe)
            "persist.sys.usb.config" to "mtp"  // Hooked: returns "mtp" (safe)
        )

        var processCalls = mutableListOf<String>()
        val result = DebugDetector.resolveDebugPropertiesState(
            getMethodProvider = { fakeGetMethod() },
            processPropertyReader = { prop ->
                processCalls.add(prop)
                when (prop) {
                    "ro.debuggable" -> "1"   // Real value from getprop
                    "ro.secure" -> "1"
                    "persist.sys.usb.config" -> "mtp"
                    else -> null
                }
            }
        )

        assertTrue(result)
        assertTrue(processCalls.contains("ro.debuggable"))
    }
    // ------------------------------------------------------------------
    // checkTimingAttack — 反动态调试时序检测
    //
    // 以下测试是 PR #95 的回归守卫。PR #95 误将 `checkTimingAttack` 中的工作负载
    // 循环判为“死代码”删除，使函数在默认 1000ms 阈值下恒返回 false（两次相邻的
    // `System.currentTimeMillis()` 调用差值在毫秒粒度上几乎总是 0），导致反调试
    // 检测形同虚设。PR #95 添加的两条测试 (`returnsFalse_underNormalExecution`
    // 与 `returnsTrue_whenExecutionExceedsThreshold`) 在回归后仍是 tautology
    // （前者恒真、后者用 `-1` 阈值也是恒真），无法捕获回归。
    //
    // 本批测试通过可注入的时钟 (`now: () -> Long`) 让时序逻辑可被确定性验证，
    // 并通过 [@Volatile][DebugDetector.workloadFingerprint] 字段断言工作负载确实执行。
    // ------------------------------------------------------------------

    /**
     * 默认 1000ms 阈值下，工作负载（100 万次整数累加）在真实硬件上的耗时远低于
     * 1000ms，应返回 false。该测试现在具有实际意义：循环若被删除，[workloadFingerprint]
     * 不会更新，会被 [checkTimingAttack_executesObservableWorkload] 捕获。
     */
    @Test
    fun checkTimingAttack_returnsFalse_underNormalExecution() {
        assertFalse(DebugDetector.checkTimingAttack(1000))
    }

    /**
     * 模拟调试器单步执行：让注入的时钟在两次读取之间前进 2000ms。该测试不依赖
     * 真实时序或实际调试器附加，可确定性验证阈值逻辑。
     */
    @Test
    fun checkTimingAttack_returnsTrue_whenClockSimulatesDebuggerSlowdown() {
        val times = ArrayDeque(listOf(0L, 2000L))
        val fakeClock = { times.removeFirst() }
        assertTrue(
            "slow clock (2000ms diff) must trigger detection at 1000ms threshold",
            DebugDetector.checkTimingAttack(thresholdMs = 1000, now = fakeClock)
        )
    }

    /**
     * 模拟快速执行：注入的时钟只前进 10ms。低于 1000ms 阈值，应返回 false。
     */
    @Test
    fun checkTimingAttack_returnsFalse_whenClockSimulatesFastExecution() {
        val times = ArrayDeque(listOf(0L, 10L))
        val fakeClock = { times.removeFirst() }
        assertFalse(
            "fast clock (10ms diff) must not trigger detection at 1000ms threshold",
            DebugDetector.checkTimingAttack(thresholdMs = 1000, now = fakeClock)
        )
    }

    /**
     * 边界条件：diff 恰好等于阈值（严格 `>` 比较）应返回 false。
     */
    @Test
    fun checkTimingAttack_returnsFalse_whenDiffEqualsThreshold() {
        val times = ArrayDeque(listOf(0L, 1000L))
        val fakeClock = { times.removeFirst() }
        assertFalse(
            "diff == threshold must NOT trigger (strict > comparison)",
            DebugDetector.checkTimingAttack(thresholdMs = 1000, now = fakeClock)
        )
    }

    /**
     * PR #95 回归守卫：工作负载循环必须实际执行，不能被静默删除或被 DCE 消除。
     *
     * 双重断言：
     * 1. [DebugDetector.timingCheckInvocationCount] 在循环之后递增——证明函数体执行
     *    到了循环之后的代码（如果循环被删除且计数器行也跟着删除，编译会失败；若只
     *    删除循环，计数器仍递增但 fingerprint 不更新，断言 2 捕获）。
     * 2. [DebugDetector.workloadFingerprint] 等于 `0 until 1_000_000` 的累加和（带
     *    Int 溢出回绕）——若循环被删除，`sum` 永远为 0，fingerprint 与期望值不符。
     */
    @Test
    fun checkTimingAttack_executesObservableWorkload() {
        val beforeCount = DebugDetector.timingCheckInvocationCount

        // 用 Long.MAX_VALUE 阈值确保不触发（与触发逻辑解耦，专注工作负载可观测性）
        DebugDetector.checkTimingAttack(thresholdMs = Long.MAX_VALUE)

        assertEquals(
            "timingCheckInvocationCount must increment after checkTimingAttack (function completed)",
            beforeCount + 1,
            DebugDetector.timingCheckInvocationCount
        )

        // 0 until 1_000_000 的累加和（Kotlin Int 溢出回绕，与生产代码 `sum += i` 语义一致）
        val expected = (0 until 1_000_000).sum()
        assertEquals(
            "workloadFingerprint must equal expected sum (loop must execute, DCE guard). " +
                "If this fails, the workload loop was likely removed — see PR #95 regression.",
            expected,
            DebugDetector.workloadFingerprint
        )
    }
}
