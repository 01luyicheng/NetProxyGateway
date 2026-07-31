package com.netproxy.gateway.utils

import com.netproxy.gateway.result.AppResult
import com.netproxy.gateway.result.getOrDefault
import com.netproxy.gateway.result.map

object IpAddressUtils {
    fun isPrivateIpv4Rfc1918(ip: String): Boolean {
        return isPrivateIpv4Rfc1918WithResult(ip).getOrDefault(false)
    }

    fun validateIpv4WithResult(ip: String): AppResult<Boolean> {
        return parseIpv4Octets(ip).map { octets ->
            octets.size == 4 && octets.all { it in 0..255 }
        }
    }

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

    private fun parseIpv4Octets(ip: String): AppResult<List<Int>> {
        if (ip.isEmpty()) return AppResult.error(IllegalArgumentException("Empty string"))
        val octets = ArrayList<Int>(4)
        var start = 0
        var i = 0
        val len = ip.length

        while (i <= len) {
            if (i == len || ip[i] == '.') {
                if (start == i) return AppResult.error(IllegalArgumentException("Empty octet"))

                var isNegative = false
                var j = start
                if (ip[j] == '-') {
                    isNegative = true
                    j++
                    if (j == i) return AppResult.error(IllegalArgumentException("Just minus"))
                } else if (ip[j] == '+') {
                    j++
                    if (j == i) return AppResult.error(IllegalArgumentException("Just plus"))
                }

                val limit = if (isNegative) Int.MIN_VALUE else -Int.MAX_VALUE
                val multmin = limit / 10
                var result = 0

                while (j < i) {
                    val digit = ip[j] - '0'
                    if (digit !in 0..9) return AppResult.error(IllegalArgumentException("Not a digit"))
                    if (result < multmin) return AppResult.error(IllegalArgumentException("Overflow"))
                    result *= 10
                    if (result < limit + digit) return AppResult.error(IllegalArgumentException("Overflow"))
                    result -= digit
                    j++
                }
                val value = if (isNegative) result else -result

                octets.add(value)
                start = i + 1
            }
            i++
        }

        return AppResult.success(octets)
        }
}
