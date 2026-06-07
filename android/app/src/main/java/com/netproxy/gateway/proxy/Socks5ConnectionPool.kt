package com.netproxy.gateway.proxy

import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * SOCKS5连接池配置
 */
data class Socks5ConnectionPoolConfig(
    val maxConnections: Int = 50,
    val idleTimeoutMs: Long = 60_000L,
    val connectionTimeoutMs: Int = 5_000,
    val socketSoTimeoutMs: Int = 30_000,
    val minIdleConnections: Int = 5,
    val maxConnectionsPerDestination: Int = 10,
    val cleanupIntervalMs: Long = 30_000L
)

/**
 * 连接池中的SOCKS5连接包装器
 */
class PooledSocks5Connection(
    val socket: Socket,
    val destinationIp: String,
    val destinationPort: Int,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        private val logger = LoggerFactory.getLogger(PooledSocks5Connection::class.java)
    }

    val lastUsedAt = AtomicLong(createdAt)
    val inUse = AtomicBoolean(false)
    val useCount = AtomicInteger(0)

    fun markUsed() {
        lastUsedAt.set(System.currentTimeMillis())
        useCount.incrementAndGet()
        inUse.set(true)
    }

    fun markReturned() {
        inUse.set(false)
    }

    fun isValid(): Boolean {
        return socket.isConnected && !socket.isClosed && !socket.isInputShutdown && !socket.isOutputShutdown
    }

    fun close() {
        // N86: 标记为不在使用中，让连接池的 cleanupIdleConnections 能够清理此连接
        inUse.set(false)
        try {
            socket.close()
        } catch (e: Exception) {
            logger.debug(
                "Failed to close pooled SOCKS5 socket for {}:{}, cause={}",
                destinationIp,
                destinationPort,
                e.message
            )
        }
    }
}

/**
 * SOCKS5连接池
 *
 * 管理到本地SOCKS5代理的可复用连接，避免每次转发都重新进行SOCKS5握手。
 * 连接池按目标地址（destinationIp:destinationPort）分组管理连接。
 */
class Socks5ConnectionPool(
    private val proxyHost: String = "127.0.0.1",
    private val proxyPort: Int = 1080,
    private val config: Socks5ConnectionPoolConfig = Socks5ConnectionPoolConfig(),
    private val credentialProvider: () -> Pair<String, CharArray>?
) {
    companion object {
        private val logger = LoggerFactory.getLogger(Socks5ConnectionPool::class.java)

        // 预分配的静态缓冲区，用于SOCKS5握手
        private val SOCKS5_METHOD_REQUEST = byteArrayOf(0x05, 0x01, 0x02)
        private val SOCKS5_AUTH_VERSION = byteArrayOf(0x01)
        private val SOCKS5_CONNECT_HEADER = byteArrayOf(0x05, 0x01, 0x00, 0x01)
    }

    init {
        require(config.socketSoTimeoutMs > 0) {
            "socketSoTimeoutMs must be positive (SOCKS5 handshake reads use Socket.soTimeout)"
        }
        require(config.connectionTimeoutMs > 0) {
            "connectionTimeoutMs must be positive"
        }
    }

    private val totalConnections = AtomicInteger(0)
    private val poolLock = ReentrantReadWriteLock()

    // 按目标地址分组的连接队列：destinationKey -> 可用连接队列
    private val availableConnections = ConcurrentHashMap<String, LinkedBlockingQueue<PooledSocks5Connection>>()

    // 所有活跃连接（包括正在使用的）
    private val allConnections = ConcurrentHashMap<PooledSocks5Connection, String>()

    private val isShutdown = AtomicBoolean(false)
    private var cleanupThread: Thread? = null

    init {
        startCleanupThread()
    }

    /**
     * 获取或创建 SOCKS5 连接
     *
     * @param destinationIp 目标 IP 地址
     * @param destinationPort 目标端口
     * @param protectSocket 可选的 socket 保护函数（用于 VPN 场景）
     * @return 可用的 SOCKS5 连接，使用完毕后必须调用 returnConnection 归还
     */
    fun borrowConnection(
        destinationIp: String,
        destinationPort: Int,
        protectSocket: ((Socket) -> Boolean)? = null
    ): PooledSocks5Connection? {
        if (isShutdown.get()) {
            return null
        }

        val destKey = "$destinationIp:$destinationPort"

        // 首先尝试从池中获取可用连接
        val invalidConnections = mutableListOf<PooledSocks5Connection>()
        val connection = poolLock.read {
            val queue = availableConnections[destKey]
            if (queue != null) {
                // 尝试获取有效连接
                while (true) {
                    val conn = queue.poll() ?: break
                    if (conn.isValid() && !conn.inUse.get()) {
                        conn.markUsed()
                        return@read conn
                    } else {
                        // 收集无效连接，稍后清理
                        invalidConnections.add(conn)
                    }
                }
            }
            null
        }

        // 在读锁外清理无效连接，避免在读锁内获取写锁
        if (invalidConnections.isNotEmpty()) {
            val toClose = mutableListOf<PooledSocks5Connection>()
            poolLock.write {
                invalidConnections.forEach { conn ->
                    // Re-check in use state under write lock to avoid racing with a concurrent borrow.
                    if (!conn.inUse.get() && !conn.isValid() && allConnections.remove(conn) != null) {
                        totalConnections.decrementAndGet()
                        toClose.add(conn)
                    }
                }
            }
            // Close sockets outside the write lock to avoid blocking borrow/return operations.
            toClose.forEach { it.close() }
        }

        if (connection != null) {
            return connection
        }

        // 池中没有可用连接，创建新连接
        return createNewConnection(destinationIp, destinationPort, protectSocket)
    }

    /**
     * 归还连接回连接池
     */
    fun returnConnection(connection: PooledSocks5Connection) {
        if (isShutdown.get()) {
            connection.close()
            return
        }

        val destKey = "${connection.destinationIp}:${connection.destinationPort}"
        var toClose: PooledSocks5Connection? = null

        poolLock.write {
            if (isShutdown.get()) {
                toClose = connection
                return@write
            }

            // Connection might have been concurrently cleaned up before returning.
            if (!allConnections.containsKey(connection)) {
                toClose = connection
                return@write
            }

            if (!connection.isValid()) {
                removeConnection(connection)
                toClose = connection
                return@write
            }

            connection.markReturned()

            val queue = availableConnections.getOrPut(destKey) { LinkedBlockingQueue() }

            // 检查该目标地址的连接数是否超过限制
            val currentCount = queue.count { !it.inUse.get() } +
                allConnections.keys.count {
                    it.destinationIp == connection.destinationIp &&
                        it.destinationPort == connection.destinationPort &&
                        it.inUse.get()
                }

            if (currentCount >= config.maxConnectionsPerDestination) {
                // 超过限制，关闭此连接
                removeConnection(connection)
                toClose = connection
            } else {
                queue.offer(connection)
            }
        }

        // Close socket outside the write lock to avoid blocking borrow/return operations.
        toClose?.close()
    }

    /**
     * 丢弃连接：从池中移除跟踪并关闭 socket。
     * 用于连接因 N86 原因（过期/无效/异常）不应归还到池中的场景。
     */
    fun discardConnection(connection: PooledSocks5Connection) {
        val destKey = "${connection.destinationIp}:${connection.destinationPort}"
        poolLock.write {
            availableConnections[destKey]?.remove(connection)
            removeConnection(connection)
        }
        connection.close()
    }

    /**
     * 创建新的SOCKS5连接
     */
    private fun createNewConnection(
        destinationIp: String,
        destinationPort: Int,
        protectSocket: ((Socket) -> Boolean)?
    ): PooledSocks5Connection? {
        // 使用 try-finally 确保连接计数一致性
        var slotReserved = false
        var connectionEstablished = false
        var trackedConnection: PooledSocks5Connection? = null

        try {
            if (!tryReserveConnectionSlot()) {
                logger.warn("Connection pool exhausted, max=$config.maxConnections")
                return null
            }
            slotReserved = true

            val credentials = credentialProvider()
            if (credentials == null) {
                logger.warn("No credentials available for SOCKS5 connection")
                return null
            }

            val (username, password) = credentials

            val socket = createSocks5Socket(destinationIp, destinationPort, username, password, protectSocket)
            val connection = PooledSocks5Connection(socket, destinationIp, destinationPort)
            connection.markUsed()

            trackedConnection = connection
            allConnections[connection] = "$destinationIp:$destinationPort"
            connectionEstablished = true

            return connection
        } catch (e: Exception) {
            trackedConnection?.let {
                allConnections.remove(it)
                it.close()
            }
            logger.error("Failed to create SOCKS5 connection to $destinationIp:$destinationPort", e)
            return null
        } finally {
            // 统一在 finally 块中管理连接计数，确保一致性
            if (slotReserved && !connectionEstablished) {
                totalConnections.decrementAndGet()
            }
        }
    }

    private fun tryReserveConnectionSlot(): Boolean {
        while (true) {
            val current = totalConnections.get()
            if (current >= config.maxConnections) {
                return false
            }
            if (totalConnections.compareAndSet(current, current + 1)) {
                return true
            }
        }
    }

    /**
     * 创建SOCKS5 Socket并进行完整握手
     */
    private fun createSocks5Socket(
        destinationIp: String,
        destinationPort: Int,
        username: String,
        password: CharArray,
        protectSocket: ((Socket) -> Boolean)?
    ): Socket {
        val socket = Socket().apply {
            protectSocket?.invoke(this)
            connect(InetSocketAddress(proxyHost, proxyPort), config.connectionTimeoutMs)
            soTimeout = config.socketSoTimeoutMs
            tcpNoDelay = true
        }

        return try {
            val output = socket.getOutputStream()
            val input = socket.getInputStream()

            // SOCKS5握手流程
            performSocks5Handshake(input, output, username, password, destinationIp, destinationPort)

            socket
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (closeError: Exception) {
                logger.debug(
                    "Failed to close SOCKS5 socket after setup failure for {}:{}, cause={}",
                    destinationIp,
                    destinationPort,
                    closeError.message
                )
            }
            throw e
        }
    }

    /**
     * 执行SOCKS5握手
     */
    private fun performSocks5Handshake(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        username: String,
        password: CharArray,
        destinationIp: String,
        destinationPort: Int
    ) {
        // 1. 认证方法协商
        output.write(SOCKS5_METHOD_REQUEST)
        output.flush()

        val methodResponse = ByteArray(2)
        readFully(input, methodResponse)
        require(methodResponse[0].toInt() == 0x05 && methodResponse[1].toInt() == 0x02) {
            "SOCKS5 password auth negotiation failed"
        }

        // 2. 用户名/密码认证
        val userBytes = username.toByteArray(Charsets.UTF_8)
        val passBytes = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(password)).array()
        try {
            require(userBytes.size <= 255 && passBytes.size <= 255) { "SOCKS5 credentials too long" }

            output.write(SOCKS5_AUTH_VERSION)
            output.write(userBytes.size)
            output.write(userBytes)
            output.write(passBytes.size)
            output.write(passBytes)
            output.flush()

            val authResponse = ByteArray(2)
            readFully(input, authResponse)
            require(authResponse[1].toInt() == 0x00) { "SOCKS5 authentication failed" }
        } finally {
            // 立即清除临时转换的密码字节数组
            passBytes.fill(0)
        }

        // 3. CONNECT请求
        val addressBytes = InetAddress.getByName(destinationIp).address
        output.write(SOCKS5_CONNECT_HEADER)
        output.write(addressBytes)
        output.write(byteArrayOf((destinationPort shr 8).toByte(), (destinationPort and 0xFF).toByte()))
        output.flush()

        // 读取CONNECT响应
        val connectHeader = ByteArray(4)
        readFully(input, connectHeader)
        require(connectHeader[1].toInt() == 0x00) {
            "SOCKS5 connect failed: ${connectHeader[1].toInt()}"
        }

        // 读取绑定地址（丢弃）
        val boundAddressLength = when (connectHeader[3].toInt()) {
            0x01 -> 4
            0x03 -> {
                val lenBuffer = ByteArray(1)
                readFully(input, lenBuffer)
                lenBuffer[0].toInt() and 0xFF
            }
            0x04 -> 16
            else -> throw IllegalStateException("Unsupported SOCKS5 ATYP ${connectHeader[3].toInt()}")
        }

        // 读取绑定地址和端口
        val boundAddressAndPort = ByteArray(boundAddressLength + 2)
        readFully(input, boundAddressAndPort)
    }

    private fun readFully(input: java.io.InputStream, target: ByteArray) {
        readFully(input, target, 0, target.size)
    }

    private fun readFully(input: java.io.InputStream, target: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= target.size) {
            "Invalid read bounds: offset=$offset, length=$length, targetSize=${target.size}"
        }

        var currentOffset = offset
        val endOffset = offset + length
        while (currentOffset < endOffset) {
            val read = try {
                input.read(target, currentOffset, endOffset - currentOffset)
            } catch (e: SocketTimeoutException) {
                throw IllegalStateException(
                    "SOCKS5 read timeout after ${config.socketSoTimeoutMs}ms",
                    e
                )
            }

            if (read < 0) {
                throw IllegalStateException("Unexpected EOF while reading SOCKS5 stream")
            }
            if (read == 0) {
                continue
            }
            currentOffset += read
        }
    }

    /**
     * 从连接池中移除连接（仅移除跟踪，不关闭socket）
     * 调用者必须在写锁外调用 connection.close() 以避免阻塞其他操作
     */
    private fun removeConnection(connection: PooledSocks5Connection) {
        if (allConnections.remove(connection) != null) {
            totalConnections.decrementAndGet()
        }
    }

    /**
     * 清理空闲超时的连接
     */
    private fun cleanupIdleConnections() {
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<PooledSocks5Connection>()

        // Keep selection and removal in one write lock window to avoid stale decisions.
        poolLock.write {
            allConnections.keys.forEach { conn ->
                if (!conn.inUse.get() && (!conn.isValid() || (now - conn.lastUsedAt.get() > config.idleTimeoutMs))) {
                    toRemove.add(conn)
                }
            }

            toRemove.forEach { conn ->
                val destKey = "${conn.destinationIp}:${conn.destinationPort}"
                availableConnections[destKey]?.remove(conn)
                if (allConnections.remove(conn) != null) {
                    totalConnections.decrementAndGet()
                }
            }
        }

        // Close sockets outside the write lock to avoid blocking borrow/return operations.
        toRemove.forEach { conn ->
            conn.close()
        }

        if (toRemove.isNotEmpty()) {
            logger.debug("Cleaned up ${toRemove.size} idle connections")
        }
    }

    /**
     * 启动清理线程
     */
    private fun startCleanupThread() {
        cleanupThread = Thread({
            while (!isShutdown.get()) {
                try {
                    Thread.sleep(config.cleanupIntervalMs)
                    cleanupIdleConnections()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    logger.error("Error in cleanup thread", e)
                }
            }
        }, "Socks5ConnectionPool-Cleanup").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 关闭连接池
     */
    fun shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            cleanupThread?.interrupt()

            poolLock.write {
                allConnections.keys.forEach { it.close() }
                allConnections.clear()
                availableConnections.clear()
                totalConnections.set(0)
            }

            logger.debug("Connection pool shutdown complete")
        }
    }

    /**
     * 获取连接池统计信息
     */
    fun getStats(): ConnectionPoolStats {
        return poolLock.read {
            ConnectionPoolStats(
                totalConnections = totalConnections.get(),
                availableConnections = allConnections.count { !it.key.inUse.get() },
                inUseConnections = allConnections.count { it.key.inUse.get() },
                destinationCount = availableConnections.size
            )
        }
    }
}

/**
 * 连接池统计信息
 */
data class ConnectionPoolStats(
    val totalConnections: Int,
    val availableConnections: Int,
    val inUseConnections: Int,
    val destinationCount: Int
)
