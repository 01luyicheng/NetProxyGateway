package com.netproxy.gateway.proxy

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class Socks5ConnectionPoolConcurrentTest {

    // C74: 使用本地 mock SOCKS5 服务器完成真实的握手，而不是连接测试环境中不存在的
    // 127.0.0.1:1080。这样 borrowConnection 才会真正创建/复用连接，从而让并发的
    // borrow/return/cleanup 竞态路径被实际执行到，而不是每次都因为连接失败提前返回 null。
    @Test
    fun testConcurrentBorrowReturnAndCleanup() {
        val mockServer = MockSocks5Server()
        try {
            val pool = Socks5ConnectionPool(
                proxyHost = "127.0.0.1",
                proxyPort = mockServer.port,
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
            val borrowedCount = AtomicInteger(0)

            val destIp = "10.0.0.1"
            val destPort = 8080

            for (i in 0 until threadCount) {
                executor.submit {
                    try {
                        for (j in 0 until iterationsPerThread) {
                            // Borrow
                            val conn = pool.borrowConnection(destIp, destPort) { true }
                            if (conn != null) {
                                borrowedCount.incrementAndGet()
                                // Simulate use
                                Thread.sleep(1)
                                // Return
                                pool.returnConnection(conn)
                            }
                            // else: pool exhausted or handshake failed under contention, which is a
                            // valid outcome and should not be treated as an error by itself.
                        }
                    } catch (e: Exception) {
                        errorCount.incrementAndGet()
                        e.printStackTrace()
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await(60, TimeUnit.SECONDS)
            executor.shutdown()
            pool.shutdown()

            assertEquals("Should be no errors during concurrent execution", 0, errorCount.get())
            assertTrue(
                "Expected borrowConnection to succeed against the local mock SOCKS5 server at least once " +
                    "(otherwise the test isn't exercising the real borrow/return/cleanup race conditions)",
                borrowedCount.get() > 0
            )
        } finally {
            mockServer.close()
        }
    }

    /**
     * 一个最小化的本地 SOCKS5 服务器：完成用户名/密码握手协商与 CONNECT 应答，
     * 之后保持连接打开直至客户端关闭，用于在测试中构造可控、真实的并发连接场景。
     */
    private class MockSocks5Server : AutoCloseable {
        private val serverSocket = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)

        val port: Int get() = serverSocket.localPort

        private val acceptThread = Thread({
            while (running.get()) {
                val client = try {
                    serverSocket.accept()
                } catch (e: Exception) {
                    break
                }
                Thread({ handleClient(client) }, "MockSocks5Server-Client").apply {
                    isDaemon = true
                    start()
                }
            }
        }, "MockSocks5Server-Accept").apply {
            isDaemon = true
            start()
        }

        private fun handleClient(socket: Socket) {
            try {
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                // 1. 方法协商: VER(1) NMETHODS(1) METHODS(NMETHODS)
                val negHeader = ByteArray(2)
                readFully(input, negHeader)
                val nMethods = negHeader[1].toInt() and 0xFF
                readFully(input, ByteArray(nMethods))
                output.write(byteArrayOf(0x05, 0x02)) // 选择用户名/密码认证
                output.flush()

                // 2. 用户名/密码认证: VER(1) ULEN(1) UNAME PLEN(1) PASSWD
                val authHeader = ByteArray(2)
                readFully(input, authHeader)
                val uLen = authHeader[1].toInt() and 0xFF
                readFully(input, ByteArray(uLen))
                val pLenBuf = ByteArray(1)
                readFully(input, pLenBuf)
                val pLen = pLenBuf[0].toInt() and 0xFF
                readFully(input, ByteArray(pLen))
                output.write(byteArrayOf(0x05, 0x00)) // 认证成功
                output.flush()

                // 3. CONNECT 请求: VER(1) CMD(1) RSV(1) ATYP(1) ADDR PORT(2)
                val connectHeader = ByteArray(4)
                readFully(input, connectHeader)
                val addrLen = when (connectHeader[3].toInt() and 0xFF) {
                    0x01 -> 4
                    0x03 -> {
                        val lenBuf = ByteArray(1)
                        readFully(input, lenBuf)
                        lenBuf[0].toInt() and 0xFF
                    }
                    0x04 -> 16
                    else -> 4
                }
                readFully(input, ByteArray(addrLen))
                readFully(input, ByteArray(2)) // 目标端口

                // 应答 CONNECT 成功，绑定地址 0.0.0.0:0
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()

                // 握手完成后保持连接打开（连接池会复用该连接），直到客户端关闭
                val buf = ByteArray(1024)
                while (running.get()) {
                    val read = try {
                        input.read(buf)
                    } catch (e: Exception) {
                        -1
                    }
                    if (read < 0) break
                }
            } catch (e: Exception) {
                // 客户端可能因清理/关闭连接而提前断开，这是并发测试中的预期情况
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        private fun readFully(input: InputStream, target: ByteArray) {
            var offset = 0
            while (offset < target.size) {
                val read = input.read(target, offset, target.size - offset)
                if (read < 0) throw EOFException("Unexpected EOF in mock SOCKS5 server")
                offset += read
            }
        }

        override fun close() {
            running.set(false)
            try {
                serverSocket.close()
            } catch (e: Exception) {
                // ignore
            }
            acceptThread.interrupt()
        }
    }
}
