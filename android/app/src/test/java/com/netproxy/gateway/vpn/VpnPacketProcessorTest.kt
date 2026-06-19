package com.netproxy.gateway.vpn

import com.netproxy.gateway.proxy.PooledSocks5Connection
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket

/**
 * VpnPacketProcessor 单元测试
 * 测试纯数据包处理逻辑，无需反射或 Android Service 实例化
 */
class VpnPacketProcessorTest {

    private lateinit var processor: VpnPacketProcessor

    @Before
    fun setup() {
        processor = VpnPacketProcessor()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== 目标 IP 解析测试 ====================

    @Test
    fun parseDestinationIp_validIpv4Packet() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[16] = 192.toByte()
        packet[17] = 168.toByte()
        packet[18] = 1.toByte()
        packet[19] = 1.toByte()

        val result = processor.parseDestinationIp(packet, packet.size)
        assertEquals("192.168.1.1", result)
    }

    @Test
    fun parseDestinationIp_publicIp() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[16] = 8.toByte()
        packet[17] = 8.toByte()
        packet[18] = 8.toByte()
        packet[19] = 8.toByte()

        val result = processor.parseDestinationIp(packet, packet.size)
        assertEquals("8.8.8.8", result)
    }

    @Test
    fun parseDestinationIp_invalidVersion() {
        val packet = ByteArray(40)
        packet[0] = 0x60

        val result = processor.parseDestinationIp(packet, packet.size)
        assertNull(result)
    }

    @Test
    fun parseDestinationIp_packetTooShort() {
        val packet = ByteArray(10)
        val result = processor.parseDestinationIp(packet, packet.size)
        assertNull(result)
    }

    @Test
    fun parseDestinationIp_exactly20Bytes() {
        val packet = ByteArray(20)
        packet[0] = 0x45
        packet[16] = 1.toByte()
        packet[17] = 2.toByte()
        packet[18] = 3.toByte()
        packet[19] = 4.toByte()

        val result = processor.parseDestinationIp(packet, packet.size)
        assertEquals("1.2.3.4", result)
    }

    @Test
    fun parseDestinationIp_withOptionsHeader() {
        val packet = ByteArray(40)
        packet[0] = 0x46
        packet[16] = 10.toByte()
        packet[17] = 0.toByte()
        packet[18] = 0.toByte()
        packet[19] = 1.toByte()

        val result = processor.parseDestinationIp(packet, packet.size)
        assertEquals("10.0.0.1", result)
    }

    @Test
    fun parseDestinationIp_maximumValues() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[16] = 0xFF.toByte()
        packet[17] = 0xFF.toByte()
        packet[18] = 0xFF.toByte()
        packet[19] = 0xFF.toByte()

        val result = processor.parseDestinationIp(packet, packet.size)
        assertEquals("255.255.255.255", result)
    }

    // ==================== 源 IP 解析测试 ====================

    @Test
    fun parseSourceIp_validIpv4Packet() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[12] = 10.toByte()
        packet[13] = 0.toByte()
        packet[14] = 0.toByte()
        packet[15] = 1.toByte()

        val result = processor.parseSourceIp(packet, packet.size)
        assertEquals("10.0.0.1", result)
    }

    @Test
    fun parseSourceIp_invalidVersion() {
        val packet = ByteArray(40)
        packet[0] = 0x60

        val result = processor.parseSourceIp(packet, packet.size)
        assertNull(result)
    }

    // ==================== 协议解析测试 ====================

    @Test
    fun parseProtocol_tcp() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[9] = 6

        val result = processor.parseProtocol(packet)
        assertEquals(6, result)
    }

    @Test
    fun parseProtocol_udp() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[9] = 17

        val result = processor.parseProtocol(packet)
        assertEquals(17, result)
    }

    @Test
    fun parseProtocol_icmp() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[9] = 1

        val result = processor.parseProtocol(packet)
        assertEquals(1, result)
    }

    @Test
    fun parseProtocol_packetTooShort() {
        val packet = ByteArray(9)
        val result = processor.parseProtocol(packet)
        assertEquals(0, result)
    }

    // ==================== 目标端口解析测试 ====================

    @Test
    fun parseDestinationPort_tcpPacket() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[22] = 1.toByte()
        packet[23] = 0xBB.toByte()

        val result = processor.parseDestinationPort(packet, packet.size)
        assertEquals(443, result)
    }

    @Test
    fun parseDestinationPort_httpPort80() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[22] = 0x00.toByte()
        packet[23] = 0x50.toByte()

        val result = processor.parseDestinationPort(packet, packet.size)
        assertEquals(80, result)
    }

    @Test
    fun parseDestinationPort_dnsPort53() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[22] = 0x00.toByte()
        packet[23] = 0x35.toByte()

        val result = processor.parseDestinationPort(packet, packet.size)
        assertEquals(53, result)
    }

    @Test
    fun parseDestinationPort_highPort() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[22] = 0xFF.toByte()
        packet[23] = 0xFF.toByte()

        val result = processor.parseDestinationPort(packet, packet.size)
        assertEquals(65535, result)
    }

    @Test
    fun parseDestinationPort_minimumValue() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[22] = 0x00
        packet[23] = 0x00

        val result = processor.parseDestinationPort(packet, packet.size)
        assertEquals(0, result)
    }

    @Test
    fun parseDestinationPort_packetTooShort() {
        val packet = ByteArray(21)
        packet[0] = 0x45

        val result = processor.parseDestinationPort(packet, packet.size)
        assertNull(result)
    }

    @Test
    fun parseDestinationPort_withOptionsHeader() {
        val packet = ByteArray(44)
        packet[0] = 0x46
        packet[26] = 0x01.toByte()
        packet[27] = 0xBB.toByte()

        val result = processor.parseDestinationPort(packet, packet.size)
        assertEquals(443, result)
    }

    // ==================== 源端口解析测试 ====================

    @Test
    fun parseSourcePort_tcpPacket() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[20] = 0x30.toByte()
        packet[21] = 0x39.toByte()

        val result = processor.parseSourcePort(packet, packet.size)
        assertEquals(12345, result)
    }

    @Test
    fun parseSourcePort_ephemeralPort() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[20] = 0xC0.toByte()
        packet[21] = 0x00.toByte()

        val result = processor.parseSourcePort(packet, packet.size)
        assertEquals(49152, result)
    }

    // ==================== Payload 提取测试 ====================

    @Test
    fun extractTransportPayloadInfo_tcpPacket() {
        val packet = ByteArray(60)
        packet[0] = 0x45
        packet[9] = 6
        packet[32] = 0x50.toByte()

        val result = processor.extractTransportPayloadInfo(packet, packet.size)
        assertNotNull(result)
        assertEquals(40, result?.first)
        assertEquals(20, result?.second)
    }

    @Test
    fun extractTransportPayloadInfo_udpPacket() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[9] = 17

        val result = processor.extractTransportPayloadInfo(packet, packet.size)
        assertNotNull(result)
        assertEquals(28, result?.first)
        assertEquals(12, result?.second)
    }

    @Test
    fun extractTransportPayloadInfo_noPayload() {
        val packet = ByteArray(28)
        packet[0] = 0x45
        packet[9] = 17

        val result = processor.extractTransportPayloadInfo(packet, packet.size)
        assertNull(result)
    }

    @Test
    fun extractTransportPayloadInfo_packetTooShort() {
        val packet = ByteArray(10)
        val result = processor.extractTransportPayloadInfo(packet, packet.size)
        assertNull(result)
    }

    @Test
    fun extractTransportPayloadInfo_tcpWithOptions() {
        val packet = ByteArray(80)
        packet[0] = 0x45
        packet[9] = 6
        packet[32] = (8 shl 4).toByte()

        val result = processor.extractTransportPayloadInfo(packet, packet.size)
        assertNotNull(result)
        assertEquals(52, result?.first)
        assertEquals(28, result?.second)
    }

    // ==================== 校验和计算测试 ====================

    @Test
    fun calculateChecksum_simpleCase() {
        val data = byteArrayOf(0x45, 0x00, 0x00, 0x3c, 0x1c, 0x46)
        val checksum = processor.calculateChecksum(data, 0, data.size)
        assertTrue(checksum in 0..65535)
    }

    @Test
    fun calculateChecksum_withOddLength() {
        val data = byteArrayOf(0x45, 0x00, 0x00)
        val checksum = processor.calculateChecksum(data, 0, data.size)
        assertTrue(checksum in 0..65535)
    }

    @Test
    fun calculateChecksum_ipHeader() {
        val ipHeader = ByteArray(20)
        ipHeader[0] = 0x45
        ipHeader[1] = 0x00
        ipHeader[2] = 0x00
        ipHeader[3] = 0x3c
        ipHeader[4] = 0x00
        ipHeader[5] = 0x00
        ipHeader[6] = 0x40
        ipHeader[7] = 0x00
        ipHeader[8] = 0x40
        ipHeader[9] = 0x06
        ipHeader[10] = 0x00
        ipHeader[11] = 0x00
        ipHeader[12] = 0x0A
        ipHeader[13] = 0x00
        ipHeader[14] = 0x00
        ipHeader[15] = 0x01
        ipHeader[16] = 0xC0.toByte()
        ipHeader[17] = 0xA8.toByte()
        ipHeader[18] = 0x01
        ipHeader[19] = 0x01

        val checksum = processor.calculateChecksum(ipHeader, 0, 20)
        assertTrue(checksum in 0..65535)

        ipHeader[10] = (checksum shr 8).toByte()
        ipHeader[11] = (checksum and 0xFF).toByte()

        val verificationChecksum = processor.calculateChecksum(ipHeader, 0, 20)
        assertEquals(0, verificationChecksum)
    }

    @Test
    fun calculateChecksum_zeroData() {
        val data = ByteArray(20) { 0x00 }
        val checksum = processor.calculateChecksum(data, 0, data.size)
        assertTrue(checksum in 0..65535)
    }

    @Test
    fun calculateTcpChecksum_withPseudoHeader() {
        val buffer = ByteArray(40)
        val srcIp = listOf(10, 0, 0, 1)
        val dstIp = listOf(192, 168, 1, 1)

        buffer[20] = 0x30.toByte()
        buffer[21] = 0x39.toByte()
        buffer[22] = 0x01.toByte()
        buffer[23] = 0xBB.toByte()
        buffer[24] = 0x00
        buffer[25] = 0x00
        buffer[26] = 0x00
        buffer[27] = 0x01
        buffer[28] = 0x00
        buffer[29] = 0x00
        buffer[30] = 0x00
        buffer[31] = 0x00
        buffer[32] = 0x50
        buffer[33] = 0x18
        buffer[34] = 0x20
        buffer[35] = 0x00
        buffer[36] = 0x00
        buffer[37] = 0x00
        buffer[38] = 0x00
        buffer[39] = 0x00

        val checksum = processor.calculateTcpChecksum(buffer, srcIp, dstIp, 6, 20, 0)
        assertTrue(checksum in 0..65535)
    }

    @Test
    fun calculateTcpChecksum_withPayload() {
        val buffer = ByteArray(60)
        val srcIp = listOf(10, 0, 0, 1)
        val dstIp = listOf(192, 168, 1, 1)

        buffer[20] = 0x30.toByte()
        buffer[21] = 0x39.toByte()
        buffer[22] = 0x01.toByte()
        buffer[23] = 0xBB.toByte()
        buffer[32] = 0x50

        for (i in 40 until 60) {
            buffer[i] = (i % 256).toByte()
        }

        val checksum = processor.calculateTcpChecksum(buffer, srcIp, dstIp, 6, 20, 20)
        assertTrue(checksum in 0..65535)
    }

    @Test
    fun calculateTcpChecksum_ipv4Options() {
        val buffer = ByteArray(64)
        val srcIp = listOf(10, 0, 0, 1)
        val dstIp = listOf(192, 168, 1, 1)

        buffer[0] = 0x46
        buffer[20] = 0x11
        buffer[21] = 0x22
        buffer[22] = 0x33
        buffer[23] = 0x44
        buffer[24] = 0x30.toByte()
        buffer[25] = 0x39.toByte()
        buffer[26] = 0x01.toByte()
        buffer[27] = 0xBB.toByte()
        buffer[36] = 0x50

        val checksum = processor.calculateTcpChecksum(buffer, srcIp, dstIp, 6, 20, 0)
        val expected = computeTcpChecksumExpected(buffer, srcIp, dstIp, 6, 24, 20)

        assertEquals(expected, checksum)
    }

    private fun computeTcpChecksumExpected(
        buffer: ByteArray,
        srcIp: List<Int>,
        dstIp: List<Int>,
        protocol: Int,
        tcpStartOffset: Int,
        tcpSegmentLength: Int,
    ): Int {
        var sum = 0
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

    // ==================== 回包构造测试 ====================

    @Test
    fun constructReturnPacket_withOversizedPayload_returnsInvalidLength() {
        val session = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = null,
            virtualSrcIp = "10.0.0.100"
        )
        val buffer = ByteArray(64)

        val packetLen = processor.constructReturnPacket(buffer, session, 65)
        assertTrue(packetLen <= 0)
    }

    @Test
    fun constructReturnPacket_withInvalidIp_returnsInvalidLength() {
        val buffer = ByteArray(128)

        val invalidVirtualSrcSession = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = null,
            virtualSrcIp = "300.1.1.1"
        )
        val lenWithInvalidVirtualSrc = processor.constructReturnPacket(buffer, invalidVirtualSrcSession, 8)
        assertTrue(lenWithInvalidVirtualSrc <= 0)

        val invalidSrcSession = ConnectionSession(
            srcIp = "invalid.ip",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = null,
            virtualSrcIp = "10.0.0.100"
        )
        val lenWithInvalidSrc = processor.constructReturnPacket(buffer, invalidSrcSession, 8)
        assertTrue(lenWithInvalidSrc <= 0)
    }

    @Test
    fun constructReturnPacket_validSession_returnsCorrectLength() {
        val session = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = null,
            virtualSrcIp = "10.0.0.100"
        )
        val buffer = ByteArray(128)

        val packetLen = processor.constructReturnPacket(buffer, session, 10)
        assertEquals(50, packetLen) // 20 IP + 20 TCP + 10 payload
    }

    // ==================== H14: 0 长度控制包与 TCP 标志位测试 ====================

    @Test
    fun constructReturnPacket_zeroLengthControlPacket_returnsCorrectLength() {
        val session = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = null,
            virtualSrcIp = "10.0.0.100"
        )
        val buffer = ByteArray(128)

        // 0 长度控制包应返回 40（20 IP + 20 TCP）
        val packetLen = processor.constructReturnPacket(buffer, session, 0, TcpFlags.ACK)
        assertEquals(40, packetLen)
    }

    @Test
    fun constructReturnPacket_zeroLengthWithFinFlag_setsFinAck() {
        val session = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = null,
            virtualSrcIp = "10.0.0.100",
            tcpState = TcpState.FIN_WAIT
        )
        val buffer = ByteArray(128)

        val packetLen = processor.constructReturnPacket(buffer, session, 0)
        assertEquals(40, packetLen)
        // TCP 标志位在 offset 33
        val flags = buffer[33].toInt() and 0xFF
        assertEquals(TcpFlags.FIN_ACK, flags)
    }

    @Test
    fun constructReturnPacket_establishedState_setsPshAck() {
        val session = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = null,
            virtualSrcIp = "10.0.0.100",
            tcpState = TcpState.ESTABLISHED
        )
        val buffer = ByteArray(128)

        val packetLen = processor.constructReturnPacket(buffer, session, 10)
        assertEquals(50, packetLen)
        val flags = buffer[33].toInt() and 0xFF
        assertEquals(TcpFlags.PSH_ACK, flags)
    }

    @Test
    fun constructReturnPacket_synSentState_setsSynAck() {
        val session = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = null,
            virtualSrcIp = "10.0.0.100",
            tcpState = TcpState.SYN_SENT
        )
        val buffer = ByteArray(128)

        val packetLen = processor.constructReturnPacket(buffer, session, 0)
        assertEquals(40, packetLen)
        val flags = buffer[33].toInt() and 0xFF
        assertEquals(TcpFlags.SYN_ACK, flags)
    }

    @Test
    fun constructReturnPacket_explicitRstFlag_overridesState() {
        val session = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = null,
            virtualSrcIp = "10.0.0.100",
            tcpState = TcpState.ESTABLISHED
        )
        val buffer = ByteArray(128)

        val packetLen = processor.constructReturnPacket(buffer, session, 0, TcpFlags.RST_ACK)
        assertEquals(40, packetLen)
        val flags = buffer[33].toInt() and 0xFF
        assertEquals(TcpFlags.RST_ACK, flags)
    }

    @Test
    fun constructReturnPacket_usesSessionSeqAckNumbers() {
        val session = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = null,
            virtualSrcIp = "10.0.0.100",
            seqNum = 0x12345678L,
            ackNum = 0xABCDEF01L
        )
        val buffer = ByteArray(128)

        val packetLen = processor.constructReturnPacket(buffer, session, 0)
        assertEquals(40, packetLen)

        // Seq number at offset 24-27 (big-endian)
        val seq = ((buffer[24].toInt() and 0xFF) shl 24) or
                  ((buffer[25].toInt() and 0xFF) shl 16) or
                  ((buffer[26].toInt() and 0xFF) shl 8) or
                  (buffer[27].toInt() and 0xFF)
        assertEquals(0x12345678, seq)

        // Ack number at offset 28-31 (big-endian)
        val ack = ((buffer[28].toInt() and 0xFF) shl 24) or
                  ((buffer[29].toInt() and 0xFF) shl 16) or
                  ((buffer[30].toInt() and 0xFF) shl 8) or
                  (buffer[31].toInt() and 0xFF)
        assertEquals(0xABCDEF01.toLong(), ack.toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun constructReturnPacket_zeroLengthPayload_withDataState_isValid() {
        val session = ConnectionSession(
            srcIp = "10.0.0.2",
            srcPort = 12345,
            dstIp = "192.168.1.1",
            dstPort = 443,
            protocol = 6,
            pooledConnection = null,
            virtualSrcIp = "10.0.0.100",
            tcpState = TcpState.ESTABLISHED
        )
        val buffer = ByteArray(128)

        // ESTABLISHED 状态下 0 长度包应使用 PSH+ACK（数据包语义）
        val packetLen = processor.constructReturnPacket(buffer, session, 0)
        assertEquals(40, packetLen)
        val flags = buffer[33].toInt() and 0xFF
        assertEquals(TcpFlags.PSH_ACK, flags)
    }

    // ==================== 真实数据包测试 ====================

    @Test
    fun realPacket_tcpSynPacket_parsesCorrectly() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[2] = 0x00
        packet[3] = 0x28
        packet[4] = 0x00
        packet[5] = 0x00
        packet[6] = 0x40
        packet[7] = 0x00
        packet[8] = 0x40
        packet[9] = 0x06
        packet[10] = 0x00
        packet[11] = 0x00
        packet[12] = 0x0A
        packet[13] = 0x00
        packet[14] = 0x00
        packet[15] = 0x02
        packet[16] = 0xC0.toByte()
        packet[17] = 0xA8.toByte()
        packet[18] = 0x01
        packet[19] = 0x01
        packet[20] = 0xD4.toByte()
        packet[21] = 0x31.toByte()
        packet[22] = 0x00
        packet[23] = 0x50

        assertEquals("192.168.1.1", processor.parseDestinationIp(packet, packet.size))
        assertEquals("10.0.0.2", processor.parseSourceIp(packet, packet.size))
        assertEquals(6, processor.parseProtocol(packet))
        assertEquals(80, processor.parseDestinationPort(packet, packet.size))
        assertEquals(54321, processor.parseSourcePort(packet, packet.size))
    }

    @Test
    fun realPacket_udpDnsQuery_parsesCorrectly() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[9] = 0x11
        packet[12] = 0x0A
        packet[13] = 0x00
        packet[14] = 0x00
        packet[15] = 0x02
        packet[16] = 0x08
        packet[17] = 0x08
        packet[18] = 0x08
        packet[19] = 0x08
        packet[20] = 0x30.toByte()
        packet[21] = 0x39.toByte()
        packet[22] = 0x00
        packet[23] = 0x35.toByte()

        assertEquals("8.8.8.8", processor.parseDestinationIp(packet, packet.size))
        assertEquals(17, processor.parseProtocol(packet))
        assertEquals(53, processor.parseDestinationPort(packet, packet.size))
    }
}
