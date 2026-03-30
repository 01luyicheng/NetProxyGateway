package com.netproxy.gateway.vpn

import java.util.concurrent.atomic.AtomicInteger

object VpnTestUtils {
    fun parseDestinationIp(packet: ByteArray, length: Int): String? {
        if (length < 20) return null

        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return null

        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (length < headerLength || length < 20) return null

        return "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
    }

    fun parseSourceIp(packet: ByteArray, length: Int): String? {
        if (length < 20) return null
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return null

        return "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}.${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
    }

    fun parseProtocol(packet: ByteArray): Int {
        if (packet.size <= 9) return 0
        return packet[9].toInt() and 0xFF
    }

    fun parseDestinationPort(packet: ByteArray, length: Int): Int? {
        if (length < 20) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (length < headerLength + 4) return null

        return ((packet[headerLength + 2].toInt() and 0xFF) shl 8) or
            (packet[headerLength + 3].toInt() and 0xFF)
    }

    fun parseSourcePort(packet: ByteArray, length: Int): Int? {
        if (length < 20) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (length < headerLength + 2) return null

        return ((packet[headerLength].toInt() and 0xFF) shl 8) or
            (packet[headerLength + 1].toInt() and 0xFF)
    }

    fun isPrivateIp(ip: String): Boolean {
        return try {
            val octets = ip.split(".").map { it.toInt() }
            if (octets.size != 4 || octets.any { it !in 0..255 }) {
                return false
            }

            when {
                octets[0] == 10 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun extractTransportPayloadInfo(packet: ByteArray, length: Int): Pair<Int, Int>? {
        if (length < 20) return null

        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        val protocol = parseProtocol(packet)
        val transportHeaderLength = when (protocol) {
            6 -> {
                if (length < ipHeaderLength + 13) return null
                ((packet[ipHeaderLength + 12].toInt() shr 4) and 0x0F) * 4
            }
            17 -> 8
            else -> 0
        }

        val payloadStart = ipHeaderLength + transportHeaderLength
        if (payloadStart >= length) return null

        return Pair(payloadStart, length - payloadStart)
    }

    fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset

        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }

        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }

        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        return sum.inv() and 0xFFFF
    }

    fun calculateTcpChecksum(
        buffer: ByteArray,
        srcIp: List<Int>,
        dstIp: List<Int>,
        protocol: Int,
        tcpHeaderLen: Int,
        payloadLen: Int,
    ): Int {
        var sum = 0
        val ipHeaderLength = if (buffer.isNotEmpty()) {
            (buffer[0].toInt() and 0x0F) * 4
        } else {
            20
        }
        val tcpStartOffset = if (ipHeaderLength in 20..buffer.size) ipHeaderLength else 20
        val tcpSegmentLength = tcpHeaderLen + payloadLen

        sum += (srcIp[0] shl 8) or srcIp[1]
        sum += (srcIp[2] shl 8) or srcIp[3]
        sum += (dstIp[0] shl 8) or dstIp[1]
        sum += (dstIp[2] shl 8) or dstIp[3]
        sum += protocol
        sum += tcpSegmentLength

        for (i in tcpStartOffset until tcpStartOffset + tcpSegmentLength step 2) {
            if (i + 1 < buffer.size) {
                sum += ((buffer[i].toInt() and 0xFF) shl 8) or (buffer[i + 1].toInt() and 0xFF)
            } else if (i < buffer.size) {
                sum += (buffer[i].toInt() and 0xFF) shl 8
            }
        }

        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        return sum.inv() and 0xFFFF
    }

    fun getOrAllocateVirtualIp(
        realDstIp: String,
        virtualIpPool: MutableMap<String, String>,
        reverseIpMap: MutableMap<String, String>,
        nextVirtualIp: AtomicInteger,
    ): String {
        return virtualIpPool.getOrPut(realDstIp) {
            val ip = "10.0.0.${nextVirtualIp.getAndIncrement()}"
            reverseIpMap[ip] = realDstIp
            ip
        }
    }

    fun calculateIdleDelay(idleRounds: Int): Long {
        if (idleRounds == 0) return 1L
        val baseDelay = 2L
        val exponent = kotlin.math.min(idleRounds - 1, 6)
        return kotlin.math.min(baseDelay shl exponent, 100L)
    }
}
