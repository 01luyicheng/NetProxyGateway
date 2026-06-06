package com.netproxy.gateway.vpn

import com.netproxy.gateway.proxy.PooledSocks5Connection
import com.netproxy.gateway.proxy.Socks5ConnectionPool
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

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
     * @return true if data was processed
     */
    fun processTcpReturn(session: ConnectionSession, sessionKey: String): Boolean {
        if (activeConnections[sessionKey] !== session) return false
        val pooledConn = session.pooledConnection ?: return false
        if (!pooledConn.isValid()) {
            removeSessionAndCloseConnection(sessionKey, session)
            return false
        }

        try {
            val input = pooledConn.socket.getInputStream()
            val available = input.available()
            if (available > 0) {
                val buffer = ByteArray(packetBufferSize)
                val payloadOffset = 40 // 20-byte IP header + 20-byte TCP header
                val read = input.read(buffer, payloadOffset, minOf(available, buffer.size - payloadOffset))
                if (read > 0) {
                    val packetLen = packetProcessor.constructReturnPacket(buffer, session, read)
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
                    session.updateActivity()
                    return true
                }
            }
            return false
        } catch (e: Exception) {
            logger.warn("TCP return traffic error for ${redactConnectionKey(sessionKey)}: ${e.message}")
            removeSessionAndCloseConnection(sessionKey, session)
            return false
        }
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
     */
    fun injectPacket(packet: ByteArray, length: Int): Boolean {
        return try {
            val stream = vpnOutputStream
            if (stream == null) {
                logger.warn("vpnOutputStream is null, cannot inject packet")
                false
            } else {
                stream.write(packet, 0, length)
                stream.flush()
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

    private fun redactIp(ip: String): String = com.netproxy.gateway.vpn.redactIp(ip)
    private fun redactConnectionKey(key: String): String = com.netproxy.gateway.vpn.redactConnectionKey(key)
}
