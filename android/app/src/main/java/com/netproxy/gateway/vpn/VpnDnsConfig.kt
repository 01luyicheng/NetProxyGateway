package com.netproxy.gateway.vpn

import com.netproxy.gateway.result.AppResult

object VpnDnsConfig {

    private const val DNS_PORT = 53
    private const val PROTOCOL_TCP = 6
    private const val PROTOCOL_UDP = 17

    fun resolveDnsServers(configuredValue: String?, defaultDnsServers: List<String>): List<String> {
        val configuredDnsServers = parseConfiguredDnsServers(configuredValue)
        return if (configuredDnsServers.isNotEmpty()) configuredDnsServers else defaultDnsServers
    }

    fun resolveDnsServersWithResult(configuredValue: String?, defaultDnsServers: List<String>): AppResult<List<String>> {
        return try {
            val configuredDnsServers = parseConfiguredDnsServers(configuredValue)
            val result = if (configuredDnsServers.isNotEmpty()) configuredDnsServers else defaultDnsServers
            AppResult.success(result)
        } catch (e: Exception) {
            AppResult.error(e)
        }
    }

    fun shouldRouteDnsViaWifi(
        destinationIp: String,
        protocol: Int,
        destinationPort: Int,
        dnsServers: Set<String>
    ): Boolean {
        val isDnsServer = destinationIp in dnsServers
        val isDnsTransportProtocol = protocol == PROTOCOL_UDP || protocol == PROTOCOL_TCP
        val isDnsPort = destinationPort == DNS_PORT
        return isDnsServer && isDnsTransportProtocol && isDnsPort
    }

    fun shouldRouteDnsViaWifiWithResult(
        destinationIp: String,
        protocol: Int,
        destinationPort: Int,
        dnsServers: Set<String>
    ): AppResult<Boolean> {
        return try {
            val isDnsServer = destinationIp in dnsServers
            val isDnsTransportProtocol = protocol == PROTOCOL_UDP || protocol == PROTOCOL_TCP
            val isDnsPort = destinationPort == DNS_PORT
            AppResult.success(isDnsServer && isDnsTransportProtocol && isDnsPort)
        } catch (e: Exception) {
            AppResult.error(e)
        }
    }

    private fun parseConfiguredDnsServers(configuredValue: String?): List<String> {
        if (configuredValue.isNullOrBlank()) {
            return emptyList()
        }

        return configuredValue
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { isValidIpv4(it) }
            .distinct()
    }

    private fun isValidIpv4(ip: String): Boolean {
        val octets = ip.split(".")
        if (octets.size != 4) {
            return false
        }

        return octets.all { octet ->
            if (octet.isEmpty() || octet.length > 3) {
                return@all false
            }
            if (!octet.all { it.isDigit() }) {
                return@all false
            }
            octet.toIntOrNull() in 0..255
        }
    }

    fun parseConfiguredDnsServersWithResult(configuredValue: String?): AppResult<List<String>> {
        return try {
            if (configuredValue.isNullOrBlank()) {
                return AppResult.success(emptyList())
            }

            val servers = configuredValue
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { isValidIpv4(it) }
                .distinct()
            AppResult.success(servers)
        } catch (e: Exception) {
            AppResult.error(e)
        }
    }
}
