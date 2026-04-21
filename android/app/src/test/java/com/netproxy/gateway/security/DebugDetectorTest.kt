package com.netproxy.gateway.security

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugDetectorTest {

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
}
