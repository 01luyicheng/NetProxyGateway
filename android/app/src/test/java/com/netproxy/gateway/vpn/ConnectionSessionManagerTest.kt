package com.netproxy.gateway.vpn

import com.netproxy.gateway.proxy.PooledSocks5Connection
import com.netproxy.gateway.proxy.Socks5ConnectionPool
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * ConnectionSessionManager 单元测试
 * 测试连接管理逻辑，无需反射或 Android Service 实例化
 */
class ConnectionSessionManagerTest {

    private lateinit var packetProcessor: VpnPacketProcessor
    private lateinit var manager: ConnectionSessionManager

    @Before
    fun setup() {
        packetProcessor = VpnPacketProcessor()
        manager = ConnectionSessionManager(packetProcessor)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createValidTcpPacket(
        srcIp: String = "10.0.0.2",
        dstIp: String = "192.168.1.1",
        srcPort: Int = 12345,
        dstPort: Int = 443
    ): ByteArray {
        val packet = ByteArray(60)
        packet[0] = 0x45
        packet[2] = 0x00
        packet[3] = 0x3C
        packet[4] = 0x00
        packet[5] = 0x00
        packet[6] = 0x40
        packet[7] = 0x00
        packet[8] = 0x40
        packet[9] = 0x06
        packet[10] = 0x00
        packet[11] = 0x00

        val srcParts = srcIp.split(".").map { it.toInt() }
        packet[12] = srcParts[0].toByte()
        packet[13] = srcParts[1].toByte()
        packet[14] = srcParts[2].toByte()
        packet[15] = srcParts[3].toByte()

        val dstParts = dstIp.split(".").map { it.toInt() }
        packet[16] = dstParts[0].toByte()
        packet[17] = dstParts[1].toByte()
        packet[18] = dstParts[2].toByte()
        packet[19] = dstParts[3].toByte()

        packet[20] = (srcPort shr 8).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = (dstPort shr 8).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        packet[24] = 0x00
        packet[25] = 0x00
        packet[26] = 0x00
        packet[27] = 0x01
        packet[28] = 0x00
        packet[29] = 0x00
        packet[30] = 0x00
        packet[31] = 0x00
        packet[32] = 0x50
        packet[33] = 0x18
        packet[34] = 0x20.toByte()
        packet[35] = 0x00
        packet[36] = 0x00
        packet[37] = 0x00
        packet[38] = 0x00
        packet[39] = 0x00

        for (i in 40 until 60) {
            packet[i] = (i % 256).toByte()
        }
        return packet
    }

    // ==================== 连接池借用测试 ====================

    @Test
    fun forwardViaSocks5_noPool_doesNothing() {
        val packet = createValidTcpPacket()
        manager.socks5ConnectionPool = null

        manager.forwardViaSocks5(packet, packet.size, "192.168.1.1") { true }

        assertEquals(0, manager.getActiveConnectionCount())
    }

    @Test
    fun forwardViaSocks5_borrowNewConnection_createsSession() {
        val packet = createValidTcpPacket()
        val mockSocket = mockk<Socket>(relaxed = true)
        every { mockSocket.isClosed } returns false
        every { mockSocket.isConnected } returns true
        every { mockSocket.isInputShutdown } returns false
        every { mockSocket.isOutputShutdown } returns false

        val mockConn = mockk<PooledSocks5Connection>(relaxed = true)
        every { mockConn.socket } returns mockSocket
        every { mockConn.isValid() } returns true

        val mockPool = mockk<Socks5ConnectionPool>(relaxed = true)
        every { mockPool.borrowConnection(any(), any(), any()) } returns mockConn

        manager.socks5ConnectionPool = mockPool

        manager.forwardViaSocks5(packet, packet.size, "192.168.1.1") { true }

        assertEquals(1, manager.getActiveConnectionCount())
        verify { mockPool.borrowConnection("192.168.1.1", 443, any()) }
    }

    @Test
    fun forwardViaSocks5_reuseValidConnection() {
        val packet = createValidTcpPacket()
        val mockSocket = mockk<Socket>(relaxed = true)
        every { mockSocket.isClosed } returns false
        every { mockSocket.isConnected } returns true
        every { mockSocket.isInputShutdown } returns false
        every { mockSocket.isOutputShutdown } returns false

        val mockConn = mockk<PooledSocks5Connection>(relaxed = true)
        every { mockConn.socket } returns mockSocket
        every { mockConn.isValid() } returns true

        val mockPool = mockk<Socks5ConnectionPool>(relaxed = true)
        every { mockPool.borrowConnection(any(), any(), any()) } returns mockConn

        manager.socks5ConnectionPool = mockPool

        // 第一次转发
        manager.forwardViaSocks5(packet, packet.size, "192.168.1.1") { true }
        assertEquals(1, manager.getActiveConnectionCount())

        // 第二次转发应复用连接
        manager.forwardViaSocks5(packet, packet.size, "192.168.1.1") { true }
        assertEquals(1, manager.getActiveConnectionCount())
        verify(exactly = 1) { mockPool.borrowConnection(any(), any(), any()) }
    }

    @Test
    fun forwardViaSocks5_invalidConnection_discardsAndReborrows() {
        val packet = createValidTcpPacket()
        val mockSocket = mockk<Socket>(relaxed = true)
        every { mockSocket.isClosed } returns false
        every { mockSocket.isConnected } returns false
        every { mockSocket.isInputShutdown } returns true
        every { mockSocket.isOutputShutdown } returns true

        val invalidConn = mockk<PooledSocks5Connection>(relaxed = true)
        every { invalidConn.socket } returns mockSocket
        every { invalidConn.isValid() } returns false

        val validConn = mockk<PooledSocks5Connection>(relaxed = true)
        every { validConn.socket } returns mockSocket
        every { validConn.isValid() } returns true

        val mockPool = mockk<Socks5ConnectionPool>(relaxed = true)
        every { mockPool.borrowConnection(any(), any(), any()) } returns validConn
        every { mockPool.discardConnection(invalidConn) } returns Unit

        manager.socks5ConnectionPool = mockPool

        // 先创建一个带有无效连接的会话
        val sessionKey = "10.0.0.2:12345-192.168.1.1:443"
        val activeConnections = ConcurrentHashMap<String, ConnectionSession>()
        activeConnections[sessionKey] = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = invalidConn,
            virtualSrcIp = "10.0.0.100"
        )

        manager = ConnectionSessionManager(packetProcessor, activeConnections)
        manager.socks5ConnectionPool = mockPool

        manager.forwardViaSocks5(packet, packet.size, "192.168.1.1") { true }

        verify(atLeast = 1) { mockPool.discardConnection(invalidConn) }
        verify(atLeast = 1) { mockPool.borrowConnection(any(), any(), any()) }
    }

    // ==================== 过期清理测试 ====================

    @Test
    fun cleanupStaleConnections_expiredSession_discardsConnection() {
        val mockSocket = mockk<Socket>(relaxed = true)
        every { mockSocket.isClosed } returns false
        every { mockSocket.isConnected } returns true
        every { mockSocket.isInputShutdown } returns false
        every { mockSocket.isOutputShutdown } returns false

        val pooledConnection = PooledSocks5Connection(
            socket = mockSocket,
            destinationIp = "192.168.1.1",
            destinationPort = 443
        )
        pooledConnection.markUsed()

        val mockPool = mockk<Socks5ConnectionPool>(relaxed = true)

        val sessionKey = "10.0.0.2:12345-192.168.1.1:443"
        val activeConnections = ConcurrentHashMap<String, ConnectionSession>()
        activeConnections[sessionKey] = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = pooledConnection,
            virtualSrcIp = "10.0.0.100",
            lastActivity = System.currentTimeMillis() - 60_000
        )

        manager = ConnectionSessionManager(
            packetProcessor = packetProcessor,
            activeConnections = activeConnections,
            connectionTimeoutMs = 30_000L
        )
        manager.socks5ConnectionPool = mockPool

        manager.cleanupStaleConnections()

        assertEquals(0, manager.getActiveConnectionCount())
        verify(exactly = 0) { mockPool.returnConnection(any()) }
        verify(atLeast = 1) { mockPool.discardConnection(pooledConnection) }
    }

    @Test
    fun cleanupStaleConnections_freshSession_keepsConnection() {
        val mockSocket = mockk<Socket>(relaxed = true)
        every { mockSocket.isClosed } returns false
        every { mockSocket.isConnected } returns true

        val pooledConnection = PooledSocks5Connection(
            socket = mockSocket,
            destinationIp = "192.168.1.1",
            destinationPort = 443
        )

        val sessionKey = "10.0.0.2:12345-192.168.1.1:443"
        val activeConnections = ConcurrentHashMap<String, ConnectionSession>()
        activeConnections[sessionKey] = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = pooledConnection,
            virtualSrcIp = "10.0.0.100",
            lastActivity = System.currentTimeMillis()
        )

        manager = ConnectionSessionManager(
            packetProcessor = packetProcessor,
            activeConnections = activeConnections,
            connectionTimeoutMs = 30_000L
        )

        manager.cleanupStaleConnections()

        assertEquals(1, manager.getActiveConnectionCount())
    }

    // ==================== TCP 回包测试 ====================

    @Test
    fun processTcpReturn_whenConstructFails_removesSession() {
        val mockInput = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        val mockSocket = mockk<Socket>(relaxed = true)
        every { mockSocket.isClosed } returns false
        every { mockSocket.isConnected } returns true
        every { mockSocket.isInputShutdown } returns false
        every { mockSocket.isOutputShutdown } returns false
        every { mockSocket.getInputStream() } returns mockInput

        val pooledConnection = PooledSocks5Connection(
            socket = mockSocket,
            destinationIp = "192.168.1.1",
            destinationPort = 443
        )

        val sessionKey = "10.0.0.2:12345-192.168.1.1:443"
        val activeConnections = ConcurrentHashMap<String, ConnectionSession>()
        val session = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = pooledConnection,
            virtualSrcIp = "bad.ip.value"
        )
        activeConnections[sessionKey] = session

        val mockPool = mockk<Socks5ConnectionPool>(relaxed = true)
        val mockOutput = mockk<FileOutputStream>(relaxed = true)

        manager = ConnectionSessionManager(
            packetProcessor = packetProcessor,
            activeConnections = activeConnections
        )
        manager.socks5ConnectionPool = mockPool
        manager.vpnOutputStream = mockOutput

        val result = manager.processTcpReturn(session, sessionKey)

        assertFalse(result)
        assertFalse(activeConnections.containsKey(sessionKey))
        verify(exactly = 0) { mockPool.returnConnection(any()) }
        verify(exactly = 0) { mockOutput.write(any<ByteArray>(), any(), any()) }
    }

    @Test
    fun processTcpReturn_sessionNotActive_returnsFalse() {
        val session = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = null,
            virtualSrcIp = "10.0.0.100"
        )

        val result = manager.processTcpReturn(session, "non-existent-key")
        assertFalse(result)
    }

    @Test
    fun processTcpReturn_invalidConnection_removesSession() {
        val mockSocket = mockk<Socket>(relaxed = true)
        every { mockSocket.isClosed } returns true
        every { mockSocket.isConnected } returns false

        val pooledConnection = PooledSocks5Connection(
            socket = mockSocket,
            destinationIp = "192.168.1.1",
            destinationPort = 443
        )

        val sessionKey = "10.0.0.2:12345-192.168.1.1:443"
        val activeConnections = ConcurrentHashMap<String, ConnectionSession>()
        val session = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = pooledConnection,
            virtualSrcIp = "10.0.0.100"
        )
        activeConnections[sessionKey] = session

        val mockPool = mockk<Socks5ConnectionPool>(relaxed = true)

        manager = ConnectionSessionManager(
            packetProcessor = packetProcessor,
            activeConnections = activeConnections
        )
        manager.socks5ConnectionPool = mockPool

        val result = manager.processTcpReturn(session, sessionKey)

        assertFalse(result)
        assertFalse(activeConnections.containsKey(sessionKey))
    }

    // ==================== 注入包测试 ====================

    @Test
    fun injectPacket_withNullStream_returnsFalse() {
        manager.vpnOutputStream = null
        val packet = ByteArray(40)
        val result = manager.injectPacket(packet, 40)
        assertFalse(result)
    }

    @Test
    fun injectPacket_withValidStream_returnsTrue() {
        val mockOutput = mockk<FileOutputStream>(relaxed = true)
        manager.vpnOutputStream = mockOutput

        val packet = ByteArray(40)
        val result = manager.injectPacket(packet, 40)
        assertTrue(result)
        verify { mockOutput.write(packet, 0, 40) }
    }

    // ==================== Session 移除测试 ====================

    @Test
    fun removeSessionAndCloseConnection_matchingSession_removesAndReturnsTrue() {
        val mockSocket = mockk<Socket>(relaxed = true)
        val pooledConnection = PooledSocks5Connection(
            socket = mockSocket,
            destinationIp = "192.168.1.1",
            destinationPort = 443
        )

        val sessionKey = "10.0.0.2:12345-192.168.1.1:443"
        val activeConnections = ConcurrentHashMap<String, ConnectionSession>()
        val session = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = pooledConnection,
            virtualSrcIp = "10.0.0.100"
        )
        activeConnections[sessionKey] = session

        val mockPool = mockk<Socks5ConnectionPool>(relaxed = true)

        manager = ConnectionSessionManager(
            packetProcessor = packetProcessor,
            activeConnections = activeConnections
        )
        manager.socks5ConnectionPool = mockPool

        val result = manager.removeSessionAndCloseConnection(sessionKey, session)

        assertTrue(result)
        assertFalse(activeConnections.containsKey(sessionKey))
    }

    @Test
    fun removeSessionAndCloseConnection_nonMatchingSession_returnsFalse() {
        val activeConnections = ConcurrentHashMap<String, ConnectionSession>()
        val session1 = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = null,
            virtualSrcIp = "10.0.0.100"
        )
        val session2 = ConnectionSession(
            srcIp = "10.0.0.3",
            srcPort = 12346,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = null,
            virtualSrcIp = "10.0.0.101"
        )
        activeConnections["key"] = session1

        manager = ConnectionSessionManager(
            packetProcessor = packetProcessor,
            activeConnections = activeConnections
        )

        val result = manager.removeSessionAndCloseConnection("key", session2)

        assertFalse(result)
        assertTrue(activeConnections.containsKey("key"))
    }

    // ==================== 清空连接测试 ====================

    @Test
    fun clearAllConnections_removesAllSessions() {
        val mockSocket = mockk<Socket>(relaxed = true)
        val conn1 = PooledSocks5Connection(
            socket = mockSocket,
            destinationIp = "192.168.1.1",
            destinationPort = 443
        )
        val conn2 = PooledSocks5Connection(
            socket = mockSocket,
            destinationIp = "192.168.1.2",
            destinationPort = 80
        )

        val activeConnections = ConcurrentHashMap<String, ConnectionSession>()
        activeConnections["key1"] = ConnectionSession(
            srcIp = "10.0.0.2", srcPort = 12345,
            dstIp = "192.168.1.1", dstPort = 443,
            protocol = 6, pooledConnection = conn1, virtualSrcIp = "10.0.0.100"
        )
        activeConnections["key2"] = ConnectionSession(
            srcIp = "10.0.0.2", srcPort = 12346,
            dstIp = "192.168.1.2", dstPort = 80,
            protocol = 6, pooledConnection = conn2, virtualSrcIp = "10.0.0.101"
        )

        val mockPool = mockk<Socks5ConnectionPool>(relaxed = true)

        manager = ConnectionSessionManager(
            packetProcessor = packetProcessor,
            activeConnections = activeConnections
        )
        manager.socks5ConnectionPool = mockPool

        manager.clearAllConnections()

        assertEquals(0, manager.getActiveConnectionCount())
        verify(atLeast = 1) { mockPool.discardConnection(conn1) }
        verify(atLeast = 1) { mockPool.discardConnection(conn2) }
    }
}
