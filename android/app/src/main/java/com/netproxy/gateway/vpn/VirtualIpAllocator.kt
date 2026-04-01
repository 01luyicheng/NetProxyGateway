package com.netproxy.gateway.vpn

import java.util.concurrent.atomic.AtomicInteger

internal object VirtualIpAllocator {

    @Synchronized
    fun getOrAllocateVirtualIp(
        realDstIp: String,
        virtualIpPool: MutableMap<String, String>,
        reverseIpMap: MutableMap<String, String>,
        nextVirtualIp: AtomicInteger,
        onPoolReset: (() -> Unit)? = null,
    ): String {
        virtualIpPool[realDstIp]?.let { existingIp ->
            return existingIp
        }

        var ipNum = nextVirtualIp.getAndIncrement()
        if (ipNum > 254) {
            onPoolReset?.invoke()
            virtualIpPool.clear()
            reverseIpMap.clear()
            ipNum = 1
            nextVirtualIp.set(2)
        }

        val ip = "10.0.0.$ipNum"
        virtualIpPool[realDstIp] = ip
        reverseIpMap[ip] = realDstIp
        return ip
    }
}
