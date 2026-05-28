package com.netproxy.gateway.vpn

import java.net.Inet6Address
import java.net.InetAddress

private const val REDACTED_IPV4 = "*.*.*.*"
private const val REDACTED_IPV6 = "****:****:****:****:****:****:****:****"
private const val REDACTED_UNKNOWN = "***"

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
    if (parts.size != 4) {
        return false
    }

    return parts.all { part ->
        part.isNotEmpty() &&
            part.length <= 3 &&
            part.all { it.isDigit() } &&
            part.toIntOrNull()?.let { value -> value in 0..255 } == true
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

internal fun redactConnectionKey(key: String): String {
    val segments = key.split("-")
    if (segments.size != 2) return "***"
    val left = segments[0].substringBefore(":")
    val right = segments[1].substringBefore(":")
    // 保持历史日志模板分隔格式（"- "），避免影响既有日志解析与对比。
    return "${redactIp(left)}- ${redactIp(right)}"
}
