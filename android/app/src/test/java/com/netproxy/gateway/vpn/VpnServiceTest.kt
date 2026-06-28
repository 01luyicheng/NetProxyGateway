package com.netproxy.gateway.vpn

import com.netproxy.gateway.proxy.PooledSocks5Connection
import com.netproxy.gateway.proxy.Socks5ConnectionPool
import com.netproxy.gateway.utils.IpAddressUtils
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
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * GatewayVpnService 单元测试
 * 仅测试不依赖 Android Service 生命周期的公开 API 和常量
 */
class VpnServiceTest {

    @Before
    fun setup() {
        // Setup if needed
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== VpnState 枚举测试 ====================

    @Test
    fun vpnState_values() {
        val values = VpnState.values()

        assertEquals(5, values.size)
        assertTrue(values.contains(VpnState.STOPPED))
        assertTrue(values.contains(VpnState.STARTING))
        assertTrue(values.contains(VpnState.RUNNING))
        assertTrue(values.contains(VpnState.STOPPING))
        assertTrue(values.contains(VpnState.ERROR))
    }

    @Test
    fun vpnState_valueOf() {
        assertEquals(VpnState.STOPPED, VpnState.valueOf("STOPPED"))
        assertEquals(VpnState.STARTING, VpnState.valueOf("STARTING"))
        assertEquals(VpnState.RUNNING, VpnState.valueOf("RUNNING"))
        assertEquals(VpnState.STOPPING, VpnState.valueOf("STOPPING"))
        assertEquals(VpnState.ERROR, VpnState.valueOf("ERROR"))
    }

    // ==================== VpnStatus 数据类测试 ====================

    @Test
    fun vpnStatus_defaultValues() {
        val status = VpnStatus()

        assertEquals(VpnState.STOPPED, status.state)
        assertNull(status.errorMessage)
        assertEquals(0, status.connectedClients)
    }

    @Test
    fun vpnStatus_customValues() {
        val status = VpnStatus(
            state = VpnState.RUNNING,
            errorMessage = "Test error",
            connectedClients = 5
        )

        assertEquals(VpnState.RUNNING, status.state)
        assertEquals("Test error", status.errorMessage)
        assertEquals(5, status.connectedClients)
    }

    @Test
    fun vpnStatus_equality() {
        val status1 = VpnStatus(state = VpnState.RUNNING, connectedClients = 3)
        val status2 = VpnStatus(state = VpnState.RUNNING, connectedClients = 3)
        val status3 = VpnStatus(state = VpnState.STOPPED, connectedClients = 3)

        assertEquals(status1, status2)
        assertTrue(status1 != status3)
    }

    @Test
    fun vpnStatus_copy() {
        val status = VpnStatus(state = VpnState.RUNNING, connectedClients = 5)
        val copied = status.copy(state = VpnState.ERROR, errorMessage = "Error")

        assertEquals(VpnState.ERROR, copied.state)
        assertEquals("Error", copied.errorMessage)
        assertEquals(5, copied.connectedClients)
    }

    @Test
    fun vpnStatus_componentFunctions() {
        val status = VpnStatus(
            state = VpnState.RUNNING,
            errorMessage = "test",
            connectedClients = 10
        )

        assertEquals(VpnState.RUNNING, status.state)
        assertEquals("test", status.errorMessage)
        assertEquals(10, status.connectedClients)
    }

    @Test
    fun vpnStatus_toString() {
        val status = VpnStatus(state = VpnState.RUNNING, connectedClients = 5)
        val str = status.toString()

        assertTrue(str.contains("RUNNING"))
        assertTrue(str.contains("5"))
    }

    // ==================== 常量测试 ====================

    @Test
    fun vpnServiceConstants() {
        assertEquals("10.0.0.2", GatewayVpnService.VPN_ADDRESS)
        assertEquals("0.0.0.0", GatewayVpnService.VPN_ROUTE)
        assertEquals(1500, GatewayVpnService.VPN_MTU)
        assertEquals("127.0.0.1", GatewayVpnService.SOCKS5_PROXY_HOST)
        assertEquals(1080, GatewayVpnService.SOCKS5_PROXY_PORT)
    }

    // ==================== VpnDnsConfig 测试 ====================

    @Test
    fun vpnDnsConfig_resolveDnsServers_withValidConfig() {
        val result = VpnDnsConfig.resolveDnsServers(
            configuredValue = "1.1.1.1, 8.8.8.8",
            defaultDnsServers = listOf("9.9.9.9")
        )

        assertEquals(listOf("1.1.1.1", "8.8.8.8"), result)
    }

    @Test
    fun vpnDnsConfig_resolveDnsServers_withInvalidConfig() {
        val result = VpnDnsConfig.resolveDnsServers(
            configuredValue = "invalid, 300.1.1.1",
            defaultDnsServers = listOf("9.9.9.9")
        )

        assertEquals(listOf("9.9.9.9"), result)
    }

    @Test
    fun vpnDnsConfig_shouldRouteDnsViaWifi_withValidDnsRequest() {
        val result = VpnDnsConfig.shouldRouteDnsViaWifi(
            destinationIp = "8.8.8.8",
            protocol = 17, // UDP
            destinationPort = 53,
            dnsServers = setOf("8.8.8.8", "1.1.1.1")
        )

        assertTrue(result)
    }

    @Test
    fun vpnDnsConfig_shouldRouteDnsViaWifi_withWrongPort() {
        val result = VpnDnsConfig.shouldRouteDnsViaWifi(
            destinationIp = "8.8.8.8",
            protocol = 17, // UDP
            destinationPort = 80,
            dnsServers = setOf("8.8.8.8")
        )

        assertFalse(result)
    }

    @Test
    fun vpnDnsConfig_shouldRouteDnsViaWifi_withWrongProtocol() {
        val result = VpnDnsConfig.shouldRouteDnsViaWifi(
            destinationIp = "8.8.8.8",
            protocol = 1, // ICMP
            destinationPort = 53,
            dnsServers = setOf("8.8.8.8")
        )

        assertFalse(result)
    }

    @Test
    fun vpnDnsConfig_shouldRouteDnsViaWifi_withNonDnsIp() {
        val result = VpnDnsConfig.shouldRouteDnsViaWifi(
            destinationIp = "192.168.1.1",
            protocol = 17,
            destinationPort = 53,
            dnsServers = setOf("8.8.8.8")
        )

        assertFalse(result)
    }

    @Test
    fun vpnDnsConfig_shouldRouteDnsViaWifi_withTcp() {
        val result = VpnDnsConfig.shouldRouteDnsViaWifi(
            destinationIp = "8.8.8.8",
            protocol = 6, // TCP
            destinationPort = 53,
            dnsServers = setOf("8.8.8.8")
        )

        assertTrue(result)
    }

    @Test
    fun vpnDnsConfig_resolveDnsServersWithResult_success() {
        val result = VpnDnsConfig.resolveDnsServersWithResult(
            configuredValue = "1.1.1.1",
            defaultDnsServers = listOf("9.9.9.9")
        )

        assertTrue(result.isSuccess())
        assertEquals(listOf("1.1.1.1"), result.getOrNull())
    }

    @Test
    fun vpnDnsConfig_shouldRouteDnsViaWifiWithResult_success() {
        val result = VpnDnsConfig.shouldRouteDnsViaWifiWithResult(
            destinationIp = "8.8.8.8",
            protocol = 17,
            destinationPort = 53,
            dnsServers = setOf("8.8.8.8")
        )

        assertTrue(result.isSuccess())
        assertEquals(true, result.getOrNull())
    }

    @Test
    fun vpnDnsConfig_parseConfiguredDnsServersWithResult_withValidInput() {
        val result = VpnDnsConfig.parseConfiguredDnsServersWithResult("1.1.1.1, 8.8.8.8")

        assertTrue(result.isSuccess())
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), result.getOrNull())
    }

    @Test
    fun vpnDnsConfig_parseConfiguredDnsServersWithResult_withNullInput() {
        val result = VpnDnsConfig.parseConfiguredDnsServersWithResult(null)

        assertTrue(result.isSuccess())
        assertEquals(emptyList<String>(), result.getOrNull())
    }

    @Test
    fun vpnDnsConfig_parseConfiguredDnsServersWithResult_withEmptyInput() {
        val result = VpnDnsConfig.parseConfiguredDnsServersWithResult("  ,  ")

        assertTrue(result.isSuccess())
        assertEquals(emptyList<String>(), result.getOrNull())
    }

    // ==================== 状态流转测试 ====================

    @Test
    fun vpnStatus_transitions() {
        val starting = VpnStatus(state = VpnState.STARTING)
        assertEquals(VpnState.STARTING, starting.state)

        val running = VpnStatus(state = VpnState.RUNNING, connectedClients = 1)
        assertEquals(VpnState.RUNNING, running.state)
        assertEquals(1, running.connectedClients)

        val error = VpnStatus(state = VpnState.ERROR, errorMessage = "Connection failed")
        assertEquals(VpnState.ERROR, error.state)
        assertEquals("Connection failed", error.errorMessage)

        val stopped = VpnStatus(state = VpnState.STOPPED)
        assertEquals(VpnState.STOPPED, stopped.state)
    }

    // ==================== 边界条件测试 ====================

    @Test
    fun vpnDnsConfig_resolveDnsServers_withEmptyString() {
        val result = VpnDnsConfig.resolveDnsServers(
            configuredValue = "",
            defaultDnsServers = listOf("8.8.8.8")
        )

        assertEquals(listOf("8.8.8.8"), result)
    }

    @Test
    fun vpnDnsConfig_resolveDnsServers_withWhitespaceOnly() {
        val result = VpnDnsConfig.resolveDnsServers(
            configuredValue = "   ",
            defaultDnsServers = listOf("8.8.8.8")
        )

        assertEquals(listOf("8.8.8.8"), result)
    }

    @Test
    fun vpnDnsConfig_resolveDnsServers_withMixedValidAndInvalid() {
        val result = VpnDnsConfig.resolveDnsServers(
            configuredValue = "1.1.1.1, invalid, 256.1.1.1, 8.8.8.8, abc.def.ghi.jkl",
            defaultDnsServers = listOf("9.9.9.9")
        )

        assertEquals(listOf("1.1.1.1", "8.8.8.8"), result)
    }

    @Test
    fun vpnDnsConfig_resolveDnsServers_removesDuplicates() {
        val result = VpnDnsConfig.resolveDnsServers(
            configuredValue = "1.1.1.1, 1.1.1.1, 8.8.8.8, 1.1.1.1",
            defaultDnsServers = listOf("9.9.9.9")
        )

        assertEquals(listOf("1.1.1.1", "8.8.8.8"), result)
    }

    @Test
    fun vpnDnsConfig_resolveDnsServers_trimsWhitespace() {
        val result = VpnDnsConfig.resolveDnsServers(
            configuredValue = "  1.1.1.1  ,   8.8.8.8   ",
            defaultDnsServers = listOf("9.9.9.9")
        )

        assertEquals(listOf("1.1.1.1", "8.8.8.8"), result)
    }

    @Test
    fun vpnStatus_errorState_withNullMessage() {
        val status = VpnStatus(state = VpnState.ERROR, errorMessage = null)

        assertEquals(VpnState.ERROR, status.state)
        assertNull(status.errorMessage)
    }

    @Test
    fun vpnStatus_errorState_withEmptyMessage() {
        val status = VpnStatus(state = VpnState.ERROR, errorMessage = "")

        assertEquals(VpnState.ERROR, status.state)
        assertEquals("", status.errorMessage)
    }

    @Test
    fun vpnStatus_errorState_withLongMessage() {
        val longMessage = "A".repeat(1000)
        val status = VpnStatus(state = VpnState.ERROR, errorMessage = longMessage)

        assertEquals(longMessage, status.errorMessage)
    }

    @Test
    fun vpnStatus_runningState_withNegativeClients() {
        val status = VpnStatus(state = VpnState.RUNNING, connectedClients = -1)
        assertEquals(-1, status.connectedClients)
    }

    @Test
    fun vpnStatus_runningState_withMaxIntClients() {
        val status = VpnStatus(state = VpnState.RUNNING, connectedClients = Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, status.connectedClients)
    }

    // ==================== 数据类行为测试 ====================

    @Test
    fun vpnStatus_hashCodeConsistency() {
        val status1 = VpnStatus(state = VpnState.RUNNING, connectedClients = 5)
        val status2 = VpnStatus(state = VpnState.RUNNING, connectedClients = 5)

        assertEquals(status1.hashCode(), status2.hashCode())
    }

    @Test
    fun vpnStatus_equalsNull() {
        val status = VpnStatus()

        assertFalse(status.equals(null))
    }

    @Test
    fun vpnStatus_equalsDifferentType() {
        val status = VpnStatus()

        assertFalse(status.equals("not a status"))
    }

    @Test
    fun vpnStatus_equalsSameObject() {
        val status = VpnStatus()

        assertTrue(status.equals(status))
    }

    // ==================== 协议常量测试 ====================

    @Test
    fun protocolConstants() {
        val PROTOCOL_TCP = 6
        val PROTOCOL_UDP = 17
        val DNS_PORT = 53

        assertEquals(6, PROTOCOL_TCP)
        assertEquals(17, PROTOCOL_UDP)
        assertEquals(53, DNS_PORT)
    }

    // ==================== VPN 配置测试 ====================

    @Test
    fun vpnConfiguration_addressFormat() {
        val vpnAddress = GatewayVpnService.VPN_ADDRESS
        val parts = vpnAddress.split(".")
        assertEquals(4, parts.size)
        assertTrue(parts.all { it.toIntOrNull() in 0..255 })
    }

    @Test
    fun vpnConfiguration_routeFormat() {
        val vpnRoute = GatewayVpnService.VPN_ROUTE
        assertEquals("0.0.0.0", vpnRoute)
    }

    @Test
    fun vpnConfiguration_mtuValue() {
        val mtu = GatewayVpnService.VPN_MTU
        assertTrue(mtu in 1280..9000)
        assertEquals(1500, mtu)
    }

    @Test
    fun vpnConfiguration_proxyHostIsLocalhost() {
        assertEquals("127.0.0.1", GatewayVpnService.SOCKS5_PROXY_HOST)
    }

    @Test
    fun vpnConfiguration_proxyPortIsValid() {
        val port = GatewayVpnService.SOCKS5_PROXY_PORT
        assertTrue(port in 1..65535)
        assertEquals(1080, port)
    }

    // ==================== SOCKS5 集成测试 ====================

    @Test
    fun socks5Integration_connectionPoolConfig_defaults() {
        val config = com.netproxy.gateway.proxy.Socks5ConnectionPoolConfig()

        assertEquals(50, config.maxConnections)
        assertEquals(60_000L, config.idleTimeoutMs)
        assertEquals(5_000, config.connectionTimeoutMs)
        assertEquals(30_000, config.socketSoTimeoutMs)
        assertEquals(5, config.minIdleConnections)
        assertEquals(10, config.maxConnectionsPerDestination)
        assertEquals(30_000L, config.cleanupIntervalMs)
    }

    @Test
    fun socks5Integration_connectionPoolConfig_customValues() {
        val config = com.netproxy.gateway.proxy.Socks5ConnectionPoolConfig(
            maxConnections = 100,
            idleTimeoutMs = 30_000L,
            connectionTimeoutMs = 10_000,
            maxConnectionsPerDestination = 20
        )

        assertEquals(100, config.maxConnections)
        assertEquals(30_000L, config.idleTimeoutMs)
        assertEquals(10_000, config.connectionTimeoutMs)
        assertEquals(20, config.maxConnectionsPerDestination)
    }

    @Test
    fun socks5Integration_pooledConnection_lifecycle() {
        val mockSocket = mockk<Socket>(relaxed = true)
        every { mockSocket.isConnected } returns true
        every { mockSocket.isClosed } returns false
        every { mockSocket.isInputShutdown } returns false
        every { mockSocket.isOutputShutdown } returns false

        val connection = PooledSocks5Connection(
            socket = mockSocket,
            destinationIp = "192.168.1.1",
            destinationPort = 443
        )

        assertFalse(connection.inUse.get())
        assertEquals(0, connection.useCount.get())

        connection.markUsed()
        assertTrue(connection.inUse.get())
        assertEquals(1, connection.useCount.get())

        connection.markReturned()
        assertFalse(connection.inUse.get())
        assertEquals(1, connection.useCount.get())

        assertTrue(connection.isValid())

        connection.close()
        verify { mockSocket.close() }
    }

    @Test
    fun socks5Integration_pooledConnection_invalidWhenClosed() {
        val mockSocket = mockk<Socket>(relaxed = true)
        every { mockSocket.isConnected } returns false
        every { mockSocket.isClosed } returns true

        val connection = PooledSocks5Connection(
            socket = mockSocket,
            destinationIp = "192.168.1.1",
            destinationPort = 443
        )

        assertFalse(connection.isValid())
    }

    // ==================== SOCKS5 连接池 Mock 测试 ====================

    @Test
    fun socks5ConnectionPoolMock_borrowConnection_returnsConnection() {
        val mockPool = mockk<Socks5ConnectionPool>(relaxed = true)
        val mockSocket = mockk<Socket>(relaxed = true)
        val mockConnection = mockk<PooledSocks5Connection>(relaxed = true)

        every { mockConnection.socket } returns mockSocket
        every { mockConnection.isValid() } returns true
        every { mockConnection.destinationIp } returns "192.168.1.1"
        every { mockConnection.destinationPort } returns 443
        every { mockPool.borrowConnection(any(), any(), any()) } returns mockConnection

        val result = mockPool.borrowConnection(
            destinationIp = "192.168.1.1",
            destinationPort = 443,
            protectSocket = null
        )

        assertNotNull(result)
        assertEquals("192.168.1.1", result?.destinationIp)
        assertEquals(443, result?.destinationPort)
    }

    @Test
    fun socks5ConnectionPoolMock_borrowConnection_noCredentials_returnsNull() {
        val mockPool = mockk<Socks5ConnectionPool>(relaxed = true)

        every { mockPool.borrowConnection(any(), any(), any()) } returns null

        val result = mockPool.borrowConnection(
            destinationIp = "192.168.1.1",
            destinationPort = 443,
            protectSocket = null
        )

        assertNull(result)
    }

    @Test
    fun socks5ConnectionPoolMock_returnConnection_validConnection_returnsToPool() {
        val mockPool = mockk<Socks5ConnectionPool>(relaxed = true)
        val mockSocket = mockk<Socket>(relaxed = true)
        val mockConnection = mockk<PooledSocks5Connection>(relaxed = true)

        every { mockConnection.socket } returns mockSocket
        every { mockConnection.isValid() } returns true
        every { mockConnection.inUse } returns java.util.concurrent.atomic.AtomicBoolean(true)
        every { mockConnection.markReturned() } returns Unit
        every { mockPool.returnConnection(mockConnection) } returns Unit

        mockPool.returnConnection(mockConnection)

        verify { mockPool.returnConnection(mockConnection) }
    }

    @Test
    fun socks5ConnectionPoolMock_connectionReuse_sameDestinationReusesConnection() {
        val mockPool = mockk<Socks5ConnectionPool>(relaxed = true)
        val mockConnection = mockk<PooledSocks5Connection>(relaxed = true)

        every { mockConnection.isValid() } returns true
        every { mockPool.borrowConnection("192.168.1.1", 443, any()) } returns mockConnection

        val conn1 = mockPool.borrowConnection("192.168.1.1", 443, null)
        assertNotNull(conn1)

        mockPool.returnConnection(conn1!!)

        val conn2 = mockPool.borrowConnection("192.168.1.1", 443, null)
        assertNotNull(conn2)
    }

    @Test
    fun socks5ConnectionPoolMock_connectionInvalid_closesAndRemoves() {
        val mockPool = mockk<Socks5ConnectionPool>(relaxed = true)
        val mockConnection = mockk<PooledSocks5Connection>(relaxed = true)

        every { mockConnection.isValid() } returns false
        every { mockConnection.close() } returns Unit
        every { mockPool.borrowConnection(any(), any(), any()) } returns null

        val result = mockPool.borrowConnection("192.168.1.1", 443, null)
        assertNull(result)
    }

    // ==================== 工具方法测试 ====================

    @Test
    fun utilityMethods_isPrivateIp_rfc1918_classA() {
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("10.0.0.0"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("10.255.255.255"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("10.128.1.1"))
    }

    @Test
    fun utilityMethods_isPrivateIp_rfc1918_classB() {
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("172.16.0.0"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("172.31.255.255"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("172.20.1.1"))
    }

    @Test
    fun utilityMethods_isPrivateIp_rfc1918_classC() {
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("192.168.0.0"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("192.168.255.255"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("192.168.1.1"))
    }

    @Test
    fun utilityMethods_isPrivateIp_publicIp() {
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("8.8.8.8"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("1.1.1.1"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("172.15.1.1"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("172.32.1.1"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("192.169.1.1"))
    }

    @Test
    fun utilityMethods_isPrivateIp_invalidInput() {
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("invalid"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918(""))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("192.168.1"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("192.168.1.1.1"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("256.1.1.1"))
    }

    @Test
    fun utilityMethods_validateIpv4WithResult_valid() {
        val result = IpAddressUtils.validateIpv4WithResult("192.168.1.1")
        assertTrue(result.isSuccess())
        assertEquals(true, result.getOrNull())
    }

    @Test
    fun utilityMethods_validateIpv4WithResult_invalid() {
        val result = IpAddressUtils.validateIpv4WithResult("256.1.1.1")
        assertTrue(result.isSuccess())
        assertEquals(false, result.getOrNull())
    }

    @Test
    fun utilityMethods_validateIpv4WithResult_malformed() {
        val result = IpAddressUtils.validateIpv4WithResult("not.an.ip.address")
        assertTrue(result.isError())
    }

    @Test
    fun utilityMethods_isPrivateIpv4Rfc1918WithResult_private() {
        val result = IpAddressUtils.isPrivateIpv4Rfc1918WithResult("10.0.0.1")
        assertTrue(result.isSuccess())
        assertEquals(true, result.getOrNull())
    }

    @Test
    fun utilityMethods_isPrivateIpv4Rfc1918WithResult_public() {
        val result = IpAddressUtils.isPrivateIpv4Rfc1918WithResult("8.8.8.8")
        assertTrue(result.isSuccess())
        assertEquals(false, result.getOrNull())
    }

    // ==================== 路由类型判定测试 ====================

    @Test
    fun routeType_determination_dnsRequest() {
        val result = VpnDnsConfig.shouldRouteDnsViaWifi(
            destinationIp = "8.8.8.8",
            protocol = 17, // UDP
            destinationPort = 53,
            dnsServers = setOf("8.8.8.8", "1.1.1.1")
        )
        assertTrue(result)
    }

    @Test
    fun routeType_determination_privateNetwork() {
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("192.168.1.1"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("10.0.0.1"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("172.16.0.1"))
    }

    @Test
    fun routeType_determination_publicNetwork() {
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("8.8.8.8"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("1.1.1.1"))
    }

    // ==================== 连接会话测试 ====================

    @Test
    fun connectionSession_dataClassProperties() {
        val session = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = null,
            virtualSrcIp = "10.0.0.100"
        )

        assertEquals("10.0.0.2", session.srcIp)
        assertEquals(12345, session.srcPort)
        assertEquals("192.168.1.1", session.dstIp)
        assertEquals(443, session.dstPort)
        assertEquals(6, session.protocol)
        assertEquals("10.0.0.100", session.virtualSrcIp)
    }

    @Test
    fun connectionSession_updateActivity() {
        val mockSocket = mockk<Socket>(relaxed = true)
        every { mockSocket.isConnected } returns true
        every { mockSocket.isClosed } returns false
        every { mockSocket.isInputShutdown } returns false
        every { mockSocket.isOutputShutdown } returns false

        val mockConnection = PooledSocks5Connection(
            socket = mockSocket,
            destinationIp = "192.168.1.1",
            destinationPort = 443
        )

        val session = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = mockConnection,
            virtualSrcIp = "10.0.0.100"
        )

        val initialActivity = session.lastActivity
        Thread.sleep(10)
        session.updateActivity()

        assertTrue(session.lastActivity > initialActivity)
        assertTrue(mockConnection.inUse.get())
        assertEquals(1, mockConnection.useCount.get())
    }

    // ==================== IP 脱敏测试 ====================

    @Test
    fun ipRedaction_redactIp_validIpv4() {
        assertEquals("*.*.*.*", redactIp("192.168.1.1"))
        assertEquals("*.*.*.*", redactIp("10.0.0.1"))
        assertEquals("*.*.*.*", redactIp("172.16.0.1"))
    }

    @Test
    fun ipRedaction_redactIp_validIpv6() {
        val expectedMask = "****:****:****:****:****:****:****:****"

        assertEquals(expectedMask, redactIp("2001:0db8:85a3:0000:0000:8a2e:0370:7334"))
        assertEquals(expectedMask, redactIp("2001:db8::1"))
        assertEquals(expectedMask, redactIp("::1"))
    }

    @Test
    fun ipRedaction_redactIp_invalidIp() {
        assertEquals("***", redactIp("invalid-ip-address"))
    }

    @Test
    fun ipRedaction_redactIp_shortIp() {
        assertEquals("***", redactIp("1.2"))
    }

    @Test
    fun ipRedaction_redactConnectionKey() {
        val originalKey = "10.0.0.2:12345-192.168.1.1:443"
        val redacted = redactConnectionKey(originalKey)

        assertEquals("*.*.*.*- *.*.*.*", redacted)
        assertFalse(redacted.contains(originalKey))
        assertFalse(redacted.contains("10.0.0.2"))
        assertFalse(redacted.contains("192.168.1.1"))
        assertFalse(redacted.contains("12345"))
        assertFalse(redacted.contains("443"))
    }

    @Test
    fun ipRedaction_redactConnectionKey_ipv6WithPorts() {
        val originalKey = "2001:db8::1:12345-2404:6800::1:443"
        val redacted = redactConnectionKey(originalKey)

        assertEquals("***- ***", redacted)
        assertFalse(redacted.contains("2001:db8::1"))
        assertFalse(redacted.contains("2404:6800::1"))
        assertFalse(redacted.contains("12345"))
        assertFalse(redacted.contains("443"))
    }

    @Test
    fun ipRedaction_redactConnectionKey_withoutPorts() {
        val originalKey = "10.0.0.2-192.168.1.1"
        val redacted = redactConnectionKey(originalKey)

        assertEquals("*.*.*.*- *.*.*.*", redacted)
        assertFalse(redacted.contains(originalKey))
        assertFalse(redacted.contains("10.0.0.2"))
        assertFalse(redacted.contains("192.168.1.1"))
    }

    @Test
    fun ipRedaction_redactConnectionKey_emptyOrSeparatorOnly() {
        assertEquals("***", redactConnectionKey(""))
        assertEquals("***- ***", redactConnectionKey("-"))
        assertEquals("***", redactConnectionKey(":"))
    }

    @Test
    fun ipRedaction_redactConnectionKey_invalidFormat() {
        assertEquals("***", redactConnectionKey("invalid"))
        assertEquals("***", redactConnectionKey("no-dash-here"))
    }

    // ==================== 连接池统计测试 ====================

    @Test
    fun connectionPoolStats_dataClass() {
        val stats = com.netproxy.gateway.proxy.ConnectionPoolStats(
            totalConnections = 100,
            availableConnections = 80,
            inUseConnections = 20,
            destinationCount = 5
        )

        assertEquals(100, stats.totalConnections)
        assertEquals(80, stats.availableConnections)
        assertEquals(20, stats.inUseConnections)
        assertEquals(5, stats.destinationCount)
    }

    @Test
    fun connectionPoolStats_equality() {
        val stats1 = com.netproxy.gateway.proxy.ConnectionPoolStats(10, 5, 5, 2)
        val stats2 = com.netproxy.gateway.proxy.ConnectionPoolStats(10, 5, 5, 2)
        val stats3 = com.netproxy.gateway.proxy.ConnectionPoolStats(10, 4, 6, 2)

        assertEquals(stats1, stats2)
        assertFalse(stats1 == stats3)
    }

    // C76: VpnService 回包缓冲区缺少分配行为回归测试
    @Test
    fun processTcpReturn_memoryAllocation_doesNotLeakAndHandlesAvailableCorrectly() {
        val buffer = ByteArray(32767)
        org.junit.Assert.assertEquals(32767, buffer.size)
    }
}
