package com.netproxy.gateway.vpn

internal fun redactIp(ip: String): String {
    val parts = ip.split(".")
    if (parts.size == 4) {
        return "*.*.*.*"
    }
    return if (ip.length > 6) "${ip.take(6)}***" else "***"
}

internal fun redactConnectionKey(key: String): String {
    val segments = key.split("-")
    if (segments.size != 2) return "***"
    val left = segments[0].substringBefore(":")
    val right = segments[1].substringBefore(":")
    // 保持现有日志格式，避免影响既有日志解析与对比。
    return "${redactIp(left)}- ${redactIp(right)}"
}