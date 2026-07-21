package com.netproxy.gateway.vpn

import io.mockk.mockk
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class ConnectionSessionManagerPerfTest {
    @Test
    fun benchmarkHandleDisconnectMessage() {
        val packetProcessor = mockk<VpnPacketProcessor>(relaxed = true)
        val activeConnections = ConcurrentHashMap<String, ConnectionSession>()
        val manager = ConnectionSessionManager(packetProcessor, activeConnections)
        val payload = """{"type":"disconnect","data":{"stream_id":"abc123"}}"""

        // Warmup
        repeat(1000) {
            manager.handleDisconnectMessage(payload)
        }

        val start = System.currentTimeMillis()
        repeat(100000) {
            manager.handleDisconnectMessage(payload)
        }
        val end = System.currentTimeMillis()
        println("BENCHMARK_RESULT: ${end - start} ms")
    }
}
