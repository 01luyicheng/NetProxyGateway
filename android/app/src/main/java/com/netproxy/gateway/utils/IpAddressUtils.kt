package com.netproxy.gateway.utils

import com.netproxy.gateway.result.AppResult
import com.netproxy.gateway.result.getOrDefault
import com.netproxy.gateway.result.map

object IpAddressUtils {
    fun isPrivateIpv4Rfc1918(ip: String): Boolean {
        return isPrivateIpv4Rfc1918WithResult(ip).getOrDefault(false)
    }

    fun validateIpv4WithResult(ip: String): AppResult<Boolean> {
        return try {
            val octets = ip.split(".").map { it.toInt() }
            val isValid = octets.size == 4 && octets.all { it in 0..255 }
            AppResult.success(isValid)
        } catch (e: Exception) {
            AppResult.error(e)
        }
    }

    fun isPrivateIpv4Rfc1918WithResult(ip: String): AppResult<Boolean> {
        return validateIpv4WithResult(ip).map { isValid ->
            if (!isValid) return@map false
            val octets = ip.split(".").map { it.toInt() }
            when {
                octets[0] == 10 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                else -> false
            }
        }
    }
}
