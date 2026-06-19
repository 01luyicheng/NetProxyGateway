package com.netproxy.gateway.vpn

import java.net.Inet6Address
import java.net.InetAddress

private const val REDACTED_IPV4 = "*.*.*.*"
private const val REDACTED_IPV6 = "****:****:****:****:****:****:****:****"
private const val REDACTED_UNKNOWN = "***"

// IPv4 validation constants
private const val IPV4_PART_COUNT = 4
private const val IPV4_MAX_PART_LENGTH = 3
private const val IPV4_MIN_VALUE = 0
private const val IPV4_MAX_VALUE = 255

// Connection key redaction constants
private const val CONNECTION_KEY_SEGMENTS = 2
private const val REDACT_MASK = "***"

/**
 * Redacts an IP address for safe logging.
 *
 * Validates the input as an IPv4 or IPv6 address and returns a fully redacted
 * placeholder string. If the input is not a valid IP address, returns a generic
 * redaction mask.
 *
 * @param ip The IP address string to redact. May contain leading/trailing whitespace.
 * @return A redacted string placeholder. One of:
 *         - "*.*.*.*" for valid IPv4 addresses
 *         - "****:****:****:****:****:****:****:****" for valid IPv6 addresses
 *         - "***" for invalid or unrecognized input
 */
internal fun redactIp(ip: String): String {
    val candidate = ip.trim()
    if (isValidIpv4(candidate)) {
        return REDACTED_IPV4
    }
    if (isValidIpv6(candidate)) {
        return REDACTED_IPV6
    }
    return REDACTED_UNKNOWN
}

private fun isValidIpv4(ip: String): Boolean {
    val parts = ip.split(".")
    if (parts.size != IPV4_PART_COUNT) {
        return false
    }

    return parts.all { part ->
        part.isNotEmpty() &&
            part.length <= IPV4_MAX_PART_LENGTH &&
            part.all { it.isDigit() } &&
            part.toIntOrNull()?.let { value -> value in IPV4_MIN_VALUE..IPV4_MAX_VALUE } == true
    }
}

private fun isValidIpv6(ip: String): Boolean {
    if (!ip.contains(':')) {
        return false
    }

    if (!ip.all { ch ->
            ch.isDigit() ||
                ch in 'a'..'f' ||
                ch in 'A'..'F' ||
                ch == ':' ||
                ch == '.'
        }) {
        return false
    }

    return try {
        InetAddress.getByName(ip) is Inet6Address
    } catch (_: Exception) {
        false
    }
}

/**
 * Redacts a connection key for safe logging.
 *
 * A connection key is expected to be in the format "srcIp:srcPort-dstIp:dstPort".
 * This function extracts the IP portions, redacts them, and preserves the
 * historical separator format ("- ") to avoid breaking existing log parsers.
 *
 * @param key The connection key string to redact. Expected format: "ip:port-ip:port".
 * @return A redacted connection key with IP addresses masked. If the input
 *         does not contain exactly two segments separated by '-', returns "***".
 */
internal fun redactConnectionKey(key: String): String {
    val segments = key.split("-")
    if (segments.size != CONNECTION_KEY_SEGMENTS) return REDACT_MASK
    val left = segments[0].substringBefore(":")
    val right = segments[1].substringBefore(":")
    // 保持历史日志模板分隔格式（"- "），避免影响既有日志解析与对比。
    return "${redactIp(left)}- ${redactIp(right)}"
}
