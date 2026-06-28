package com.netproxy.gateway.proxy

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Socks5ConnectionPoolConcurrentTest {

    @Test
    fun testConcurrentBorrowReturnAndCleanup() {
        // C74: 添加多线程并发测试，模拟 borrow/return/cleanup 竞态条件
        val pool = Socks5ConnectionPool(
            config = Socks5ConnectionPoolConfig(
                maxConnections = 50,
                idleTimeoutMs = 1, // Fast timeout for cleanup
                cleanupIntervalMs = 10 // Fast cleanup interval
            ),
            credentialProvider = { Pair("user", charArrayOf('p')) }
        )

        val threadCount = 20
        val iterationsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errorCount = AtomicInteger(0)

        // Add some dummy connections to be cleaned up
        val destIp = "10.0.0.1"
        val destPort = 8080

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    for (j in 0 until iterationsPerThread) {
                        // Borrow
                        val conn = pool.borrowConnection(destIp, destPort) { true }
                        if (conn != null) {
                            // Simulate use
                            Thread.sleep(1)
                            // Return
                            pool.returnConnection(conn)
                        } else {
                            // Pool exhausted or failed
                        }
                    }
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()
        pool.shutdown()

        assertEquals("Should be no errors during concurrent execution", 0, errorCount.get())
    }
}
