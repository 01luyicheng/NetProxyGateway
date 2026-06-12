package com.netproxy.gateway.proxy

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock

class Socks5ConnectionPoolTest {

    @Test
    fun borrowConnection_cleanupInvalidConnections_doesNotCloseValidConnectionWhenInUseFlips() {
        val socket = mockValidSocket()
        val pool = Socks5ConnectionPool(
            config = Socks5ConnectionPoolConfig(
                maxConnections = 0,
                cleanupIntervalMs = 60_000
            ),
            credentialProvider = { null }
        )

        try {
            val destinationIp = "10.0.0.9"
            val destinationPort = 443
            val destKey = "$destinationIp:$destinationPort"

            val connection = PooledSocks5Connection(
                socket = socket,
                destinationIp = destinationIp,
                destinationPort = destinationPort
            ).also {
                it.inUse.set(true)
            }

            val poolLock = getPrivateField<ReentrantReadWriteLock>(pool, "poolLock")
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

            poolLock.writeLock().lock()
            try {
                totalConnections.set(1)
                allConnections[connection] = destKey
                availableConnections[destKey] = LinkedBlockingQueue<PooledSocks5Connection>().apply {
                    offer(connection)
                }
            } finally {
                poolLock.writeLock().unlock()
            }

            // Hold a read lock so the borrow path can complete its read phase but is forced
            // to wait before acquiring the write lock for cleanup.
            poolLock.readLock().lock()
            val borrowThread = Thread {
                pool.borrowConnection(destinationIp, destinationPort)
            }
            try {
                borrowThread.start()

                val queue = availableConnections[destKey]
                    ?: throw AssertionError("Expected destination queue to exist")

                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (queue.isNotEmpty() && System.nanoTime() < deadline) {
                    Thread.yield()
                }
                assertTrue("Expected borrow thread to poll the connection", queue.isEmpty())

                // Simulate the connection becoming available again before cleanup runs.
                connection.inUse.set(false)
            } finally {
                poolLock.readLock().unlock()
            }

            borrowThread.join(2_000)
            if (borrowThread.isAlive) {
                borrowThread.interrupt()
            }
            assertFalse("Expected borrow thread to finish", borrowThread.isAlive)
            verify(exactly = 0) { socket.close() }
        } finally {
            pool.shutdown()
        }
    }

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

    @Test
    fun borrowConnection_whenPoolExhausted_doesNotDecrementTotalConnections() {
        val pool = Socks5ConnectionPool(
            config = Socks5ConnectionPoolConfig(
                maxConnections = 0,
                cleanupIntervalMs = 60_000
            ),
            credentialProvider = { null }
        )

        try {
            val result = pool.borrowConnection("10.0.0.1", 443)
            assertEquals(null, result)

            val totalConnections = getPrivateField<AtomicInteger>(pool, "totalConnections")
            assertEquals(0, totalConnections.get())
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

    @Test
    fun n37b10_createNewConnection_zerosCredentialPasswordAfterUse() {
        var capturedPassword: CharArray? = null
        val secretPassword = "super-secret-token".toCharArray()

        val pool = Socks5ConnectionPool(
            config = Socks5ConnectionPoolConfig(
                maxConnections = 1,
                cleanupIntervalMs = 60_000
            ),
            credentialProvider = {
                val copy = secretPassword.copyOf()
                capturedPassword = copy
                Pair("user", copy)
            }
        )

        try {
            // borrowConnection will fail because there's no real SOCKS5 proxy,
            // but the credentialProvider will be called and the password should be zeroed
            pool.borrowConnection("10.0.0.1", 443)
        } catch (_: Exception) {
            // expected: no real SOCKS5 proxy
        }

        try {
            // The copy returned by credentialProvider should have been zeroed
            assertNotNull("capturedPassword should not be null", capturedPassword)
            assertTrue(
                "Credential password copy should be zeroed after use",
                capturedPassword!!.all { it == '\u0000' }
            )

            // Original secretPassword must remain intact
            assertTrue(
                "Original secretPassword should remain intact",
                secretPassword.contentEquals("super-secret-token".toCharArray()),
            )
        } finally {
            pool.shutdown()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun init_rejectsZeroSocketSoTimeout() {
        Socks5ConnectionPool(
            config = Socks5ConnectionPoolConfig(socketSoTimeoutMs = 0),
            credentialProvider = { null }
        )
    }

    @Test
    fun n86_pooledConnection_close_setsInUseFalse() {
        val socket = mockValidSocket()
        val connection = PooledSocks5Connection(
            socket = socket,
            destinationIp = "10.0.0.1",
            destinationPort = 443
        )

        // Mark as in use first
        connection.markUsed()
        assertTrue(connection.inUse.get())

        // Close should set inUse to false
        connection.close()

        assertFalse(connection.inUse.get())
        verify(atLeast = 1) { socket.close() }
    }
}
