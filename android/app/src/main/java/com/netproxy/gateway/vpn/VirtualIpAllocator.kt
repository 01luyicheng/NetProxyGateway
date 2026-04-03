package com.netproxy.gateway.vpn

import java.util.concurrent.atomic.AtomicInteger

internal object VirtualIpAllocator {

    fun getOrAllocateVirtualIp(
        realDstIp: String,
        virtualIpPool: MutableMap<String, String>,
        reverseIpMap: MutableMap<String, String>,
        nextVirtualIp: AtomicInteger,
        onPoolReset: (() -> Unit)? = null,
        onAllocated: ((String, String) -> Unit)? = null,
    ): String {
        virtualIpPool[realDstIp]?.let { existingIp ->
            return existingIp
        }

        var ipNum: Int
        synchronized(this) {
            ipNum = nextVirtualIp.getAndIncrement()
            if (ipNum > 254) {
                onPoolReset?.invoke()
                virtualIpPool.clear()
                reverseIpMap.clear()
                nextVirtualIp.set(2)
                ipNum = 1
            }
        }

        val ip = "10.0.0.$ipNum"
        virtualIpPool[realDstIp] = ip
        reverseIpMap[ip] = realDstIp
        onAllocated?.invoke(ip, realDstIp)
        return ip
    }
}
