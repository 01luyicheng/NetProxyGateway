package com.netproxy.gateway.vpn

import java.net.Inet6Address
import java.net.InetAddress

private const val REDACTED_IPV4 = "*.*.*.*"
private const val REDACTED_IPV6 = "****:****:****:****:****:****:****:****"
private const val REDACTED_UNKNOWN = "***"

/**
 * 根据输入文本识别并用固定掩码替换 IPv4、IPv6 或其他未知格式的 IP。
 *
 * @param ip 原始输入字符串，可能包含要脱敏的 IP（函数会对其进行 trim() 处理）。
 * @return `REDACTED_IPV4` 如果输入为有效 IPv4，`REDACTED_IPV6` 如果为有效 IPv6，`REDACTED_UNKNOWN` 否则。
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

/**
 * 判断给定字符串是否为格式正确的 IPv4 地址表示。
 *
 * @return `true` 如果字符串由四个以点分隔的十进制段组成，且每个段非空、长度不超过 3、仅包含数字且其数值在 0 到 255 之间；`false` 否则。
 */
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

/**
 * 验证给定字符串是否表示有效的 IPv6 地址。
 *
 * 检查包括必须包含冒号、仅包含允许的 IPv6 字符（十六进制字符、冒号和点）并且可解析为 Inet6Address。
 *
 * @param ip 要验证的地址字符串（可能包含前后空白）。
 * @return `true` 如果字符串是有效且可解析为 IPv6 地址，`false` 否则。
 */
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
 * 对连接键的左右主机地址进行 IP 脱敏并按历史日志模板保留分隔格式。
 *
 * @param key 连接键字符串，期望包含且只有一个 `-` 将左右两部分分隔；每一部分在遇到 `:` 时只取其前缀作为主机地址进行脱敏（例如含端口的 `host:port`）。
 * @return 若输入格式不符合预期则返回 `***`；否则返回左右主机地址脱敏后的字符串，使用 `- `（短横加空格）作为分隔符。
 */
internal fun redactConnectionKey(key: String): String {
    val segments = key.split("-")
    if (segments.size != 2) return "***"
    val left = segments[0].substringBefore(":")
    val right = segments[1].substringBefore(":")
    // 保持历史日志模板分隔格式（"- "），避免影响既有日志解析与对比。
    return "${redactIp(left)}- ${redactIp(right)}"
}
