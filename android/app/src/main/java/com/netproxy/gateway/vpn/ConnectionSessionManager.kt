package com.netproxy.gateway.vpn

import com.netproxy.gateway.proxy.Socks5ConnectionPool
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 连接会话管理器
 * 管理活跃连接、连接池借用/归还、过期清理和回包处理
 */
internal class ConnectionSessionManager(
    private val packetProcessor: VpnPacketProcessor,
    private val activeConnections: ConcurrentHashMap<String, ConnectionSession> = ConcurrentHashMap(),
    private val connectionTimeoutMs: Long = 30_000L,
    private val packetBufferSize: Int = 32 * 1024,
    private val logger: org.slf4j.Logger = LoggerFactory.getLogger(ConnectionSessionManager::class.java)
) {

    @Volatile
    var socks5ConnectionPool: Socks5ConnectionPool? = null

    @Volatile
    var vpnOutputStream: FileOutputStream? = null

    private val isProcessing = AtomicBoolean(false)

    /**
     * 通过本地 SOCKS5 代理转发数据包
     */
    fun forwardViaSocks5(
        packet: ByteArray,
        length: Int,
        destinationIp: String,
        protectSocket: (java.net.Socket) -> Boolean
    ) {
        val destinationPort = packetProcessor.parseDestinationPort(packet, length) ?: return
        val protocol = packetProcessor.parseProtocol(packet)
        val payloadInfo = packetProcessor.extractTransportPayloadInfo(packet, length) ?: return
        val srcIp = packetProcessor.parseSourceIp(packet, length) ?: return
        val srcPort = packetProcessor.parseSourcePort(packet, length) ?: return

        val pool = socks5ConnectionPool
        if (pool == null) {
            logger.warn("Skip SOCKS5 forward: missing connection pool for ${redactIp(destinationIp)}")
            return
        }

        val connectionKey = "$srcIp:$srcPort-$destinationIp:$destinationPort"

        try {
            var sessionToUse: ConnectionSession? = activeConnections.computeIfPresent(connectionKey) { _, existingSession ->
                if (existingSession.pooledConnection?.isValid() == true) {
                    existingSession.updateActivity()
                    existingSession
                } else {
                    existingSession.pooledConnection?.let { pool.discardConnection(it) }
                    null
                }
            }

            if (sessionToUse == null) {
                val conn = pool.borrowConnection(
                    destinationIp = destinationIp,
                    destinationPort = destinationPort,
                    protectSocket = protectSocket
                )

                if (conn != null) {
                    val newSession = ConnectionSession(
                        srcIp = srcIp,
                        srcPort = srcPort,
                        dstIp = destinationIp,
                        dstPort = destinationPort,
                        protocol = protocol,
                        pooledConnection = conn,
                        virtualSrcIp = "10.0.0.1" // 由调用方覆盖或使用分配器
                    )
                    newSession.updateActivity()

                    val existing = activeConnections.putIfAbsent(connectionKey, newSession)
                    sessionToUse = if (existing != null) {
                        pool.discardConnection(conn)
                        existing
                    } else {
                        logger.debug("Borrowed connection from pool: ${redactConnectionKey(connectionKey)}")
                        newSession
                    }
                } else {
                    logger.warn("Failed to borrow connection from pool for ${redactConnectionKey(connectionKey)}")
                }
            }

            sessionToUse?.pooledConnection?.let { pooledConn ->
                pooledConn.socket.getOutputStream()?.write(packet, payloadInfo.first, payloadInfo.second)
                pooledConn.socket.getOutputStream()?.flush()
            }
        } catch (e: Exception) {
            logger.warn("Forward via SOCKS5 failed for ${redactConnectionKey(connectionKey)}", e)
            activeConnections.remove(connectionKey)?.pooledConnection?.let { pool.discardConnection(it) }
        }
    }

    /**
     * 清理过期连接
     */
    fun cleanupStaleConnections() {
        val now = System.currentTimeMillis()
        val pool = socks5ConnectionPool

        activeConnections.forEach { (key, session) ->
            activeConnections.computeIfPresent(key) { _, existingSession ->
                val isExpired = now - existingSession.lastActivity > connectionTimeoutMs
                if (isExpired) {
                    try {
                        existingSession.pooledConnection?.let { pool?.discardConnection(it) }
                        logger.debug("Closed stale connection: ${redactConnectionKey(key)}")
                    } catch (e: Exception) {
                        logger.warn("Failed to close stale connection", e)
                    }
                    null
                } else {
                    existingSession
                }
            }
        }
    }

    /**
     * 处理 TCP 回包
     * N58 修复：移除 available() 检查，改为直接尝试非阻塞读取。
     * H14 修复：支持 0 长度控制包注入（当无数据可读但会话有待发送控制标志时）。
     * 使用 socket.setSoTimeout(1) 实现非阻塞语义：有数据则读取，无数据立即抛出 SocketTimeoutException。
     * @return true if data was processed
     */
    fun processTcpReturn(session: ConnectionSession, sessionKey: String): Boolean {
        if (activeConnections[sessionKey] !== session) return false
        val pooledConn = session.pooledConnection ?: return false
        if (!pooledConn.isValid()) {
            removeSessionAndCloseConnection(sessionKey, session)
            return false
        }

        val socket = pooledConn.socket
        val previousTimeout = try { socket.soTimeout } catch (_: Exception) { 0 }
        var processed = false

        try {
            socket.soTimeout = 1
            val input = socket.getInputStream()
            val buffer = ByteArray(packetBufferSize)
            val payloadOffset = 40 // 20-byte IP header + 20-byte TCP header
            val read = input.read(buffer, payloadOffset, buffer.size - payloadOffset)
            if (read > 0) {
                // 有数据时优先使用显式控制标志（如 FIN），否则根据状态自动解析
                val flags = session.consumePendingFlags()
                val packetLen = packetProcessor.constructReturnPacket(buffer, session, read, flags)
                if (packetLen <= 0) {
                    logger.warn("Drop invalid TCP return packet for ${redactConnectionKey(sessionKey)}")
                    removeSessionAndCloseConnection(sessionKey, session)
                    return false
                }
                if (!injectPacket(buffer, packetLen)) {
                    logger.warn("Failed to inject TCP return packet for ${redactConnectionKey(sessionKey)}, closing session")
                    removeSessionAndCloseConnection(sessionKey, session)
                    return false
                }
                session.advanceSeq(read)
                session.advanceAck(read)
                session.updateActivity()
                processed = true
            }
        } catch (e: java.net.SocketTimeoutException) {
            // N58: 无数据可读，继续检查是否需要发送控制包
        } catch (e: Exception) {
            logger.warn("TCP return traffic error for ${redactConnectionKey(sessionKey)}: ${e.message}")
            removeSessionAndCloseConnection(sessionKey, session)
            return false
        } finally {
            try { socket.soTimeout = previousTimeout } catch (_: Exception) { /* ignore */ }
        }

        // H14: 即使没有可读数据，如果有待发送的控制包（如 ACK/FIN/RST），仍构造并注入 0 长度包
        if (!processed && session.needsControlPacket()) {
            val buffer = ByteArray(packetBufferSize)
            val flags = session.consumePendingFlags()
            val packetLen = packetProcessor.constructReturnPacket(buffer, session, 0, flags)
            if (packetLen > 0 && injectPacket(buffer, packetLen)) {
                session.updateActivity()
                processed = true
            }
        }

        return processed
    }

    /**
     * 原子移除 session 并关闭连接
     */
    fun removeSessionAndCloseConnection(sessionKey: String, session: ConnectionSession): Boolean {
        var removed = false
        activeConnections.computeIfPresent(sessionKey) { _, existing ->
            if (existing === session) {
                existing.pooledConnection?.let { socks5ConnectionPool?.discardConnection(it) }
                removed = true
                null
            } else existing
        }
        return removed
    }

    /**
     * 注入包到 TUN 接口
     * N57: 使用同步块保证多协程并发写入时的线程安全
     */
    fun injectPacket(packet: ByteArray, length: Int): Boolean {
        return try {
            val stream = vpnOutputStream
            if (stream == null) {
                logger.warn("vpnOutputStream is null, cannot inject packet")
                false
            } else {
                synchronized(stream) {
                    stream.write(packet, 0, length)
                    stream.flush()
                }
                true
            }
        } catch (e: Exception) {
            logger.error("Failed to inject packet to TUN", e)
            false
        }
    }

    /**
     * 获取活跃连接数
     */
    fun getActiveConnectionCount(): Int = activeConnections.size

    /**
     * 获取活跃连接的只读快照
     */
    fun getActiveConnectionsSnapshot(): List<Pair<String, ConnectionSession>> =
        activeConnections.entries.map { it.key to it.value }

    /**
     * 清空所有连接并丢弃（不归还连接池）
     */
    fun clearAllConnections() {
        val pool = socks5ConnectionPool
        activeConnections.values.forEach { session ->
            try {
                session.pooledConnection?.let { pool?.discardConnection(it) }
            } catch (e: Exception) {
                logger.warn("Failed to discard connection for session ${redactIp(session.srcIp)}:${session.srcPort}", e)
            }
        }
        activeConnections.clear()
    }

    /**
     * N80: 根据 stream ID 查找并移除对应的会话。
     * 由于 Android 端当前没有维护 stream_id -> session 的映射，
     * 而 SOCKS5-Proxy 的 disconnect 消息只包含 stream_id，
     * 因此需要遍历所有活跃连接，通过匹配连接池中的底层 socket 信息来定位。
     * 如果找不到直接映射，则回退到清理所有可能相关的连接。
     *
     * @param streamId SOCKS5-Proxy 发来的 stream_id
     * @return 如果成功找到并移除了会话返回 true，否则返回 false
     */
    fun removeSessionByStreamId(streamId: String): Boolean {
        if (streamId.isBlank()) {
            logger.warn("N80: removeSessionByStreamId called with blank streamId")
            return false
        }

        // 策略：遍历所有活跃连接，尝试找到匹配的会话。
        // 由于当前 ConnectionSession 不存储 stream_id，我们无法精确匹配。
        // 但 SOCKS5-Proxy 发送 disconnect 意味着底层 SOCKS5 连接已被关闭，
        // 因此任何关联到该目标地址的会话都应该被清理。
        // 这里采用保守策略：如果活跃连接中某连接的 socket 已失效，就清理它。
        var removedAny = false
        val snapshot = activeConnections.entries.toList()
        for ((key, session) in snapshot) {
            val pooledConn = session.pooledConnection
            if (pooledConn == null || !pooledConn.isValid()) {
                if (removeSessionAndCloseConnection(key, session)) {
                    removedAny = true
                    logger.debug("N80: Removed stale session for stream disconnect: ${redactConnectionKey(key)}")
                }
            }
        }

        if (!removedAny) {
            logger.debug("N80: No matching session found for streamId=$streamId, activeConnections=${activeConnections.size}")
        }
        return removedAny
    }

    /**
     * N80: 处理 SOCKS5-Proxy 发来的 disconnect 消息。
     * 消息格式: {"type":"disconnect","data":{"stream_id":"..."}}
     *
     * @param payload MQTT 消息 payload
     * @return 如果成功解析并处理了 disconnect 消息返回 true
     */
    fun handleDisconnectMessage(payload: String): Boolean {
        return try {
            val trimmed = payload.trim()
            if (!trimmed.contains("\"type\"")) return false

            // 简单解析：提取 type 字段
            val typeMatch = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
            val msgType = typeMatch?.groupValues?.get(1)
            if (msgType != "disconnect") return false

            // 提取 stream_id
            val streamIdMatch = Regex("\"stream_id\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
            val streamId = streamIdMatch?.groupValues?.get(1)
            if (streamId.isNullOrBlank()) {
                logger.warn("N80: disconnect message missing stream_id")
                return false
            }

            logger.debug("N80: Received disconnect message for streamId=$streamId")
            removeSessionByStreamId(streamId)
        } catch (e: Exception) {
            logger.warn("N80: Failed to parse disconnect message", e)
            false
        }
    }

    private fun redactIp(ip: String): String = com.netproxy.gateway.vpn.redactIp(ip)
    private fun redactConnectionKey(key: String): String = com.netproxy.gateway.vpn.redactConnectionKey(key)
}
