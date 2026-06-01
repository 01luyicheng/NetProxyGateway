package com.netproxy.gateway.utils

import com.netproxy.gateway.result.AppResult
import com.netproxy.gateway.result.getOrDefault
import com.netproxy.gateway.result.map

object IpAddressUtils {
    /**
     * 判断给定 IPv4 字符串是否属于 RFC1918 私有地址范围。
     *
     * @param ip 以点分十进制表示的 IPv4 地址字符串。
     * @return `true` 如果地址属于 RFC1918 私有网段（10.0.0.0/8、172.16.0.0/12 或 192.168.0.0/16），`false` 否则或在解析失败时返回 `false`。
     */
    fun isPrivateIpv4Rfc1918(ip: String): Boolean {
        return isPrivateIpv4Rfc1918WithResult(ip).getOrDefault(false)
    }

    /**
     * 验证字符串是否为有效的 IPv4 地址（由四个取值在 0 到 255 之间的八位组组成）。
     *
     * @param ip 要校验的点分十进制 IPv4 地址字符串（例如 "192.168.0.1"）。
     * @return `AppResult` 包含 `true` 表示是有效的 IPv4 地址，`false` 表示不是；若在解析为整数列表时发生错误则返回 `AppResult.error`。
     */
    fun validateIpv4WithResult(ip: String): AppResult<Boolean> {
        return parseIpv4Octets(ip).map { octets ->
            octets.size == 4 && octets.all { it in 0..255 }
        }
    }

    /**
     * 判断给定 IPv4 字符串是否属于 RFC1918 定义的私有网段。
     *
     * @param ip 要检测的 IPv4 地址字符串（点分十进制形式）。
     * @return 包含布尔结果的 `AppResult`：`true` 表示地址为 RFC1918 私有网段（10.0.0.0/8、172.16.0.0/12、192.168.0.0/16）且为有效的 IPv4；`false` 表示地址不是上述私有网段或不是有效的 IPv4。若地址解析失败则返回 `AppResult.error`。
     */
    fun isPrivateIpv4Rfc1918WithResult(ip: String): AppResult<Boolean> {
        return parseIpv4Octets(ip).map { octets ->
            if (octets.size != 4 || octets.any { it !in 0..255 }) return@map false
            when {
                octets[0] == 10 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                else -> false
            }
        }
    }

    /**
     * 将 IPv4 地址字符串按 `.` 分割并将各段解析为整数列表。
     *
     * @param ip 待解析的 IPv4 地址字符串（例如 "192.168.0.1"）。
     * @return 成功时包含按原始顺序解析出的整数列表；解析或转换失败时包含导致失败的异常。
     */
    private fun parseIpv4Octets(ip: String): AppResult<List<Int>> {
        return try {
            val octets = ip.split(".").map { it.toInt() }
            AppResult.success(octets)
        } catch (e: Exception) {
            AppResult.error(e)
        }
    }
}
