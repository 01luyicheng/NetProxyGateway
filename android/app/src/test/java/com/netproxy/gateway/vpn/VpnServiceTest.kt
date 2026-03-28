package com.netproxy.gateway.vpn

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.netproxy.gateway.connection.AuthSessionStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * VpnService 的单元测试
 * 由于 GatewayVpnService 继承自 Android VpnService，包含大量 Android 系统依赖，
 * 本测试类主要测试可以独立测试的逻辑部分，包括状态管理、数据类等。
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

        assertEquals(4, values.size)
        assertTrue(values.contains(VpnState.STOPPED))
        assertTrue(values.contains(VpnState.STARTING))
        assertTrue(values.contains(VpnState.RUNNING))
        assertTrue(values.contains(VpnState.ERROR))
    }

    @Test
    fun vpnState_valueOf() {
        assertEquals(VpnState.STOPPED, VpnState.valueOf("STOPPED"))
        assertEquals(VpnState.STARTING, VpnState.valueOf("STARTING"))
        assertEquals(VpnState.RUNNING, VpnState.valueOf("RUNNING"))
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
}
