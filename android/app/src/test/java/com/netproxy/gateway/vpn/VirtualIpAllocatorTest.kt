package com.netproxy.gateway.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VirtualIpAllocatorTest {

    @Test
    fun boundary254_returns10_0_0_254() {
        val virtualIpPool = ConcurrentHashMap<String, String>()
        val reverseIpMap = ConcurrentHashMap<String, String>()
        val nextVirtualIp = AtomicInteger(254)

        val ip = VirtualIpAllocator.getOrAllocateVirtualIp(
            realDstIp = "8.8.8.8",
            virtualIpPool = virtualIpPool,
            reverseIpMap = reverseIpMap,
            nextVirtualIp = nextVirtualIp
        )

        assertEquals("10.0.0.254", ip)
        assertEquals("8.8.8.8", reverseIpMap["10.0.0.254"])
        assertEquals(255, nextVirtualIp.get())
    }

    @Test
    fun overflow255_resetsAndNeverReturnsBroadcastIp() {
        val virtualIpPool = ConcurrentHashMap<String, String>()
        val reverseIpMap = ConcurrentHashMap<String, String>()
        val nextVirtualIp = AtomicInteger(255)

        virtualIpPool["1.1.1.1"] = "10.0.0.1"
        reverseIpMap["10.0.0.1"] = "1.1.1.1"

        val ip = VirtualIpAllocator.getOrAllocateVirtualIp(
            realDstIp = "8.8.8.8",
            virtualIpPool = virtualIpPool,
            reverseIpMap = reverseIpMap,
            nextVirtualIp = nextVirtualIp
        )

        assertEquals("10.0.0.1", ip)
        assertFalse("10.0.0.255" == ip)
        assertEquals(1, virtualIpPool.size)
        assertEquals("10.0.0.1", virtualIpPool["8.8.8.8"])
        assertEquals("8.8.8.8", reverseIpMap["10.0.0.1"])
        assertEquals(2, nextVirtualIp.get())
    }

    @Test
    fun concurrentAllocation_neverReturnsBroadcastIp() {
        val virtualIpPool = ConcurrentHashMap<String, String>()
        val reverseIpMap = ConcurrentHashMap<String, String>()
        val nextVirtualIp = AtomicInteger(254)

        val executor = Executors.newFixedThreadPool(8)
        val doneLatch = CountDownLatch(16)
        val results = ConcurrentHashMap.newKeySet<String>()

        repeat(16) { index ->
            executor.submit {
                try {
                    val ip = VirtualIpAllocator.getOrAllocateVirtualIp(
                        realDstIp = "192.168.1.$index",
                        virtualIpPool = virtualIpPool,
                        reverseIpMap = reverseIpMap,
                        nextVirtualIp = nextVirtualIp
                    )
                    results.add(ip)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertFalse(results.contains("10.0.0.255"))
        assertTrue(results.all { it.startsWith("10.0.0.") })
    }

    @Test
    fun concurrentResetDuringOverflow_maintainsConsistency() {
        val virtualIpPool = ConcurrentHashMap<String, String>()
        val reverseIpMap = ConcurrentHashMap<String, String>()
        val nextVirtualIp = AtomicInteger(253)

        val executor = Executors.newFixedThreadPool(16)
        val doneLatch = CountDownLatch(32)
        val results = ConcurrentHashMap.newKeySet<String>()
        val resetCount = AtomicInteger(0)

        repeat(32) { index ->
            executor.submit {
                try {
                    val ip = VirtualIpAllocator.getOrAllocateVirtualIp(
                        realDstIp = "10.0.0.$index",
                        virtualIpPool = virtualIpPool,
                        reverseIpMap = reverseIpMap,
                        nextVirtualIp = nextVirtualIp,
                        onPoolReset = { resetCount.incrementAndGet() }
                    )
                    synchronized(results) {
                        results.add(ip)
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertFalse("Should never return broadcast IP", results.contains("10.0.0.255"))
        assertTrue("All IPs should be in 10.0.0.x format", results.all { it.startsWith("10.0.0.") })

        val uniqueIps = results.filter { it != "10.0.0.1" }
        assertTrue("Should have allocated valid IPs", uniqueIps.isNotEmpty())

        assertTrue("nextVirtualIp should be >= 2 after concurrent allocations", nextVirtualIp.get() >= 2)
        assertEquals("Pool reset should be called exactly once", 1, resetCount.get())
    }
}
