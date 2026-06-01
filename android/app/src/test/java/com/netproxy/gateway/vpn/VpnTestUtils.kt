package com.netproxy.gateway.vpn

import java.util.concurrent.atomic.AtomicInteger

object VpnTestUtils {
    /**
     * 从给定的 IPv4 数据包中提取目的 IPv4 地址的点分十进制表示。
     *
     * @param packet 包含 IP 数据报的字节数组（网络字节序）。
     * @param length packet 中实际有效的字节数，用于边界检查。
     * @return 目的 IPv4 地址的点分十进制字符串（例如 "192.168.0.1"），若数据包长度不足、不是 IPv4 或 IP 头不完整则返回 `null`。
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
     * 从 IPv4 数据包中提取源 IP 并以点分十进制字符串返回。
     *
     * @param packet 包含 IPv4 报文的字节数组。
     * @param length packet 中有效数据的长度；函数要求至少为 20 字节且报文版本为 IPv4，否则返回 `null`。
     * @return 源 IPv4 地址的点分十进制表示（例如 "192.168.0.1"），在包长度不足或报文非 IPv4 时返回 `null`。
     */
    fun parseSourceIp(packet: ByteArray, length: Int): String? {
        if (length < 20) return null
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return null

        return "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}.${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
    }

    /**
     * 从 IPv4 数据包字节数组中提取 IP 协议字段值。
     *
     * @param packet 包含 IPv4 报文的字节数组。
     * @return 协议号（0..255）；当输入长度不足以包含协议字段时返回 `0`。
     */
    fun parseProtocol(packet: ByteArray): Int {
        if (packet.size <= 9) return 0
        return packet[9].toInt() and 0xFF
    }

    /**
     * 从 IPv4 数据包中解析传输层（TCP/UDP）目的端口。
     *
     * 若提供的字节数组长度不足以包含完整的 IPv4 首部或传输层端口字段，则返回 `null`。
     *
     * @param packet 包含 IPv4 数据包的字节数组。
     * @param length packet 中有效数据的长度（字节数）。
     * @return 目的端口号（0 到 65535）或在数据不足时返回 `null`。
     */
    fun parseDestinationPort(packet: ByteArray, length: Int): Int? {
        if (length < 20) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (length < headerLength + 4) return null

        return ((packet[headerLength + 2].toInt() and 0xFF) shl 8) or
            (packet[headerLength + 3].toInt() and 0xFF)
    }

    /**
     * 从 IPv4 数据包中解析并返回源端口（如果可用）。
     *
     * 计算 IPv4 首部长度（packet[0] 低 4 位 * 4），并在可用数据至少包含该首部加 2 字节时读取源端口。
     *
     * @param packet 包含 IPv4 数据包字节的数组。
     * @param length packet 中有效数据的长度（字节数）。
     * @return 源端口号（0..65535）；如果数据长度不足以包含 IPv4 首部或源端口，则返回 `null`。
     */
    fun parseSourcePort(packet: ByteArray, length: Int): Int? {
        if (length < 20) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (length < headerLength + 2) return null

        return ((packet[headerLength].toInt() and 0xFF) shl 8) or
            (packet[headerLength + 1].toInt() and 0xFF)
    }

    /**
     * 判断给定 IPv4 地址字符串是否属于私有地址范围。
     *
     * @param ip 点分十进制表示的 IPv4 地址（例如 "192.168.0.1"）。
     * @return `true` 如果地址属于私有网段：10.0.0.0/8、172.16.0.0/12 或 192.168.0.0/16；`false` 如果不属于这些私有网段或输入格式无效。
     */
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

    /**
     * 从 IPv4 数据包中提取传输层载荷的起始偏移和长度。
     *
     * 如果包不是合法的 IPv4 包、头部长度或传输层头部不完整，或没有可用的传输层载荷则返回 `null`。
     * 对于 TCP，使用 TCP 报文头部中的 data offset 计算传输层头长；对于 UDP，传输层头长固定为 8；其它协议将传输层头长视为 0。
     *
     * @param packet 包字节数组（IPv4 数据包）。
     * @param length packet 中有效字节数（可能小于数组长度）。
     * @return 包含载荷起始偏移和载荷长度的 `Pair(startOffset, payloadLength)`，或在验证失败时返回 `null`。
     */
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

    /**
     * 对指定字节范围计算 16 位一补和校验和。
     *
     * 以大端顺序把数据按 16 位字组合计算校验和；若字节数为奇数，则把最后的单字节作为高位字节处理。
     *
     * @param data 要计算的字节数组。
     * @param offset 起始偏移（从 0 开始）。
     * @param length 要包含的字节长度。
     * @return 计算得到的 16 位一补和，范围为 0..0xFFFF。
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
     * 基于 IPv4 伪首部与给定 TCP 段（头部 + 载荷）计算 TCP 校验和。
     *
     * @param buffer 包含 IP 数据包及其后续字节的字节数组；可为空或仅包含部分数据。
     * @param srcIp 源 IPv4 地址的四个八位字节，按顺序 [a, b, c, d]，每项 0..255。
     * @param dstIp 目的 IPv4 地址的四个八位字节，按顺序 [a, b, c, d]，每项 0..255。
     * @param protocol IPv4 协议号（例如 TCP 为 6）。
     * @param tcpHeaderLen TCP 头部长度（字节数）。
     * @param payloadLen TCP 载荷长度（字节数）。
     * @return 计算得到的 16 位一补校验和，范围 0..0xFFFF。
     */
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

    private val testAllocator: VirtualIpAllocator = VirtualIpAllocatorImpl()

    /**
     * 为给定的真实目标 IP 获取对应的虚拟 IP；若尚未分配则分配一个新的虚拟 IP 并在映射中记录。
     *
     * @param realDstIp 目标真实 IP（点分十进制字符串）。
     * @param virtualIpPool 可变映射，键为虚拟 IP，值为对应的真实 IP；函数在分配时会向此映射写入条目。
     * @param reverseIpMap 可变反向映射，键为真实 IP，值为对应的虚拟 IP；函数在分配时会向此映射写入条目。
     * @param nextVirtualIp 用于生成下一个可用虚拟 IP 的原子计数器。
     * @return 分配或已存在的虚拟 IP（点分十进制字符串）。
     */
    fun getOrAllocateVirtualIp(
        realDstIp: String,
        virtualIpPool: MutableMap<String, String>,
        reverseIpMap: MutableMap<String, String>,
        nextVirtualIp: AtomicInteger,
    ): String {
        return testAllocator.getOrAllocateVirtualIp(
            realDstIp = realDstIp,
            virtualIpPool = virtualIpPool,
            reverseIpMap = reverseIpMap,
            nextVirtualIp = nextVirtualIp
        )
    }

    /**
     * 根据连续空闲轮次计算下一次等待的延迟（毫秒）。
     *
     * @param idleRounds 连续空闲的轮次数；0 表示首次调用或无空闲。
     * @return 计算出的延迟（毫秒），从 1 开始，按指数增长（以 2 为底），但不超过 100 毫秒。
     */
    fun calculateIdleDelay(idleRounds: Int): Long {
        if (idleRounds == 0) return 1L
        val baseDelay = 2L
        val exponent = kotlin.math.min(idleRounds - 1, 6)
        return kotlin.math.min(baseDelay shl exponent, 100L)
    }
}
