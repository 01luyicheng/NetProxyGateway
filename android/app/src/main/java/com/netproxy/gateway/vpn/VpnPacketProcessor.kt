package com.netproxy.gateway.vpn

import kotlin.math.min

/**
 * VPN 数据包处理工具类
 * 纯逻辑，与 Android Service 生命周期无关
 */
internal class VpnPacketProcessor {

    companion object {
        private const val IP_VERSION_IHL = 0x45
        private const val IP_FLAG_DF = 0x40
        private const val IP_DEFAULT_TTL = 64
        private const val IP_HEADER_LEN = 20

        private const val TCP_HEADER_LEN = 20
        private const val TCP_DATA_OFFSET = (5 shl 4)
        private const val TCP_WINDOW_SIZE = 8192

        private const val UDP_HEADER_LEN = 8
        private const val PROTOCOL_TCP = 6
        private const val PROTOCOL_UDP = 17
    }

    /**
     * 解析 IP 包中的目标 IP 地址
     */
    fun parseDestinationIp(packet: ByteArray, length: Int): String? {
        if (length < 20) return null

        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return null

        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (length < headerLength || length < 20) return null

        return "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
    }

    /**
     * 解析源 IP 地址
     */
    fun parseSourceIp(packet: ByteArray, length: Int): String? {
        if (length < 20) return null
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return null
        return "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}.${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
    }

    /**
     * 解析协议号
     */
    fun parseProtocol(packet: ByteArray): Int {
        if (packet.size < 10) return 0
        return packet[9].toInt() and 0xFF
    }

    /**
     * 解析目标端口
     */
    fun parseDestinationPort(packet: ByteArray, length: Int): Int? {
        if (length < 20) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (length < headerLength + 4) return null
        return ((packet[headerLength + 2].toInt() and 0xFF) shl 8) or (packet[headerLength + 3].toInt() and 0xFF)
    }

    /**
     * 解析源端口
     */
    fun parseSourcePort(packet: ByteArray, length: Int): Int? {
        if (length < 20) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (length < headerLength + 2) return null
        return ((packet[headerLength].toInt() and 0xFF) shl 8) or (packet[headerLength + 1].toInt() and 0xFF)
    }

    /**
     * 提取传输层 payload 信息
     * @return Pair<起始位置, 长度>，如果无 payload 返回 null
     */
    fun extractTransportPayloadInfo(packet: ByteArray, length: Int): Pair<Int, Int>? {
        if (length < 20) return null
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        val protocol = parseProtocol(packet)
        val transportHeaderLength = when (protocol) {
            PROTOCOL_TCP -> {
                if (length < ipHeaderLength + 13) return null
                ((packet[ipHeaderLength + 12].toInt() shr 4) and 0x0F) * 4
            }
            PROTOCOL_UDP -> UDP_HEADER_LEN
            else -> 0
        }
        val payloadStart = ipHeaderLength + transportHeaderLength
        if (payloadStart >= length) return null
        return Pair(payloadStart, length - payloadStart)
    }

    /**
     * 构造回包（IP头 + TCP头 + payload）
     * H14: 支持 0 长度控制包，使用会话中的 seq/ack 和 TCP 状态标志位
     * @param tcpFlags 显式指定 TCP 标志位；为 0 时根据 session.tcpState 自动解析
     * @return 完整包长度
     */
    fun constructReturnPacket(
        buffer: ByteArray,
        session: ConnectionSession,
        payloadLen: Int,
        tcpFlags: Int = 0
    ): Int {
        val ipHeaderLen = IP_HEADER_LEN
        val tcpHeaderLen = TCP_HEADER_LEN
        if (payloadLen < 0) {
            return 0
        }
        val maxPayloadLen = buffer.size - ipHeaderLen - tcpHeaderLen
        if (payloadLen > maxPayloadLen) {
            return 0
        }
        val totalLen = ipHeaderLen + tcpHeaderLen + payloadLen
        if (totalLen > buffer.size) {
            return 0
        }

        val srcIpParts = parseIpv4Parts(session.virtualSrcIp) ?: return 0
        val dstIpParts = parseIpv4Parts(session.srcIp) ?: return 0

        // 确定 TCP 标志位
        val flags = if (tcpFlags != 0) tcpFlags else session.resolveTcpFlags()

        // 构造IP头（从虚拟源IP到原始源IP）
        buffer[0] = IP_VERSION_IHL.toByte() // IPv4, IHL=5
        buffer[1] = 0 // DSCP/ECN
        buffer[2] = (totalLen shr 8).toByte()
        buffer[3] = (totalLen and 0xFF).toByte()
        buffer[4] = 0 // Identification
        buffer[5] = 0
        buffer[6] = IP_FLAG_DF.toByte() // DF标志
        buffer[7] = 0
        buffer[8] = IP_DEFAULT_TTL.toByte() // TTL
        buffer[9] = session.protocol.toByte()
        buffer[10] = 0 // Header checksum (稍后计算)
        buffer[11] = 0

        // 源IP（虚拟IP）
        buffer[12] = srcIpParts[0].toByte()
        buffer[13] = srcIpParts[1].toByte()
        buffer[14] = srcIpParts[2].toByte()
        buffer[15] = srcIpParts[3].toByte()

        // 目标IP（原始源IP）
        buffer[16] = dstIpParts[0].toByte()
        buffer[17] = dstIpParts[1].toByte()
        buffer[18] = dstIpParts[2].toByte()
        buffer[19] = dstIpParts[3].toByte()

        // 计算IP头校验和
        val ipChecksum = calculateChecksum(buffer, 0, ipHeaderLen)
        buffer[10] = (ipChecksum shr 8).toByte()
        buffer[11] = (ipChecksum and 0xFF).toByte()

        // 构造TCP头
        buffer[20] = (session.dstPort shr 8).toByte() // 源端口（原始目标端口）
        buffer[21] = (session.dstPort and 0xFF).toByte()
        buffer[22] = (session.srcPort shr 8).toByte() // 目标端口（原始源端口）
        buffer[23] = (session.srcPort and 0xFF).toByte()

        // Seq number（使用会话中的 seqNum）
        val seq = session.seqNum
        buffer[24] = (seq shr 24).toByte()
        buffer[25] = (seq shr 16).toByte()
        buffer[26] = (seq shr 8).toByte()
        buffer[27] = (seq and 0xFF).toByte()

        // Ack number（使用会话中的 ackNum）
        val ack = session.ackNum
        buffer[28] = (ack shr 24).toByte()
        buffer[29] = (ack shr 16).toByte()
        buffer[30] = (ack shr 8).toByte()
        buffer[31] = (ack and 0xFF).toByte()

        buffer[32] = TCP_DATA_OFFSET.toByte() // Data offset = 5
        buffer[33] = flags.toByte()
        buffer[34] = (TCP_WINDOW_SIZE shr 8).toByte() // Window size
        buffer[35] = (TCP_WINDOW_SIZE and 0xFF).toByte()
        buffer[36] = 0 // TCP checksum (稍后计算)
        buffer[37] = 0
        buffer[38] = 0 // Urgent pointer
        buffer[39] = 0

        // 计算TCP校验和（伪头 + TCP头 + payload）
        val tcpChecksum = calculateTcpChecksum(buffer, srcIpParts, dstIpParts, session.protocol, tcpHeaderLen, payloadLen)
        buffer[36] = (tcpChecksum shr 8).toByte()
        buffer[37] = (tcpChecksum and 0xFF).toByte()

        return totalLen
    }

    /**
     * 计算IP校验和
     */
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

    /**
     * 计算TCP校验和（包含伪头）
     */
    fun calculateTcpChecksum(
        buffer: ByteArray,
        srcIp: List<Int>,
        dstIp: List<Int>,
        protocol: Int,
        tcpHeaderLen: Int,
        payloadLen: Int
    ): Int {
        var sum = 0

        // 从 IP 头解析实际头长度，支持 IPv4 options
        val ipHeaderLen = (buffer[0].toInt() and 0x0F) * 4

        // 伪头
        sum += (srcIp[0] shl 8) or srcIp[1]
        sum += (srcIp[2] shl 8) or srcIp[3]
        sum += (dstIp[0] shl 8) or dstIp[1]
        sum += (dstIp[2] shl 8) or dstIp[3]
        sum += protocol
        sum += tcpHeaderLen + payloadLen

        // TCP头和payload
        // 使用 endLimit = min(ipHeaderLen + tcpHeaderLen + payloadLen, buffer.size) 作为循环上界，
        // 确保不会读取 TCP 段之外的字节。当 tcpHeaderLen + payloadLen 为奇数时，
        // 最后一个字节通过零填充（shl 8）单独处理，而不是与段外的下一字节组合。
        // （REV59 修复：PR #108 重写为 `for (i in ... step 2)` + `i + 1 < buffer.size` 缓冲区边界判断，
        // 当段长为奇数且 buffer.size > segment_end 时，最后一次迭代会读取 buffer[i+1]，
        // 该字节位于 TCP 段之外，会污染校验和。）
        val endLimit = min(ipHeaderLen + tcpHeaderLen + payloadLen, buffer.size)
        var i = ipHeaderLen
        while (i < endLimit - 1) {
            sum += ((buffer[i].toInt() and 0xFF) shl 8) or (buffer[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < endLimit) {
            sum += (buffer[i].toInt() and 0xFF) shl 8
        }

        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun parseIpv4Parts(ip: String): List<Int>? {
        val parts = ip.split(".")
        if (parts.size != 4) {
            return null
        }
        return parts.map { part ->
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) {
                return null
            }
            value
        }
    }
}
