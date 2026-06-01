package com.netproxy.gateway.vpn

import com.netproxy.gateway.result.AppResult

object VpnDnsConfig {

    private const val DNS_PORT = 53
    private const val PROTOCOL_TCP = 6
    private const val PROTOCOL_UDP = 17

    /**
     * 根据配置字符串解析并返回有效的 DNS 服务器列表，若解析结果为空则返回默认列表。
     *
     * @param configuredValue 以逗号分隔的 IPv4 地址字符串，可能为 `null` 或空白。
     * @param defaultDnsServers 当解析未产生有效 DNS 条目时使用的备用服务器列表。
     * @return 解析并去重后仅包含合法 IPv4 的服务器列表；如果该列表为空，则返回 `defaultDnsServers`。
     */
    fun resolveDnsServers(configuredValue: String?, defaultDnsServers: List<String>): List<String> {
        val configuredDnsServers = parseConfiguredDnsServers(configuredValue)
        return if (configuredDnsServers.isNotEmpty()) configuredDnsServers else defaultDnsServers
    }

    /**
     * 解析传入的逗号分隔 DNS 配置字符串，返回解析出的有效 IPv4 列表；若解析结果为空则回退到提供的默认列表，并以 `AppResult` 封装成功或错误。
     *
     * @param configuredValue 可能为 null 或逗号分隔的 DNS IPv4 字符串（每项可包含空格，函数会 trim 并过滤无效项）。
     * @param defaultDnsServers 当解析后无有效 DNS 时使用的回退 DNS 列表。
     * @return `AppResult.success` 包含解析后的非空去重 IPv4 列表或 `defaultDnsServers`（当解析结果为空）；`AppResult.error` 包含解析过程中抛出的异常。
     */
    fun resolveDnsServersWithResult(configuredValue: String?, defaultDnsServers: List<String>): AppResult<List<String>> {
        return try {
            val configuredDnsServers = parseConfiguredDnsServers(configuredValue)
            val result = if (configuredDnsServers.isNotEmpty()) configuredDnsServers else defaultDnsServers
            AppResult.success(result)
        } catch (e: Exception) {
            AppResult.error(e)
        }
    }

    /**
     * 判断给定目标是否应将该网络流量作为 DNS 请求通过 WiFi 路由。
     *
     * @param destinationIp 目标主机的 IP 地址（点分十进制 IPv4 字符串）。
     * @param protocol 传输层协议号（例如 TCP = 6，UDP = 17）。
     * @param destinationPort 目标端口号。
     * @param dnsServers 已配置的 DNS 服务器 IP 集合。
     * @return `true` 如果 destinationIp 在 dnsServers 中且 protocol 为 TCP 或 UDP 且 destinationPort 等于 53，`false` 否则。
     */
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

    /**
     * 判断给定目标是否应将 DNS 请求通过 WiFi 路由。
     *
     * @param destinationIp 目标 IP（点分十进制字符串）。
     * @param protocol 传输协议编号（例如使用常量 `PROTOCOL_TCP` 或 `PROTOCOL_UDP`）。
     * @param destinationPort 目标端口号。
     * @param dnsServers 可接受的 DNS 服务器 IP 集合（点分十进制字符串）。
     * @return `AppResult.success(true)` 当且仅当 `destinationIp` 位于 `dnsServers` 且 `protocol` 为 TCP 或 UDP 且 `destinationPort` 为 53；
     *         `AppResult.success(false)` 当上述条件不全部满足；`AppResult.error(e)` 当方法执行期间发生异常时。
     */
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

    /**
     * 将配置的逗号分隔字符串解析为去重的、仅包含合法 IPv4 地址的列表。
     *
     * 参数为 null 或仅包含空白字符时返回空列表。否则会按逗号拆分、去除首尾空白、过滤空项与不合法的 IPv4，再去重。
     *
     * @param configuredValue 可能包含以逗号分隔的 DNS 服务器地址的字符串（可为 null 或空白）。
     * @return 解析并去重后的 IPv4 地址列表；如果输入为 null 或空白则返回空列表。
     */
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

    /**
     * 验证给定字符串是否为合法的 IPv4 点分十进制表示。
     *
     * @param ip 要验证的 IPv4 地址字符串（例如 "192.168.0.1"）。
     * @return `true` 如果字符串表示四段点分十进制的 IPv4 地址且每段在 0 到 255 之间，`false` 否则。
     */
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

    /**
     * 解析配置的逗号分隔 DNS 字符串为去重的 IPv4 列表，并以 AppResult 封装结果。
     *
     * @param configuredValue 逗号分隔的 DNS IP 列表字符串，可能为 null 或空表示未配置。
     * @return `AppResult.success` 包含按逗号解析、去空项、仅保留合法 IPv4 并去重后的地址列表；如果输入为 null 或空则返回包含空列表的 `success`。若处理过程中发生异常则返回 `AppResult.error` 并封装该异常。
     */
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
