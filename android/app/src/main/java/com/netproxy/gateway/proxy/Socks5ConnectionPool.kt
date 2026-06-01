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

    /**
     * 标记连接为正在使用。
     *
     * 将 lastUsedAt 更新为当前时间，递增 useCount，并将 inUse 置为 `true`。
     */
    fun markUsed() {
        lastUsedAt.set(System.currentTimeMillis())
        useCount.incrementAndGet()
        inUse.set(true)
    }

    /**
     * 将此连接标记为已归还，使其可被复用。
     *
     * 将内部的 `inUse` 标志设为 `false`，表明连接不再被占用。
     */
    fun markReturned() {
        inUse.set(false)
    }

    /**
     * 检查封装的 Socket 是否处于可用状态。
     *
     * @return `true` 如果 socket 已连接、未关闭且输入/输出未被关闭，`false` 否则。
     */
    fun isValid(): Boolean {
        return socket.isConnected && !socket.isClosed && !socket.isInputShutdown && !socket.isOutputShutdown
    }

    /**
     * 关闭封装的底层 Socket。
     *
     * 尝试关闭底层 socket；如果关闭过程中发生异常，方法会捕获异常并记录调试信息，不会向上抛出异常。
     */
    fun close() {
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
    private val credentialProvider: () -> Pair<String, String>?
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
     * 从连接池获取目标地址的可用 SOCKS5 连接；若无可用连接则尝试创建新连接。
     *
     * @param protectSocket 可选的 socket 保护函数（例如用于 VPN 场景），在建立底层 Socket 后调用以应用平台/环境特定的保护。
     * @return 已获取并标记为“使用中”的 `PooledSocks5Connection`，使用完毕必须调用 `returnConnection` 归还；在池已关闭或无法创建连接时返回 `null`。
     */
    fun borrowConnection(
        destinationIp: String,
        destinationPort: Int,
        protectSocket: ((Socket) -> Unit)? = null
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
     * 将已使用的连接归还到连接池或根据条件关闭并移除。
     *
     * 在写锁内检查池是否已关闭、连接是否仍被跟踪以及连接有效性；对有效且未超出单目的地最大连接数的连接将被放回对应目的地的可用队列，
     * 否则从池中移除并在锁外关闭底层 socket。归还过程中可能会修改池的连接计数与映射。
     *
     * @param connection 要归还的 PooledSocks5Connection 实例；该方法可能会关闭此连接的底层 socket。
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
     * 为指定目标创建并在连接池中注册一个新的 SOCKS5 连接。
     *
     * 在成功时会从凭据提供器获取认证信息、建立到代理的 SOCKS5 连接并将其封装为 PooledSocks5Connection 并纳入池的跟踪；在任何失败情况下不建立连接并返回 null。
     *
     * @param destinationIp 目标主机的 IP 或主机名。
     * @param destinationPort 目标端口。
     * @param protectSocket 可选的回调，用于在套接字连接代理前对 Socket 进行额外处理（例如设置路由/保护），可为 null。
     * @return 已建立并注册的 PooledSocks5Connection 实例，创建失败时为 `null`。
     */
    private fun createNewConnection(
        destinationIp: String,
        destinationPort: Int,
        protectSocket: ((Socket) -> Unit)?
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

    /**
     * 尝试为连接池预留一个连接名额（以原子方式增加计数）。
     *
     * @return `true` 如果成功预留了一个名额，`false` 如果已达到配置的最大连接数。
     */
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
     * 建立到本地 SOCKS5 代理的 TCP 连接并完成完整的 SOCKS5 身份验证与 CONNECT 请求，使返回的 socket 直接可用于与目标地址通信。
     *
     * 在连接到代理之前会调用可选的 `protectSocket` 回调（例如用于绑定或设置额外 socket 选项），然后以配置的超时连接代理并设置 `soTimeout` 与 `tcpNoDelay`，随后执行用户名/密码认证与 CONNECT 握手以连接到指定的目标地址。
     *
     * @param destinationIp 目标主机的 IP 地址或可解析的主机名，用于 SOCKS5 CONNECT 请求。
     * @param destinationPort 目标主机的端口，用于 SOCKS5 CONNECT 请求。
     * @param username 用于 SOCKS5 账号密码认证的用户名。
     * @param password 用于 SOCKS5 账号密码认证的密码。
     * @param protectSocket 可选回调，在 socket 建立但尚未连接前调用以对 socket 做特殊处理（例如绑定或 file-descriptor 保护）。
     * @return 已经完成 SOCKS5 握手并连接到指定目标的 `Socket` 实例。
     */
    private fun createSocks5Socket(
        destinationIp: String,
        destinationPort: Int,
        username: String,
        password: String,
        protectSocket: ((Socket) -> Unit)?
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
     * 执行完整的 SOCKS5 握手（含用户名/密码认证）并发起对目标地址的 CONNECT 请求。
     *
     * 该方法向代理发送方法协商、认证数据和 CONNECT 请求，并校验代理返回的响应；在任一步骤失败时抛出异常。
     *
     * @param input  从代理读取响应的输入流
     * @param output 向代理发送请求的输出流
     * @param username SOCKS5 用户名（UTF-8，长度不超过 255 字节）
     * @param password SOCKS5 密码（UTF-8，长度不超过 255 字节）
     * @param destinationIp 目标主机的 IP 字符串（将通过 InetAddress 解析为字节形式）
     * @param destinationPort 目标主机端口（0-65535）
     *
     * @throws IllegalArgumentException 当方法协商或认证结果不符合预期，或用户名/密码长度超过允许范围时抛出
     * @throws IllegalStateException 当读取响应时遇到超时、意外 EOF 或收到不支持的地址类型（ATYP）时抛出
     */
    private fun performSocks5Handshake(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        username: String,
        password: String,
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
        val passBytes = password.toByteArray(Charsets.UTF_8)
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

    /**
     * 从输入流中读取数据直到填满指定的字节数组。
     *
     * 在读取到意外 EOF 或发生读取超时时会抛出 IllegalStateException。
     *
     * @param input 要读取的输入流。
     * @param target 要填充的目标字节数组。
     */
    private fun readFully(input: java.io.InputStream, target: ByteArray) {
        readFully(input, target, 0, target.size)
    }

    /**
     * 从输入流读取指定长度的字节并填充到目标数组的指定区间，直到读取到所需字节数。
     *
     * @param input 要读取的输入流。
     * @param target 用于写入读取字节的目标数组。
     * @param offset 在目标数组中开始写入的起始索引（inclusive）。
     * @param length 要读取并写入的字节数。
     *
     * @throws IllegalArgumentException 当 offset 或 length 越界时抛出。
     * @throws IllegalStateException 当在读取过程中发生 socket 超时或在到达所需字节数之前遇到流结束（EOF）时抛出。
     */
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
     * 清理超过 idleTimeoutMs 的空闲连接并从池中移除它们。
     *
     * 在写锁保护下遍历所有连接，选出未被使用且上次使用时间距今超过配置空闲超时的连接，从可用队列与全量跟踪映射中移除并更新总连接计数；在写锁外关闭对应的 socket 并在发生实际清理时记录调试日志。
     */
    private fun cleanupIdleConnections() {
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<PooledSocks5Connection>()

        // Keep selection and removal in one write lock window to avoid stale decisions.
        poolLock.write {
            allConnections.keys.forEach { conn ->
                if (!conn.inUse.get() && (now - conn.lastUsedAt.get() > config.idleTimeoutMs)) {
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
     * 启动一个守护清理线程，周期性调用 `cleanupIdleConnections` 清理超过空闲超时的连接直到池被关闭。
     *
     * 线程名为 "Socks5ConnectionPool-Cleanup"；在被中断或检测到池已关闭时退出；非中断异常将被记录并继续循环。 
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
     * 关闭连接池并释放所有资源。
     *
     * 中止后台清理线程，关闭并移除池中所有跟踪的连接、清空可用连接队列与目的地映射，并将总连接计数重置为 0。
     * 调用为幂等操作：仅第一次有效，后续调用无副作用。
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
     * 获取当前连接池的统计信息。
     *
     * @return 包含以下指标的 `ConnectionPoolStats`：
     * - `totalConnections`：池中被跟踪的连接总数（包括空闲与在用）。
     * - `availableConnections`：当前未被使用的连接数。
     * - `inUseConnections`：当前正在使用的连接数。
     * - `destinationCount`：按目的地（destinationIp:destinationPort）分组的数量。
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
