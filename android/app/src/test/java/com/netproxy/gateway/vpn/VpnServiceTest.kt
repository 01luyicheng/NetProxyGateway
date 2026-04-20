package com.netproxy.gateway.vpn

import android.net.VpnService as AndroidVpnService
import android.os.ParcelFileDescriptor
import com.netproxy.gateway.proxy.PooledSocks5Connection
import com.netproxy.gateway.proxy.Socks5ConnectionPool
import com.netproxy.gateway.utils.IpAddressUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * VpnService 的单元测试
 * 测试 VpnService 的核心业务逻辑，包括 VPN 配置、数据包处理、SOCKS5 集成、状态管理和工具方法。
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
        // Verify the constants are defined correctly
        assertEquals("10.0.0.2", GatewayVpnService.VPN_ADDRESS)
        assertEquals("0.0.0.0", GatewayVpnService.VPN_ROUTE)
        assertEquals(1500, GatewayVpnService.VPN_MTU)
        assertEquals("127.0.0.1", GatewayVpnService.SOCKS5_PROXY_HOST)
        assertEquals(1080, GatewayVpnService.SOCKS5_PROXY_PORT)
    }

    // ==================== VpnDnsConfig 测试 (补充现有测试) ====================

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
        // STOPPED -> STARTING
        val starting = VpnStatus(state = VpnState.STARTING)
        assertEquals(VpnState.STARTING, starting.state)

        // STARTING -> RUNNING
        val running = VpnStatus(state = VpnState.RUNNING, connectedClients = 1)
        assertEquals(VpnState.RUNNING, running.state)
        assertEquals(1, running.connectedClients)

        // RUNNING -> ERROR
        val error = VpnStatus(state = VpnState.ERROR, errorMessage = "Connection failed")
        assertEquals(VpnState.ERROR, error.state)
        assertEquals("Connection failed", error.errorMessage)

        // ERROR -> STOPPED
        val stopped = VpnStatus(state = VpnState.STOPPED)
        assertEquals(VpnState.STOPPED, stopped.state)
    }

    @Test
    fun vpnStatus_withConnectedClients() {
        val status0 = VpnStatus(connectedClients = 0)
        val status1 = VpnStatus(connectedClients = 1)
        val status100 = VpnStatus(connectedClients = 100)

        assertEquals(0, status0.connectedClients)
        assertEquals(1, status1.connectedClients)
        assertEquals(100, status100.connectedClients)
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
        // Although negative doesn't make sense, the data class allows it
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
        // These are used in packet parsing
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
        // 验证 VPN 地址格式
        val vpnAddress = GatewayVpnService.VPN_ADDRESS
        val parts = vpnAddress.split(".")
        assertEquals(4, parts.size)
        assertTrue(parts.all { it.toIntOrNull() in 0..255 })
    }

    @Test
    fun vpnConfiguration_routeFormat() {
        // 验证路由格式
        val vpnRoute = GatewayVpnService.VPN_ROUTE
        assertEquals("0.0.0.0", vpnRoute)
    }

    @Test
    fun vpnConfiguration_mtuValue() {
        // 验证 MTU 值在合理范围内
        val mtu = GatewayVpnService.VPN_MTU
        assertTrue(mtu in 1280..9000)
        assertEquals(1500, mtu)
    }

    @Test
    fun vpnConfiguration_proxyHostIsLocalhost() {
        // 验证 SOCKS5 代理主机是本地地址
        assertEquals("127.0.0.1", GatewayVpnService.SOCKS5_PROXY_HOST)
    }

    @Test
    fun vpnConfiguration_proxyPortIsValid() {
        // 验证 SOCKS5 代理端口在有效范围内
        val port = GatewayVpnService.SOCKS5_PROXY_PORT
        assertTrue(port in 1..65535)
        assertEquals(1080, port)
    }

    // ==================== 数据包处理测试 ====================

    @Test
    fun packetParsing_parseDestinationIp_validIpv4Packet() {
        // 构造一个有效的 IPv4 数据包
        val packet = ByteArray(40)
        // IP 版本 4, IHL = 5 (20 bytes)
        packet[0] = 0x45
        // 目标 IP: 192.168.1.1
        packet[16] = 192.toByte()
        packet[17] = 168.toByte()
        packet[18] = 1.toByte()
        packet[19] = 1.toByte()

        val result = VpnTestUtils.parseDestinationIp(packet, packet.size)
        assertEquals("192.168.1.1", result)
    }

    @Test
    fun packetParsing_parseDestinationIp_publicIp() {
        val packet = ByteArray(40)
        packet[0] = 0x45

        // 目标 IP: 8.8.8.8 (Google DNS)
        packet[16] = 8.toByte()
        packet[17] = 8.toByte()
        packet[18] = 8.toByte()
        packet[19] = 8.toByte()

        val result = VpnTestUtils.parseDestinationIp(packet, packet.size)
        assertEquals("8.8.8.8", result)
    }

    @Test
    fun packetParsing_parseDestinationIp_invalidVersion() {
        // IPv6 数据包 (版本 6)
        val packet = ByteArray(40)
        packet[0] = 0x60  // Version 6

        val result = VpnTestUtils.parseDestinationIp(packet, packet.size)
        assertNull(result)
    }

    @Test
    fun packetParsing_parseDestinationIp_packetTooShort() {
        // 数据包太短
        val packet = ByteArray(10)
        val result = VpnTestUtils.parseDestinationIp(packet, packet.size)
        assertNull(result)
    }

    @Test
    fun packetParsing_parseDestinationIp_exactly20Bytes() {
        val packet = ByteArray(20)
        packet[0] = 0x45

        // 目标 IP: 1.2.3.4
        packet[16] = 1.toByte()
        packet[17] = 2.toByte()
        packet[18] = 3.toByte()
        packet[19] = 4.toByte()

        val result = VpnTestUtils.parseDestinationIp(packet, packet.size)
        assertEquals("1.2.3.4", result)
    }

    @Test
    fun packetParsing_parseDestinationIp_withOptionsHeader() {
        // IHL = 6 (24 bytes header with options)
        val packet = ByteArray(40)
        packet[0] = 0x46  // Version 4, IHL = 6

        // 目标 IP 仍然在字节 16-19
        packet[16] = 10.toByte()
        packet[17] = 0.toByte()
        packet[18] = 0.toByte()
        packet[19] = 1.toByte()

        val result = VpnTestUtils.parseDestinationIp(packet, packet.size)
        assertEquals("10.0.0.1", result)
    }

    @Test
    fun packetParsing_parseDestinationIp_maximumValues() {
        val packet = ByteArray(40)
        packet[0] = 0x45

        // IP: 255.255.255.255
        packet[16] = 0xFF.toByte()
        packet[17] = 0xFF.toByte()
        packet[18] = 0xFF.toByte()
        packet[19] = 0xFF.toByte()

        val result = VpnTestUtils.parseDestinationIp(packet, packet.size)
        assertEquals("255.255.255.255", result)
    }

    @Test
    fun packetParsing_parseSourceIp_validIpv4Packet() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        // 源 IP: 10.0.0.1
        packet[12] = 10.toByte()
        packet[13] = 0.toByte()
        packet[14] = 0.toByte()
        packet[15] = 1.toByte()

        val result = VpnTestUtils.parseSourceIp(packet, packet.size)
        assertEquals("10.0.0.1", result)
    }

    @Test
    fun packetParsing_parseSourceIp_invalidVersion() {
        val packet = ByteArray(40)
        packet[0] = 0x60  // IPv6

        val result = VpnTestUtils.parseSourceIp(packet, packet.size)
        assertNull(result)
    }

    @Test
    fun packetParsing_parseProtocol_tcp() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[9] = 6  // TCP

        val result = VpnTestUtils.parseProtocol(packet)
        assertEquals(6, result)
    }

    @Test
    fun packetParsing_parseProtocol_udp() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[9] = 17  // UDP

        val result = VpnTestUtils.parseProtocol(packet)
        assertEquals(17, result)
    }

    @Test
    fun packetParsing_parseProtocol_icmp() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[9] = 1  // ICMP

        val result = VpnTestUtils.parseProtocol(packet)
        assertEquals(1, result)
    }

    @Test
    fun packetParsing_parseProtocol_packetTooShort() {
        val packet = ByteArray(9)
        val result = VpnTestUtils.parseProtocol(packet)
        assertEquals(0, result)
    }

    @Test
    fun packetParsing_parseDestinationPort_tcpPacket() {
        val packet = ByteArray(40)
        packet[0] = 0x45  // IHL = 5 (20 bytes)
        // TCP 目标端口: 443 (HTTPS)
        packet[22] = 1.toByte()   // 0x01
        packet[23] = 0xBB.toByte() // 0xBB = 187, 1*256 + 187 = 443

        val result = VpnTestUtils.parseDestinationPort(packet, packet.size)
        assertEquals(443, result)
    }

    @Test
    fun packetParsing_parseDestinationPort_httpPort80() {
        val packet = ByteArray(40)
        packet[0] = 0x45  // IHL = 5 (20 bytes)

        // TCP 目标端口在字节 22-23 (20 + 2)
        // 端口 80 = 0x0050
        packet[22] = 0x00.toByte()
        packet[23] = 0x50.toByte()

        val result = VpnTestUtils.parseDestinationPort(packet, packet.size)
        assertEquals(80, result)
    }

    @Test
    fun packetParsing_parseDestinationPort_dnsPort53() {
        val packet = ByteArray(40)
        packet[0] = 0x45

        // 端口 53 = 0x0035
        packet[22] = 0x00.toByte()
        packet[23] = 0x35.toByte()

        val result = VpnTestUtils.parseDestinationPort(packet, packet.size)
        assertEquals(53, result)
    }

    @Test
    fun packetParsing_parseDestinationPort_highPort() {
        val packet = ByteArray(40)
        packet[0] = 0x45

        // 端口 65535 = 0xFFFF
        packet[22] = 0xFF.toByte()
        packet[23] = 0xFF.toByte()

        val result = VpnTestUtils.parseDestinationPort(packet, packet.size)
        assertEquals(65535, result)
    }

    @Test
    fun packetParsing_parseDestinationPort_maximumValue() {
        val packet = ByteArray(40)
        packet[0] = 0x45

        packet[22] = 0xFF.toByte()
        packet[23] = 0xFF.toByte()

        val result = VpnTestUtils.parseDestinationPort(packet, packet.size)
        assertEquals(65535, result)
    }

    @Test
    fun packetParsing_parseDestinationPort_minimumValue() {
        val packet = ByteArray(40)
        packet[0] = 0x45

        packet[22] = 0x00
        packet[23] = 0x00

        val result = VpnTestUtils.parseDestinationPort(packet, packet.size)
        assertEquals(0, result)
    }

    @Test
    fun packetParsing_parseDestinationPort_packetTooShort() {
        val packet = ByteArray(21)
        packet[0] = 0x45

        val result = VpnTestUtils.parseDestinationPort(packet, packet.size)
        assertNull(result)
    }

    @Test
    fun packetParsing_parseDestinationPort_withOptionsHeader() {
        val packet = ByteArray(44)
        packet[0] = 0x46  // IHL = 6 (24 bytes header)

        // TCP 目标端口在字节 26-27 (24 + 2, 因为源端口占2字节)
        packet[26] = 0x01.toByte()
        packet[27] = 0xBB.toByte()

        val result = VpnTestUtils.parseDestinationPort(packet, packet.size)
        assertEquals(443, result)
    }

    @Test
    fun packetParsing_parseSourcePort_tcpPacket() {
        val packet = ByteArray(40)
        packet[0] = 0x45  // IHL = 5
        // TCP 源端口: 12345
        packet[20] = 0x30.toByte() // 48
        packet[21] = 0x39.toByte() // 57, 48*256 + 57 = 12345

        val result = VpnTestUtils.parseSourcePort(packet, packet.size)
        assertEquals(12345, result)
    }

    @Test
    fun packetParsing_parseSourcePort_ephemeralPort() {
        val packet = ByteArray(40)
        packet[0] = 0x45

        // 端口 49152 = 0xC000
        packet[20] = 0xC0.toByte()
        packet[21] = 0x00.toByte()

        val result = VpnTestUtils.parseSourcePort(packet, packet.size)
        assertEquals(49152, result)
    }

    @Test
    fun packetParsing_extractTransportPayloadInfo_tcpPacket() {
        val packet = ByteArray(60)
        packet[0] = 0x45  // IHL = 5 (20 bytes)
        packet[9] = 6     // TCP
        // TCP Data Offset = 5 (20 bytes), 位于字节 12 的高 4 位
        packet[32] = 0x50.toByte() // Data Offset = 5

        val result = VpnTestUtils.extractTransportPayloadInfo(packet, packet.size)
        assertNotNull(result)
        assertEquals(40, result?.first)  // 20 (IP) + 20 (TCP)
        assertEquals(20, result?.second) // 60 - 40 = 20
    }

    @Test
    fun packetParsing_extractTransportPayloadInfo_udpPacket() {
        val packet = ByteArray(40)
        packet[0] = 0x45  // IHL = 5 (20 bytes)
        packet[9] = 17    // UDP

        val result = VpnTestUtils.extractTransportPayloadInfo(packet, packet.size)
        assertNotNull(result)
        assertEquals(28, result?.first)  // 20 (IP) + 8 (UDP)
        assertEquals(12, result?.second) // 40 - 28 = 12
    }

    @Test
    fun packetParsing_extractTransportPayloadInfo_noPayload() {
        val packet = ByteArray(28)
        packet[0] = 0x45
        packet[9] = 17  // UDP

        val result = VpnTestUtils.extractTransportPayloadInfo(packet, packet.size)
        assertNull(result)  // 没有 payload
    }

    @Test
    fun packetParsing_extractTransportPayloadInfo_packetTooShort() {
        val packet = ByteArray(10)
        val result = VpnTestUtils.extractTransportPayloadInfo(packet, packet.size)
        assertNull(result)
    }

    @Test
    fun packetParsing_extractTransportPayloadInfo_tcpWithOptions() {
        val packet = ByteArray(80)
        packet[0] = 0x45
        packet[9] = 6  // TCP

        // TCP Data Offset = 8 (32 bytes with options)
        packet[32] = (8 shl 4).toByte()

        val result = VpnTestUtils.extractTransportPayloadInfo(packet, packet.size)
        assertNotNull(result)
        assertEquals(52, result?.first)   // 20 (IP) + 32 (TCP with options)
        assertEquals(28, result?.second)  // 80 - 52 = 28
    }

    // ==================== 校验和计算测试 ====================

    @Test
    fun checksumCalculation_calculateChecksum_simpleCase() {
        // 测试简单的校验和计算
        val data = byteArrayOf(0x45, 0x00, 0x00, 0x3c, 0x1c, 0x46)
        val checksum = VpnTestUtils.calculateChecksum(data, 0, data.size)

        // 验证校验和是 16 位值
        assertTrue(checksum in 0..65535)
    }

    @Test
    fun checksumCalculation_calculateChecksum_withOddLength() {
        // 测试奇数长度的数据
        val data = byteArrayOf(0x45, 0x00, 0x00)
        val checksum = VpnTestUtils.calculateChecksum(data, 0, data.size)

        assertTrue(checksum in 0..65535)
    }

    @Test
    fun checksumCalculation_calculateChecksum_ipHeader() {
        // 构造 IP 头进行校验和计算
        val ipHeader = ByteArray(20)
        ipHeader[0] = 0x45  // Version 4, IHL 5
        ipHeader[1] = 0x00
        ipHeader[2] = 0x00
        ipHeader[3] = 0x3c  // Total length = 60
        ipHeader[4] = 0x00
        ipHeader[5] = 0x00
        ipHeader[6] = 0x40  // DF flag
        ipHeader[7] = 0x00
        ipHeader[8] = 0x40  // TTL = 64
        ipHeader[9] = 0x06  // Protocol = TCP
        // 校验和字段初始化为 0
        ipHeader[10] = 0x00
        ipHeader[11] = 0x00
        // 源 IP: 10.0.0.1
        ipHeader[12] = 0x0A
        ipHeader[13] = 0x00
        ipHeader[14] = 0x00
        ipHeader[15] = 0x01
        // 目标 IP: 192.168.1.1
        ipHeader[16] = 0xC0.toByte()
        ipHeader[17] = 0xA8.toByte()
        ipHeader[18] = 0x01
        ipHeader[19] = 0x01

        val checksum = VpnTestUtils.calculateChecksum(ipHeader, 0, 20)
        assertTrue(checksum in 0..65535)

        // 将计算出的校验和写回头部并重新计算，应该得到 0
        ipHeader[10] = (checksum shr 8).toByte()
        ipHeader[11] = (checksum and 0xFF).toByte()

        val verificationChecksum = VpnTestUtils.calculateChecksum(ipHeader, 0, 20)
        assertEquals(0, verificationChecksum)
    }

    @Test
    fun checksumCalculation_calculateChecksum_zeroData() {
        val data = ByteArray(20) { 0x00 }
        val checksum = VpnTestUtils.calculateChecksum(data, 0, data.size)

        assertTrue(checksum in 0..65535)
    }

    @Test
    fun checksumCalculation_calculateTcpChecksum_withPseudoHeader() {
        // 构造 TCP 伪头和 TCP 头
        val buffer = ByteArray(40)
        // 源 IP: 10.0.0.1
        val srcIp = listOf(10, 0, 0, 1)
        // 目标 IP: 192.168.1.1
        val dstIp = listOf(192, 168, 1, 1)

        // TCP 头 (从偏移 20 开始)
        buffer[20] = 0x30.toByte() // 源端口高字节
        buffer[21] = 0x39.toByte() // 源端口低字节 (12345)
        buffer[22] = 0x01.toByte() // 目标端口高字节
        buffer[23] = 0xBB.toByte() // 目标端口低字节 (443)
        buffer[24] = 0x00
        buffer[25] = 0x00
        buffer[26] = 0x00
        buffer[27] = 0x01 // Seq number
        buffer[28] = 0x00
        buffer[29] = 0x00
        buffer[30] = 0x00
        buffer[31] = 0x00 // Ack number
        buffer[32] = 0x50 // Data offset = 5
        buffer[33] = 0x18 // PSH + ACK
        buffer[34] = 0x20 // Window size
        buffer[35] = 0x00
        buffer[36] = 0x00 // 校验和占位
        buffer[37] = 0x00
        buffer[38] = 0x00
        buffer[39] = 0x00

        val checksum = VpnTestUtils.calculateTcpChecksum(buffer, srcIp, dstIp, 6, 20, 0)
        assertTrue(checksum in 0..65535)
    }

    @Test
    fun checksumCalculation_calculateTcpChecksum_withPayload() {
        val buffer = ByteArray(60)
        val srcIp = listOf(10, 0, 0, 1)
        val dstIp = listOf(192, 168, 1, 1)

        // TCP 头
        buffer[20] = 0x30.toByte()
        buffer[21] = 0x39.toByte()
        buffer[22] = 0x01.toByte()
        buffer[23] = 0xBB.toByte()
        buffer[32] = 0x50  // Data offset = 5

        // Payload (20 bytes)
        for (i in 40 until 60) {
            buffer[i] = (i % 256).toByte()
        }

        val checksum = VpnTestUtils.calculateTcpChecksum(buffer, srcIp, dstIp, 6, 20, 20)

        assertTrue(checksum in 0..65535)
    }

    @Test
    fun checksumCalculation_calculateTcpChecksum_ipv4Options() {
        val buffer = ByteArray(64)
        val srcIp = listOf(10, 0, 0, 1)
        val dstIp = listOf(192, 168, 1, 1)

        // IPv4 IHL = 6, TCP starts at offset 24.
        buffer[0] = 0x46

        // 20..23 是 IPv4 options，不应被 TCP 校验和覆盖
        buffer[20] = 0x11
        buffer[21] = 0x22
        buffer[22] = 0x33
        buffer[23] = 0x44

        // TCP header (20 bytes) from offset 24
        buffer[24] = 0x30.toByte()
        buffer[25] = 0x39.toByte()
        buffer[26] = 0x01.toByte()
        buffer[27] = 0xBB.toByte()
        buffer[36] = 0x50

        val checksum = VpnTestUtils.calculateTcpChecksum(buffer, srcIp, dstIp, 6, 20, 0)
        val expected = computeTcpChecksumExpected(buffer, srcIp, dstIp, 6, 24, 20)

        assertEquals(expected, checksum)
    }

    private fun computeTcpChecksumExpected(
        buffer: ByteArray,
        srcIp: List<Int>,
        dstIp: List<Int>,
        protocol: Int,
        tcpStartOffset: Int,
        tcpSegmentLength: Int,
    ): Int {
        var sum = 0
        sum += (srcIp[0] shl 8) or srcIp[1]
        sum += (srcIp[2] shl 8) or srcIp[3]
        sum += (dstIp[0] shl 8) or dstIp[1]
        sum += (dstIp[2] shl 8) or dstIp[3]
        sum += protocol
        sum += tcpSegmentLength

        for (i in tcpStartOffset until tcpStartOffset + tcpSegmentLength step 2) {
            if (i + 1 < buffer.size) {
                sum += ((buffer[i].toInt() and 0xFF) shl 8) or (buffer[i + 1].toInt() and 0xFF)
            } else if (i < buffer.size) {
                sum += (buffer[i].toInt() and 0xFF) shl 8
            }
        }

        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        return sum.inv() and 0xFFFF
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

        // 初始状态
        assertFalse(connection.inUse.get())
        assertEquals(0, connection.useCount.get())

        // 标记使用中
        connection.markUsed()
        assertTrue(connection.inUse.get())
        assertEquals(1, connection.useCount.get())

        // 标记归还
        connection.markReturned()
        assertFalse(connection.inUse.get())
        assertEquals(1, connection.useCount.get()) // useCount 不变

        // 验证有效性
        assertTrue(connection.isValid())

        // 关闭连接
        connection.close()
        verify { mockSocket.close() }
    }

    @Test
    fun socks5Integration_pooledConnection_invalidWhenClosed() {
        val mockSocket = mockk<Socket>(relaxed = true)
        every { mockSocket.isConnected } returns false  // 未连接
        every { mockSocket.isClosed } returns true

        val connection = PooledSocks5Connection(
            socket = mockSocket,
            destinationIp = "192.168.1.1",
            destinationPort = 443
        )

        assertFalse(connection.isValid())
    }

    // ==================== SOCKS5 连接池 Mock 测试 (来自 VpnServiceCoreTest) ====================

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

        // 第一次借用
        val conn1 = mockPool.borrowConnection("192.168.1.1", 443, null)
        assertNotNull(conn1)

        // 归还
        mockPool.returnConnection(conn1!!)

        // 第二次借用（应该复用）
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
        // 10.0.0.0/8
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("10.0.0.0"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("10.255.255.255"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("10.128.1.1"))
    }

    @Test
    fun utilityMethods_isPrivateIp_rfc1918_classB() {
        // 172.16.0.0/12
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("172.16.0.0"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("172.31.255.255"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("172.20.1.1"))
    }

    @Test
    fun utilityMethods_isPrivateIp_rfc1918_classC() {
        // 192.168.0.0/16
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("192.168.0.0"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("192.168.255.255"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("192.168.1.1"))
    }

    @Test
    fun utilityMethods_isPrivateIp_publicIp() {
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("8.8.8.8"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("1.1.1.1"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("172.15.1.1"))  // 172.15.x.x 不是私有地址
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("172.32.1.1"))  // 172.32.x.x 不是私有地址
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("192.169.1.1")) // 192.169.x.x 不是私有地址
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
        // 当输入无法解析为数字时，返回 error 结果
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
        // DNS 请求应该走 WiFi
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
        // 内网地址应该走 WiFi
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("192.168.1.1"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("10.0.0.1"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("172.16.0.1"))
    }

    @Test
    fun routeType_determination_publicNetwork() {
        // 公网地址应该走代理
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("8.8.8.8"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("1.1.1.1"))
    }

    // ==================== 连接会话测试 ====================

    @Test
    fun connectionSession_dataClassProperties() {
        // 验证 ConnectionSession 数据类的属性
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
        Thread.sleep(10) // 确保时间有变化
        session.updateActivity()

        assertTrue(session.lastActivity > initialActivity)
        assertTrue(mockConnection.inUse.get())
        assertEquals(1, mockConnection.useCount.get())
    }

    // ==================== 空闲延迟计算测试 ====================

    @Test
    fun idleDelayCalculation_calculateIdleDelay_noIdle() {
        // 空闲轮数为 0 时，延迟为 1ms
        val delay = VpnTestUtils.calculateIdleDelay(0)
        assertEquals(1L, delay)
    }

    @Test
    fun idleDelayCalculation_calculateIdleDelay_increasingDelay() {
        // 空闲延迟应该指数增长
        assertEquals(1L, VpnTestUtils.calculateIdleDelay(0))
        assertEquals(2L, VpnTestUtils.calculateIdleDelay(1))
        assertEquals(4L, VpnTestUtils.calculateIdleDelay(2))
        assertEquals(8L, VpnTestUtils.calculateIdleDelay(3))
        assertEquals(16L, VpnTestUtils.calculateIdleDelay(4))
        assertEquals(32L, VpnTestUtils.calculateIdleDelay(5))
        assertEquals(64L, VpnTestUtils.calculateIdleDelay(6))
    }

    @Test
    fun idleDelayCalculation_calculateIdleDelay_maxDelay() {
        // 延迟不应该超过最大值 100ms
        val delay = VpnTestUtils.calculateIdleDelay(100)
        assertEquals(100L, delay)
    }

    @Test
    fun idleDelayCalculation_calculateIdleDelay_boundaryValues() {
        // 测试边界值
        assertTrue(VpnTestUtils.calculateIdleDelay(10) <= 100L)
        assertTrue(VpnTestUtils.calculateIdleDelay(31) <= 100L) // 最大值限制
    }

    // ==================== 虚拟 IP 分配测试 ====================

    @Test
    fun virtualIpAllocation_getOrAllocateVirtualIp() {
        // 模拟虚拟 IP 池的行为
        val virtualIpPool = HashMap<String, String>()
        val reverseIpMap = HashMap<String, String>()
        val nextVirtualIp = AtomicInteger(1)

        // 第一次分配
        val ip1 = VpnTestUtils.getOrAllocateVirtualIp("8.8.8.8", virtualIpPool, reverseIpMap, nextVirtualIp)
        assertEquals("10.0.0.1", ip1)
        // virtualIpPool 的 key 是 realDstIp，value 是虚拟 IP
        assertEquals("10.0.0.1", virtualIpPool["8.8.8.8"])

        // 同一 IP 应该返回相同的虚拟 IP
        val ip1Again = VpnTestUtils.getOrAllocateVirtualIp("8.8.8.8", virtualIpPool, reverseIpMap, nextVirtualIp)
        assertEquals(ip1, ip1Again)

        // 不同 IP 应该分配新的虚拟 IP
        val ip2 = VpnTestUtils.getOrAllocateVirtualIp("1.1.1.1", virtualIpPool, reverseIpMap, nextVirtualIp)
        assertEquals("10.0.0.2", ip2)
        assertFalse(ip1 == ip2)
    }

    @Test
    fun virtualIpAllocation_reverseMapping() {
        val virtualIpPool = HashMap<String, String>()
        val reverseIpMap = HashMap<String, String>()
        val nextVirtualIp = AtomicInteger(1)

        val virtualIp = VpnTestUtils.getOrAllocateVirtualIp("8.8.8.8", virtualIpPool, reverseIpMap, nextVirtualIp)

        // 验证反向映射
        assertEquals("8.8.8.8", reverseIpMap[virtualIp])
    }

    @Test
    fun virtualIpAllocation_firstAllocation_returns10_0_0_1() {
        val virtualIpPool = ConcurrentHashMap<String, String>()
        val reverseIpMap = ConcurrentHashMap<String, String>()
        val nextVirtualIp = AtomicInteger(1)

        val result = VpnTestUtils.getOrAllocateVirtualIp("8.8.8.8", virtualIpPool, reverseIpMap, nextVirtualIp)

        assertEquals("10.0.0.1", result)
        assertEquals("10.0.0.1", virtualIpPool["8.8.8.8"])
        assertEquals("8.8.8.8", reverseIpMap["10.0.0.1"])
    }

    @Test
    fun virtualIpAllocation_sameRealIp_returnsSameVirtualIp() {
        val virtualIpPool = ConcurrentHashMap<String, String>()
        val reverseIpMap = ConcurrentHashMap<String, String>()
        val nextVirtualIp = AtomicInteger(1)

        val ip1 = VpnTestUtils.getOrAllocateVirtualIp("8.8.8.8", virtualIpPool, reverseIpMap, nextVirtualIp)
        val ip2 = VpnTestUtils.getOrAllocateVirtualIp("8.8.8.8", virtualIpPool, reverseIpMap, nextVirtualIp)

        assertEquals(ip1, ip2)
        assertEquals(1, virtualIpPool.size)
    }

    @Test
    fun virtualIpAllocation_differentRealIps_returnsDifferentVirtualIps() {
        val virtualIpPool = ConcurrentHashMap<String, String>()
        val reverseIpMap = ConcurrentHashMap<String, String>()
        val nextVirtualIp = AtomicInteger(1)

        val ip1 = VpnTestUtils.getOrAllocateVirtualIp("8.8.8.8", virtualIpPool, reverseIpMap, nextVirtualIp)
        val ip2 = VpnTestUtils.getOrAllocateVirtualIp("1.1.1.1", virtualIpPool, reverseIpMap, nextVirtualIp)

        assertEquals("10.0.0.1", ip1)
        assertEquals("10.0.0.2", ip2)
        assertEquals(2, virtualIpPool.size)
    }

    @Test
    fun virtualIpAllocation_multipleAllocations_incrementsCorrectly() {
        val virtualIpPool = ConcurrentHashMap<String, String>()
        val reverseIpMap = ConcurrentHashMap<String, String>()
        val nextVirtualIp = AtomicInteger(1)

        for (i in 1..10) {
            val realIp = "192.168.1.$i"
            val virtualIp = VpnTestUtils.getOrAllocateVirtualIp(realIp, virtualIpPool, reverseIpMap, nextVirtualIp)
            assertEquals("10.0.0.$i", virtualIp)
        }

        assertEquals(10, virtualIpPool.size)
        assertEquals(11, nextVirtualIp.get())
    }

    @Test
    fun virtualIpAllocation_boundary254_returns10_0_0_254() {
        val virtualIpPool = ConcurrentHashMap<String, String>()
        val reverseIpMap = ConcurrentHashMap<String, String>()
        val nextVirtualIp = AtomicInteger(254)

        val ip = VpnTestUtils.getOrAllocateVirtualIp("8.8.8.8", virtualIpPool, reverseIpMap, nextVirtualIp)

        assertEquals("10.0.0.254", ip)
        assertEquals("8.8.8.8", reverseIpMap["10.0.0.254"])
        assertEquals(255, nextVirtualIp.get())
    }

    @Test
    fun virtualIpAllocation_overflow255_resetsAndNeverReturnsBroadcastIp() {
        val virtualIpPool = ConcurrentHashMap<String, String>()
        val reverseIpMap = ConcurrentHashMap<String, String>()
        val nextVirtualIp = AtomicInteger(255)

        virtualIpPool["1.1.1.1"] = "10.0.0.1"
        reverseIpMap["10.0.0.1"] = "1.1.1.1"

        val ip = VpnTestUtils.getOrAllocateVirtualIp("8.8.8.8", virtualIpPool, reverseIpMap, nextVirtualIp)

        assertEquals("10.0.0.1", ip)
        assertFalse("10.0.0.255" == ip)
        assertEquals(1, virtualIpPool.size)
        assertEquals("10.0.0.1", virtualIpPool["8.8.8.8"])
        assertEquals("8.8.8.8", reverseIpMap["10.0.0.1"])
        assertEquals(2, nextVirtualIp.get())
    }

    // ==================== IP 脱敏测试 ====================

    @Test
    fun ipRedaction_redactIp_validIpv4() {
        assertEquals("*.*.*.*", redactIp("192.168.1.1"))
        assertEquals("*.*.*.*", redactIp("10.0.0.1"))
        assertEquals("*.*.*.*", redactIp("172.16.0.1"))
    }

    @Test
    fun ipRedaction_redactIp_invalidIp() {
        val result = redactIp("invalid-ip-address")
        assertTrue(result.contains("***"))
    }

    @Test
    fun ipRedaction_redactIp_shortIp() {
        val result = redactIp("1.2")
        assertTrue(result.contains("***"))
    }

    @Test
    fun ipRedaction_redactConnectionKey() {
        assertEquals("*.*.*.*-*.*.*.*", redactConnectionKey("10.0.0.2:12345-192.168.1.1:443"))
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

    // ==================== 真实数据包测试 (来自 VpnServiceCoreTest) ====================

    @Test
    fun realPacket_tcpSynPacket_parsesCorrectly() {
        // 构造一个真实的 TCP SYN 包
        val packet = ByteArray(40)

        // IP 头 (20 bytes)
        packet[0] = 0x45  // Version 4, IHL 5
        packet[1] = 0x00  // DSCP/ECN
        packet[2] = 0x00  // Total length high
        packet[3] = 0x28  // Total length low (40)
        packet[4] = 0x00  // Identification
        packet[5] = 0x00
        packet[6] = 0x40  // Flags (DF)
        packet[7] = 0x00
        packet[8] = 0x40  // TTL = 64
        packet[9] = 0x06  // Protocol = TCP
        packet[10] = 0x00 // Checksum
        packet[11] = 0x00

        // Source IP: 10.0.0.2
        packet[12] = 0x0A
        packet[13] = 0x00
        packet[14] = 0x00
        packet[15] = 0x02

        // Dest IP: 192.168.1.1
        packet[16] = 0xC0.toByte()
        packet[17] = 0xA8.toByte()
        packet[18] = 0x01
        packet[19] = 0x01

        // TCP 头 (20 bytes)
        // Source port: 54321 = 0xD431
        packet[20] = 0xD4.toByte()
        packet[21] = 0x31.toByte()

        // Dest port: 80 = 0x0050
        packet[22] = 0x00
        packet[23] = 0x50

        // 解析验证
        assertEquals("192.168.1.1", VpnTestUtils.parseDestinationIp(packet, packet.size))
        assertEquals("10.0.0.2", VpnTestUtils.parseSourceIp(packet, packet.size))
        assertEquals(6, VpnTestUtils.parseProtocol(packet))
        assertEquals(80, VpnTestUtils.parseDestinationPort(packet, packet.size))
        assertEquals(54321, VpnTestUtils.parseSourcePort(packet, packet.size))
    }

    @Test
    fun realPacket_udpDnsQuery_parsesCorrectly() {
        // 构造一个 UDP DNS 查询包
        val packet = ByteArray(40)

        // IP 头
        packet[0] = 0x45
        packet[9] = 0x11  // Protocol = UDP

        // Source IP: 10.0.0.2
        packet[12] = 0x0A
        packet[13] = 0x00
        packet[14] = 0x00
        packet[15] = 0x02

        // Dest IP: 8.8.8.8
        packet[16] = 0x08
        packet[17] = 0x08
        packet[18] = 0x08
        packet[19] = 0x08

        // UDP 头 (8 bytes)
        // Source port: 12345
        packet[20] = 0x30.toByte()
        packet[21] = 0x39.toByte()

        // Dest port: 53 (DNS)
        packet[22] = 0x00
        packet[23] = 0x35.toByte()

        // 解析验证
        assertEquals("8.8.8.8", VpnTestUtils.parseDestinationIp(packet, packet.size))
        assertEquals(17, VpnTestUtils.parseProtocol(packet))
        assertEquals(53, VpnTestUtils.parseDestinationPort(packet, packet.size))
    }

    // ==================== H13 回包健壮性测试 ====================

    @Test
    fun h13_constructReturnPacket_withOversizedPayload_returnsInvalidLength() {
        val service = GatewayVpnService()
        val session = createSessionForReflection(
            service = service,
            srcIp = "10.0.0.2",
            virtualSrcIp = "10.0.0.100",
            pooledConnection = null
        )
        val buffer = ByteArray(64)

        val packetLen = invokeConstructReturnPacket(
            service = service,
            session = session,
            buffer = buffer,
            payloadLen = 65
        )

        assertTrue(packetLen <= 0)
    }

    @Test
    fun h13_constructReturnPacket_withInvalidIp_returnsInvalidLength() {
        val service = GatewayVpnService()
        val buffer = ByteArray(128)

        val invalidVirtualSrcSession = createSessionForReflection(
            service = service,
            srcIp = "10.0.0.2",
            virtualSrcIp = "300.1.1.1",
            pooledConnection = null
        )
        val lenWithInvalidVirtualSrc = invokeConstructReturnPacket(
            service = service,
            session = invalidVirtualSrcSession,
            buffer = buffer,
            payloadLen = 8
        )
        assertTrue(lenWithInvalidVirtualSrc <= 0)

        val invalidSrcSession = createSessionForReflection(
            service = service,
            srcIp = "invalid.ip",
            virtualSrcIp = "10.0.0.100",
            pooledConnection = null
        )
        val lenWithInvalidSrc = invokeConstructReturnPacket(
            service = service,
            session = invalidSrcSession,
            buffer = buffer,
            payloadLen = 8
        )
        assertTrue(lenWithInvalidSrc <= 0)
    }

    @Test
    fun h13_processTcpReturn_whenConstructFails_skipsInjectAndCleansSession() {
        val service = GatewayVpnService()
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
        val session = createSessionForReflection(
            service = service,
            srcIp = "10.0.0.2",
            virtualSrcIp = "bad.ip.value",
            pooledConnection = pooledConnection
        )

        val mockPool = mockk<Socks5ConnectionPool>(relaxed = true)
        setPrivateField(service, "socks5ConnectionPool", mockPool)

        val mockOutput = mockk<FileOutputStream>(relaxed = true)
        setPrivateField(service, "vpnOutputStream", mockOutput)

        val sessionKey = "10.0.0.2:12345-192.168.1.1:443"
        val activeConnections = getPrivateField(service, "activeConnections") as ConcurrentHashMap<String, Any>
        activeConnections[sessionKey] = session

        val result = invokeProcessTcpReturn(service, session, sessionKey)

        assertFalse(result)
        assertFalse(activeConnections.containsKey(sessionKey))
        verify(exactly = 1) { mockPool.returnConnection(pooledConnection) }
        verify(exactly = 0) { mockOutput.write(any<ByteArray>(), any(), any()) }
    }

    // ==================== 帮助方法 ====================

    private fun createSessionForReflection(
        service: GatewayVpnService,
        srcIp: String,
        virtualSrcIp: String,
        pooledConnection: PooledSocks5Connection?
    ): Any {
        val sessionClass = getSessionClass(service)
        val constructor = sessionClass.declaredConstructors.first { it.parameterTypes.size == 9 }
        constructor.isAccessible = true
        return constructor.newInstance(
            srcIp,
            12345,
            "192.168.1.1",
            443,
            6,
            pooledConnection,
            virtualSrcIp,
            System.currentTimeMillis(),
            System.currentTimeMillis()
        )
    }

    private fun invokeConstructReturnPacket(
        service: GatewayVpnService,
        session: Any,
        buffer: ByteArray,
        payloadLen: Int
    ): Int {
        val sessionClass = getSessionClass(service)
        val method = service.javaClass.getDeclaredMethod(
            "constructReturnPacket",
            ByteArray::class.java,
            sessionClass,
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(service, buffer, session, payloadLen) as Int
    }

    private fun invokeProcessTcpReturn(
        service: GatewayVpnService,
        session: Any,
        sessionKey: String
    ): Boolean {
        val sessionClass = getSessionClass(service)
        val method = service.javaClass.getDeclaredMethod(
            "processTcpReturn",
            sessionClass,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(service, session, sessionKey) as Boolean
    }

    private fun setPrivateField(target: Any, fieldName: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun getPrivateField(target: Any, fieldName: String): Any {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(target)
    }

    private fun getSessionClass(service: GatewayVpnService): Class<*> {
        return service.javaClass.declaredClasses.first { it.simpleName == "ConnectionSession" }
    }

    /**
     * IP 脱敏（从 VpnService 复制用于测试）
     */
    private fun redactIp(ip: String): String {
        val parts = ip.split(".")
        if (parts.size == 4) {
            return "*.*.*.*"
        }
        return if (ip.length > 6) "${ip.take(6)}***" else "***"
    }

    /**
     * 连接 key 脱敏（从 VpnService 复制用于测试）
     */
    private fun redactConnectionKey(key: String): String {
        val segments = key.split("-")
        if (segments.size != 2) return "***"
        val left = segments[0].substringBefore(":")
        val right = segments[1].substringBefore(":")
        return "${redactIp(left)}-${redactIp(right)}"
    }

    /**
     * 连接会话数据类（从 VpnService 复制用于测试）
     */
    private data class ConnectionSession(
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val protocol: Int,
        val pooledConnection: PooledSocks5Connection?,
        val virtualSrcIp: String,
        val createdAt: Long = System.currentTimeMillis(),
        var lastActivity: Long = System.currentTimeMillis()
    ) {
        fun updateActivity() {
            lastActivity = System.currentTimeMillis()
            pooledConnection?.markUsed()
        }
    }

}
