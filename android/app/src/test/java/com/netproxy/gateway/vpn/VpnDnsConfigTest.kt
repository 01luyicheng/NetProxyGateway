package com.netproxy.gateway.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnDnsConfigTest {

    @Test
    fun resolveDnsServers_usesConfiguredDns_whenConfigContainsValidIpv4() {
        val result = VpnDnsConfig.resolveDnsServers(
            configuredValue = " 1.1.1.1,8.8.8.8,invalid, 300.1.1.1,1.1.1.1 ",
            defaultDnsServers = listOf("8.8.8.8", "8.8.4.4")
        )

        assertEquals(listOf("1.1.1.1", "8.8.8.8"), result)
    }

    @Test
    fun resolveDnsServers_fallsBackToDefault_whenConfigMissingOrNoValidIpv4() {
        val defaults = listOf("8.8.8.8", "8.8.4.4")

        assertEquals(defaults, VpnDnsConfig.resolveDnsServers(null, defaults))
        assertEquals(defaults, VpnDnsConfig.resolveDnsServers(" , ,not-an-ip", defaults))
    }

    @Test
    fun shouldRouteDnsViaWifi_returnsFalse_whenDnsIpMatchesButPortIsNot53() {
        val result = VpnDnsConfig.shouldRouteDnsViaWifi(
            destinationIp = "8.8.8.8",
            protocol = 17,
            destinationPort = 5353,
            dnsServers = setOf("8.8.8.8", "1.1.1.1")
        )

        assertEquals(false, result)
    }

    @Test
    fun shouldRouteDnsViaWifi_returnsTrue_whenDnsIpMatchesAndUdp53() {
        val result = VpnDnsConfig.shouldRouteDnsViaWifi(
            destinationIp = "8.8.8.8",
            protocol = 17,
            destinationPort = 53,
            dnsServers = setOf("8.8.8.8", "1.1.1.1")
        )

        assertEquals(true, result)
    }

    @Test
    fun shouldRouteDnsViaWifi_returnsFalse_whenIpIsNotDnsEvenIfPort53() {
        val result = VpnDnsConfig.shouldRouteDnsViaWifi(
            destinationIp = "9.9.9.9",
            protocol = 6,
            destinationPort = 53,
            dnsServers = setOf("8.8.8.8", "1.1.1.1")
        )

        assertEquals(false, result)
    }
}
