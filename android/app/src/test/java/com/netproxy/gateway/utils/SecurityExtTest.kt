package com.netproxy.gateway.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityExtTest {

    @Test
    fun `securelyClear clears CharArray`() {
        val data = "secret".toCharArray()
        data.securelyClear()
        assertTrue(data.all { it == '\u0000' })
    }

    @Test
    fun `securelyClear clears ByteArray`() {
        val data = "secret".toByteArray()
        data.securelyClear()
        assertTrue(data.all { it == 0.toByte() })
    }

    @Test
    fun `securelyClear handles null CharArray`() {
        val nullArray: CharArray? = null
        nullArray.securelyClear() // should not throw
    }

    @Test
    fun `securelyClear handles null ByteArray`() {
        val nullArray: ByteArray? = null
        nullArray.securelyClear() // should not throw
    }

    @Test
    fun `securelyClear handles empty CharArray`() {
        val emptyArray = CharArray(0)
        emptyArray.securelyClear() // should not throw
        assertTrue(emptyArray.isEmpty())
    }

    @Test
    fun `securelyClear handles empty ByteArray`() {
        val emptyArray = ByteArray(0)
        emptyArray.securelyClear() // should not throw
        assertTrue(emptyArray.isEmpty())
    }

    @Test
    fun `securelyClear is idempotent for CharArray`() {
        val data = "secret".toCharArray()
        data.securelyClear()
        data.securelyClear()
        assertTrue(data.all { it == '\u0000' })
    }

    @Test
    fun `securelyClear is idempotent for ByteArray`() {
        val data = "secret".toByteArray()
        data.securelyClear()
        data.securelyClear()
        assertTrue(data.all { it == 0.toByte() })
    }
}
