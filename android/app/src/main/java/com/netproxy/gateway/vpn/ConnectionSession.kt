package com.netproxy.gateway.vpn

import com.netproxy.gateway.proxy.PooledSocks5Connection

/**
 * 连接会话（完整四元组映射）- 使用连接池管理SOCKS5连接
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
    var lastActivity: Long = System.currentTimeMillis()
) {
    fun updateActivity() {
        lastActivity = System.currentTimeMillis()
        pooledConnection?.markUsed()
    }
}
