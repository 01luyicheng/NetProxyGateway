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
        return try {
            val octets = ip.split(".").map { it.toInt() }
            AppResult.success(octets)
        } catch (e: Exception) {
            AppResult.error(e)
        }
    }
}
