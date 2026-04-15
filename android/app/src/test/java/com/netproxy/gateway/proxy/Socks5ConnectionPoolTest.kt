package com.netproxy.gateway.proxy

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

class Socks5ConnectionPoolTest {

    @Test
    fun cleanupIdleConnections_removesOnlyIdleConnections() {
        val pool = Socks5ConnectionPool(
            config = Socks5ConnectionPoolConfig(
                idleTimeoutMs = 10,
                cleanupIntervalMs = 60_000
            ),
            credentialProvider = { null }
        )

        try {
            val now = System.currentTimeMillis()
            val idleConnection = PooledSocks5Connection(
                socket = mockValidSocket(),
                destinationIp = "10.0.0.1",
                destinationPort = 443,
                createdAt = now - 1_000
            ).also {
                it.lastUsedAt.set(now - 1_000)
                it.inUse.set(false)
            }

            val activeConnection = PooledSocks5Connection(
                socket = mockValidSocket(),
                destinationIp = "10.0.0.2",
                destinationPort = 443,
                createdAt = now - 1_000
            ).also {
                it.lastUsedAt.set(now - 1_000)
                it.inUse.set(true)
            }

            val allConnections = getPrivateField<ConcurrentHashMap<PooledSocks5Connection, String>>(
                pool,
                "allConnections"
            )
            val availableConnections =
                getPrivateField<ConcurrentHashMap<String, LinkedBlockingQueue<PooledSocks5Connection>>>(
                    pool,
                    "availableConnections"
                )
            val totalConnections = getPrivateField<AtomicInteger>(pool, "totalConnections")

            allConnections[idleConnection] = "10.0.0.1:443"
            allConnections[activeConnection] = "10.0.0.2:443"
            availableConnections["10.0.0.1:443"] = LinkedBlockingQueue<PooledSocks5Connection>().apply {
                offer(idleConnection)
            }
            totalConnections.set(2)

            invokePrivateMethod(pool, "cleanupIdleConnections")

            assertEquals(1, totalConnections.get())
            assertTrue(!allConnections.containsKey(idleConnection))
            assertTrue(allConnections.containsKey(activeConnection))
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun readFully_whenSocketTimeout_throwsIllegalStateException() {
        val pool = Socks5ConnectionPool(
            config = Socks5ConnectionPoolConfig(
                socketSoTimeoutMs = 1234,
                cleanupIntervalMs = 60_000
            ),
            credentialProvider = { null }
        )

        try {
            val input = object : InputStream() {
                override fun read(): Int = -1

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    throw SocketTimeoutException("timed out")
                }
            }

            val method = getReadFullyMethod()
            try {
                method.invoke(pool, input, ByteArray(4), 0, 4)
                throw AssertionError("Expected IllegalStateException")
            } catch (e: Exception) {
                val cause = e.cause
                assertTrue(cause is IllegalStateException)
                assertTrue(cause?.message?.contains("1234") == true)
            }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun returnConnection_whenConnectionNotTracked_shouldNotEnqueueClosedConnection() {
        val socket = mockValidSocket()
        val pool = Socks5ConnectionPool(
            config = Socks5ConnectionPoolConfig(cleanupIntervalMs = 60_000),
            credentialProvider = { null }
        )

        try {
            val connection = PooledSocks5Connection(
                socket = socket,
                destinationIp = "10.0.0.7",
                destinationPort = 443
            )

            pool.returnConnection(connection)

            val availableConnections =
                getPrivateField<ConcurrentHashMap<String, LinkedBlockingQueue<PooledSocks5Connection>>>(
                    pool,
                    "availableConnections"
                )
            val queued = availableConnections["10.0.0.7:443"]?.contains(connection) == true
            assertTrue(!queued)
            verify(atLeast = 1) { socket.close() }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun readFully_whenBoundsInvalid_throwsIllegalArgumentException() {
        val pool = Socks5ConnectionPool(
            config = Socks5ConnectionPoolConfig(cleanupIntervalMs = 60_000),
            credentialProvider = { null }
        )

        try {
            val input = object : InputStream() {
                override fun read(): Int = -1
            }

            val method = getReadFullyMethod()
            try {
                method.invoke(pool, input, ByteArray(2), 1, 5)
                throw AssertionError("Expected IllegalArgumentException")
            } catch (e: Exception) {
                val cause = e.cause
                assertTrue(cause is IllegalArgumentException)
            }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun tryReserveConnectionSlot_enforcesConfiguredMaxConnections() {
        val pool = Socks5ConnectionPool(
            config = Socks5ConnectionPoolConfig(
                maxConnections = 1,
                cleanupIntervalMs = 60_000
            ),
            credentialProvider = { null }
        )

        try {
            val totalConnections = getPrivateField<AtomicInteger>(pool, "totalConnections")
            val method = pool::class.java.getDeclaredMethod("tryReserveConnectionSlot")
            method.isAccessible = true

            totalConnections.set(1)
            val shouldReject = method.invoke(pool) as Boolean
            assertTrue(!shouldReject)

            totalConnections.set(0)
            val shouldReserve = method.invoke(pool) as Boolean
            assertTrue(shouldReserve)
            assertEquals(1, totalConnections.get())
        } finally {
            pool.shutdown()
        }
    }

    private fun getReadFullyMethod(): Method {
        return Socks5ConnectionPool::class.java
            .getDeclaredMethod(
                "readFully",
                InputStream::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            .apply {
                if (!Modifier.isPublic(modifiers)) {
                    isAccessible = true
                }
            }
    }

    private fun mockValidSocket(): Socket {
        return mockk<Socket>(relaxed = true).also { socket ->
            every { socket.isConnected } returns true
            every { socket.isClosed } returns false
            every { socket.isInputShutdown } returns false
            every { socket.isOutputShutdown } returns false
        }
    }

    private fun invokePrivateMethod(target: Any, methodName: String) {
        val method = target::class.java.getDeclaredMethod(methodName)
        method.isAccessible = true
        method.invoke(target)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getPrivateField(target: Any, fieldName: String): T {
        val field = target::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(target) as T
    }
}
