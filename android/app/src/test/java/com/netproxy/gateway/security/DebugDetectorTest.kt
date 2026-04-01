package com.netproxy.gateway.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugDetectorTest {

    @Test
    fun parsePtraceStatus_returnsTrue_whenTracerPidIsNonZero() {
        val status = """
            Name:   app_process
            TracerPid: 1234
            PPid:   567
        """.trimIndent()

        assertTrue(DebugDetector.parsePtraceStatus(status))
    }

    @Test
    fun parsePtraceStatus_returnsFalse_whenTracerPidIsZero() {
        val status = """
            Name:   app_process
            TracerPid: 0
            PPid:   567
        """.trimIndent()

        assertFalse(DebugDetector.parsePtraceStatus(status))
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
        """.trimIndent()

        assertFalse(DebugDetector.parsePtraceStatus(missingTracerPid))
        assertFalse(DebugDetector.parsePtraceStatus(invalidTracerPid))
    }
}
