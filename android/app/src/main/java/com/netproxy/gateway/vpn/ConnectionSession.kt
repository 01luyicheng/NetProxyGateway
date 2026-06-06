package com.netproxy.gateway.vpn

import com.netproxy.gateway.proxy.PooledSocks5Connection

/**
 * TCP 连接状态（最小状态机）
 */
internal enum class TcpState {
    CLOSED,
    SYN_SENT,
    ESTABLISHED,
    FIN_WAIT,
    CLOSE_WAIT,
    LAST_ACK,
    CLOSING,
    TIME_WAIT
}

/**
 * TCP 标志位常量
 */
internal object TcpFlags {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
    const val SYN_ACK = SYN or ACK
    const val FIN_ACK = FIN or ACK
    const val RST_ACK = RST or ACK
    const val PSH_ACK = PSH or ACK
}

/**
 * 连接会话（完整四元组映射）- 使用连接池管理SOCKS5连接
 * H14: 支持最小 TCP 状态机和 seq/ack 管理，允许 0 长度控制包注入
 */
internal data class ConnectionSession(
    val srcIp: String,
    val srcPort: Int,
    val dstIp: String,
    val dstPort: Int,
    val protocol: Int, // 6=TCP, 17=UDP
    val pooledConnection: PooledSocks5Connection?, // 来自连接池的连接
    val virtualSrcIp: String, // 虚拟源IP（用于回包）
    val createdAt: Long = System.currentTimeMillis(),
    var lastActivity: Long = System.currentTimeMillis(),
    var tcpState: TcpState = TcpState.ESTABLISHED, // 默认已建立（SOCKS5 连接已完成握手）
    var seqNum: Long = 0L, // 发送序列号
    var ackNum: Long = 0L, // 确认序列号
    var pendingControlFlags: Int = 0 // 待发送的控制标志（如 FIN/ACK/RST）
) {
    fun updateActivity() {
        lastActivity = System.currentTimeMillis()
        pooledConnection?.markUsed()
    }

    /**
     * 检查是否需要发送控制包（0 长度）
     */
    fun needsControlPacket(): Boolean = pendingControlFlags != 0

    /**
     * 消费待发送的控制标志，返回当前标志并清零
     */
    fun consumePendingFlags(): Int {
        val flags = pendingControlFlags
        pendingControlFlags = 0
        return flags
    }

    /**
     * 根据当前 TCP 状态获取应使用的标志位
     */
    fun resolveTcpFlags(explicitFlags: Int = 0): Int {
        if (explicitFlags != 0) {
            return explicitFlags
        }
        return when (tcpState) {
            TcpState.SYN_SENT -> TcpFlags.SYN_ACK
            TcpState.FIN_WAIT,
            TcpState.CLOSE_WAIT,
            TcpState.LAST_ACK,
            TcpState.CLOSING -> TcpFlags.FIN_ACK
            else -> TcpFlags.PSH_ACK
        }
    }

    /**
     * 推进序列号（发送了多少数据）
     */
    fun advanceSeq(bytesSent: Int) {
        seqNum = (seqNum + bytesSent) and 0xFFFFFFFFL
    }

    /**
     * 推进确认号（确认接收了多少数据）
     */
    fun advanceAck(bytesReceived: Int) {
        ackNum = (ackNum + bytesReceived) and 0xFFFFFFFFL
    }
}
