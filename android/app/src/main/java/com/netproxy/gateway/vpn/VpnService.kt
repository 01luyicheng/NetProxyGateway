package com.netproxy.gateway.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService as AndroidVpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.netproxy.gateway.BuildConfig
import com.netproxy.gateway.R
import com.netproxy.gateway.connection.AuthSessionStore
import com.netproxy.gateway.i18n.AppLocale
import com.netproxy.gateway.ui.MainActivity
import com.netproxy.gateway.proxy.Socks5ProxyService
import com.netproxy.gateway.proxy.Socks5ConnectionPool
import com.netproxy.gateway.proxy.Socks5ConnectionPoolConfig
import com.netproxy.gateway.proxy.PooledSocks5Connection
import com.netproxy.gateway.utils.IpAddressUtils
import dagger.hilt.android.AndroidEntryPoint
import org.slf4j.LoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.math.min
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong

enum class VpnState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

data class VpnStatus(
    val state: VpnState = VpnState.STOPPED,
    val errorMessage: String? = null,
    val connectedClients: Int = 0
)

@AndroidEntryPoint
class GatewayVpnService : AndroidVpnService() {

    companion object {
        private val logger = LoggerFactory.getLogger(GatewayVpnService::class.java)
        private const val LANGUAGE_LISTENER_ID = "vpn_service"
        private const val NOTIFICATION_CHANNEL_ID = "vpn_service_channel"
        private const val NOTIFICATION_ID = 100
        private const val PACKET_BUFFER_SIZE = 32 * 1024
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        private const val MAX_RETURN_TRAFFIC_IDLE_DELAY_MS = 100L
        private const val SESSION_MISSING_LOG_INTERVAL_MS = 5_000L
        private const val GATEWAY_CONFIG_PREFS = "gateway_config"
        private const val DNS_SERVERS_PREF_KEY = "dns_servers"

        const val VPN_ADDRESS = "10.0.0.2"
        const val VPN_ROUTE = "0.0.0.0"
        const val VPN_MTU = 1500

        private val _status = MutableStateFlow(VpnStatus())
        val status: StateFlow<VpnStatus> = _status.asStateFlow()

        fun resetStatus() {
            _status.value = VpnStatus()
        }

        // SOCKS5 代理本地端口
        const val SOCKS5_PROXY_HOST = "127.0.0.1"
        const val SOCKS5_PROXY_PORT = 1080
        
        private val EMPTY_BYTE_ARRAY = ByteArray(0)
        
        // Protocol constants
        private const val PROTOCOL_TCP = 6
        private const val PROTOCOL_UDP = 17
        
        // IP header constants
        private const val IP_VERSION_IHL = 0x45
        private const val IP_FLAG_DF = 0x40
        private const val IP_DEFAULT_TTL = 64
        private const val IP_HEADER_LEN = 20
        
        // TCP header constants
        private const val TCP_HEADER_LEN = 20
        private const val TCP_DATA_OFFSET = (5 shl 4)
        private const val TCP_FLAGS_PSH_ACK = 0x18
        private const val TCP_WINDOW_SIZE = 8192
        
        // 内网 IP 段（通过 WiFi 直连）
        // 10.0.0.0/8 - 私有 A 类
        // 172.16.0.0/12 - 私有 B 类  
        // 192.168.0.0/16 - 私有 C 类
        private val PRIVATE_IP_RANGES = listOf(
            "10.0.0.0" to 8,
            "172.16.0.0" to 12,
            "192.168.0.0" to 16
        )
        
        // DNS 服务器（通过 WiFi）
        private val DNS_SERVERS = listOf(
            "8.8.8.8", "8.8.4.4",
            "1.1.1.1", "1.0.0.1"
        )
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    // serviceScope 使用 var 以便在服务停止后可以重新创建
    private var serviceScope: CoroutineScope? = null

    @Inject
    lateinit var authSessionStore: AuthSessionStore

    @Inject
    lateinit var virtualIpAllocator: VirtualIpAllocator

    private var vpnInterface: ParcelFileDescriptor? = null
    
    // SOCKS5连接池
    private var socks5ConnectionPool: Socks5ConnectionPool? = null
    
    // 活跃的代理连接映射（四元组 -> 连接会话）- 现在存储借用自连接池的连接
    private val activeConnections = ConcurrentHashMap<String, ConnectionSession>()
    // TUN读取缓冲区
    private val packetBuffer = ByteArray(PACKET_BUFFER_SIZE)

    // 虚拟IP分配（用于回包构造）
    private val virtualIpPool = ConcurrentHashMap<String, String>() // realDstIp -> virtualSrcIp
    private val reverseIpMap = ConcurrentHashMap<String, String>() // virtualSrcIp -> realDstIp
    private val nextVirtualIp = AtomicInteger(1) // 10.0.0.x
    private val lastMissingSessionLogAt = AtomicLong(0L)

    private var resolvedDnsServers: Set<String> = DNS_SERVERS.toSet()
    
    // TUN输出流（用于回包注入）
    private var vpnOutputStream: FileOutputStream? = null
    
    // 原子标志，防止 stopVpn() 重复执行
    private val isStopping = AtomicBoolean(false)
    
    // 原子标志，防止 cleanupVpnResources() 重复执行
    private val isCleaningUp = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 创建协程作用域
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // 重置状态，确保服务重启后状态干净
        resetStatus()

        // H27: 注册语言变更监听，运行中的通知会自动刷新
        // I1: 防御性注销，防止系统强制杀死后残留监听器
        unregisterLanguageChangeListener()
        registerLanguageChangeListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startVpn()
            "STOP" -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (_status.value.state == VpnState.RUNNING) {
            return
        }

        // 如果正在停止，等待停止完成后再启动
        if (isStopping.get()) {
            logger.warn("Cannot start VPN while stopping")
            return
        }

        // 重置停止标志，允许新的停止流程
        isStopping.set(false)
        // 重置清理标志，允许新的清理流程
        isCleaningUp.set(false)

        startForeground(NOTIFICATION_ID, createNotification())

        _status.value = VpnStatus(state = VpnState.STARTING)

        try {
            val dnsServers = resolveDnsServers()
            resolvedDnsServers = dnsServers.toSet()

            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(VPN_MTU)
                .addAddress(VPN_ADDRESS, 32)
                .addRoute(VPN_ROUTE, 0)
                .setBlocking(true)

            dnsServers.forEach { dnsServer ->
                builder.addDnsServer(dnsServer)
            }

            val configureIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setConfigureIntent(configureIntent)

            vpnInterface = builder.establish()

            if (vpnInterface != null) {
                // 初始化SOCKS5连接池
                initializeConnectionPool()

                // 初始化TUN输出流用于回包注入
                vpnOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor)

                // 所有资源就绪后再更新状态为 RUNNING
                _status.value = VpnStatus(state = VpnState.RUNNING)

                // 启动TUN读取协程
                serviceScope?.launch {
                    processVpnTraffic()
                }

                // 启动回包处理协程
                serviceScope?.launch {
                    processReturnTraffic()
                }

                startProxyService()
            } else {
                _status.value = VpnStatus(
                    state = VpnState.ERROR,
                    errorMessage = getString(R.string.error_failed_to_establish_vpn)
                )
            }
        } catch (e: Exception) {
            _status.value = VpnStatus(
                state = VpnState.ERROR,
                errorMessage = e.message
            )
        }
    }

    private suspend fun processVpnTraffic() {
        val vpnFd = vpnInterface ?: return

        FileInputStream(vpnFd.fileDescriptor).use { inputStream ->
            try {
                while (_status.value.state == VpnState.RUNNING) {
                    val length = inputStream.read(packetBuffer)
                    if (length > 0) {
                        processPacket(packetBuffer, length)
                        cleanupStaleConnections()
                    }
                }
            } catch (e: Exception) {
                if (_status.value.state == VpnState.RUNNING) {
                    logger.error("VPN traffic loop failed", e)
                    _status.value = VpnStatus(
                        state = VpnState.ERROR,
                        errorMessage = e.message
                    )
                }
            }
        }
    }

    private fun processPacket(packet: ByteArray, length: Int) {
        if (length <= 0) return

        // 解析 IP 包获取目标地址
        val destinationIp = parseDestinationIp(packet, length) ?: return
        val protocol = parseProtocol(packet)
        val destinationPort = parseDestinationPort(packet, length)
        
        // 根据目标地址、协议和端口判断流量类型
        val routeType = determineRouteType(destinationIp, protocol, destinationPort)
        
        when (routeType) {
            RouteType.LOCAL_NETWORK -> {
                // 内网流量：绕过 VPN，最终出口由系统路由决定
                forwardViaWifi(packet, length, destinationIp)
            }
            RouteType.DNS -> {
                // DNS 查询：绕过 VPN，最终出口由系统路由决定（使用内网 DNS）
                forwardViaWifi(packet, length, destinationIp)
            }
            RouteType.CLOUD_SERVER -> {
                // 云服务器：走蜂窝网络（排除在 VPN 外）
                // Android 13+ 使用 excludeRoute 硬件分流
                // 软件层面直接忽略这些包
            }
            RouteType.PROXY -> {
                // 外网流量：通过本地 SOCKS5 代理转发
                forwardViaSocks5(packet, length, destinationIp)
            }
        }
    }
    
    /**
     * 解析 IP 包中的目标 IP 地址
     */
    private fun parseDestinationIp(packet: ByteArray, length: Int): String? {
        if (length < 20) return null
        
        // 检查 IP 版本 (IPv4 = 4)
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return null // 仅支持 IPv4
        
        // IP 头长度
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (length < headerLength || length < 20) return null
        
        // 目标 IP 在第 16-19 字节
        val dstIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
        return dstIp
    }
    
    /**
     * 判断流量类型
     */
    private fun determineRouteType(destinationIp: String, protocol: Int, destinationPort: Int?): RouteType {
        // DNS 分流必须同时满足：DNS IP + TCP/UDP + 53端口
        if (destinationPort != null && VpnDnsConfig.shouldRouteDnsViaWifi(
                destinationIp = destinationIp,
                protocol = protocol,
                destinationPort = destinationPort,
                dnsServers = resolvedDnsServers
            )) {
            return RouteType.DNS
        }
        
        // 检查是否是内网 IP
        if (isPrivateIp(destinationIp)) {
            return RouteType.LOCAL_NETWORK
        }
        
        // 检查是否是云服务器 IP（需要排除）
        // 实际实现中应从配置或路由表获取
        // 这里简化为：其他所有流量走代理
        return RouteType.PROXY
    }

    private fun resolveDnsServers(): List<String> {
        val prefs = getSharedPreferences(GATEWAY_CONFIG_PREFS, Context.MODE_PRIVATE)
        val configuredDns = prefs.getString(DNS_SERVERS_PREF_KEY, null)
        return VpnDnsConfig.resolveDnsServers(configuredDns, DNS_SERVERS)
    }
    
    /**
     * 初始化SOCKS5连接池
     */
    private fun initializeConnectionPool() {
        val config = Socks5ConnectionPoolConfig(
            maxConnections = 100,
            idleTimeoutMs = CONNECTION_TIMEOUT_MS,
            connectionTimeoutMs = 5_000,
            socketSoTimeoutMs = 30_000,
            maxConnectionsPerDestination = 8,
            cleanupIntervalMs = 30_000L
        )
        
        socks5ConnectionPool = Socks5ConnectionPool(
            proxyHost = SOCKS5_PROXY_HOST,
            proxyPort = SOCKS5_PROXY_PORT,
            config = config,
            credentialProvider = {
                val session = authSessionStore.getCurrentSession()
                if (session != null) {
                    Pair(session.deviceId, session.authToken)
                } else {
                    null
                }
            }
        )
    }
    
    /**
     * 判断是否是私有 IP 地址
     */
    private fun isPrivateIp(ip: String): Boolean {
        return IpAddressUtils.isPrivateIpv4Rfc1918(ip)
    }
    
    /**
     * 直连内网流量（当前实现通过 protect() 让 socket 绕过 VPN 隧道）
     * 注意：protect() 只保证不走 VPN，不保证一定走 WiFi。
     * 在多网络并存或厂商网络加速场景下，系统可能将流量路由到其他网卡。
     */
    private fun forwardViaWifi(packet: ByteArray, length: Int, destinationIp: String) {
        try {
            val protocol = parseProtocol(packet)
            val destinationPort = parseDestinationPort(packet, length) ?: return
            val payload = extractTransportPayload(packet, length)

            when (protocol) {
                PROTOCOL_UDP -> {
                    // UDP
                    DatagramSocket().use { socket ->
                        protect(socket)
                        val datagram = DatagramPacket(payload, payload.size, InetAddress.getByName(destinationIp), destinationPort)
                        socket.send(datagram)
                    }
                }
                PROTOCOL_TCP -> {
                    // TCP best-effort forwarding
                    Socket().use { socket ->
                        protect(socket)
                        socket.connect(InetSocketAddress(destinationIp, destinationPort), 3000)
                        socket.getOutputStream().write(payload)
                        socket.getOutputStream().flush()
                    }
                }
                else -> {
                    logDebug("Skip unsupported protocol=$protocol for WiFi route")
                }
            }
        } catch (e: Exception) {
            logger.warn("Forward via WiFi failed for ${redactIp(destinationIp)}", e)
        }
    }
    
    /**
     * 通过本地 SOCKS5 代理转发（使用连接池复用SOCKS5连接）
     */
    private fun forwardViaSocks5(packet: ByteArray, length: Int, destinationIp: String) {
        val destinationPort = parseDestinationPort(packet, length) ?: return
        val protocol = parseProtocol(packet)
        val payloadInfo = extractTransportPayloadInfo(packet, length) ?: return
        val srcIp = parseSourceIp(packet, length) ?: return
        val srcPort = parseSourcePort(packet, length) ?: return
        
        val pool = socks5ConnectionPool
        if (pool == null) {
            logMissingSession(destinationIp)
            return
        }
        
        // 使用四元组作为会话key
        val connectionKey = "$srcIp:$srcPort-$destinationIp:$destinationPort"

        try {
            val existingSession = activeConnections[connectionKey]
            val pooledConn = if (existingSession?.pooledConnection?.isValid() == true) {
                existingSession.pooledConnection
            } else {
                // 旧连接无效，清理
                existingSession?.pooledConnection?.let { pool.returnConnection(it) }
                
                // 从连接池借用连接
                val conn = pool.borrowConnection(
                    destinationIp = destinationIp,
                    destinationPort = destinationPort,
                    protectSocket = { protect(it) }
                )
                
                if (conn != null) {
                    // 分配虚拟IP用于回包
                    val virtualSrcIp = getOrAllocateVirtualIp(destinationIp)
                    
                    activeConnections[connectionKey] = ConnectionSession(
                        srcIp = srcIp,
                        srcPort = srcPort,
                        dstIp = destinationIp,
                        dstPort = destinationPort,
                        protocol = protocol,
                        pooledConnection = conn,
                        virtualSrcIp = virtualSrcIp
                    )
                    logDebug("Borrowed connection from pool: ${redactConnectionKey(connectionKey)} -> virtualIP: ${redactIp(virtualSrcIp)}")
                    conn
                } else {
                    logger.warn("Failed to borrow connection from pool for ${redactConnectionKey(connectionKey)}")
                    null
                }
            }

            if (pooledConn != null) {
                // 写入payload（不拷贝数组）
                pooledConn.socket.getOutputStream()?.write(packet, payloadInfo.first, payloadInfo.second)
                pooledConn.socket.getOutputStream()?.flush()
                
                // 更新会话活动状态
                activeConnections[connectionKey]?.updateActivity()
            }
        } catch (e: Exception) {
            logger.warn("Forward via SOCKS5 failed for ${redactConnectionKey(connectionKey)}", e)
            // 连接出错，归还连接并清理会话
            activeConnections.remove(connectionKey)?.pooledConnection?.let { pool.returnConnection(it) }
        }
    }
    
    /**
     * 解析源IP地址
     */
    private fun parseSourceIp(packet: ByteArray, length: Int): String? {
        if (length < 20) return null
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return null
        // 源IP在第12-15字节
        return "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}.${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
    }
    
    /**
     * 解析源端口
     */
    private fun parseSourcePort(packet: ByteArray, length: Int): Int? {
        if (length < 20) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (length < headerLength + 2) return null
        // 源端口在传输层头的前2字节
        return ((packet[headerLength].toInt() and 0xFF) shl 8) or (packet[headerLength + 1].toInt() and 0xFF)
    }

    private fun cleanupStaleConnections() {
        val now = System.currentTimeMillis()
        val pool = socks5ConnectionPool
        
        activeConnections.entries.removeIf { entry ->
            val session = entry.value
            val isExpired = now - session.lastActivity > CONNECTION_TIMEOUT_MS
            if (isExpired) {
                try {
                    // 归还连接到连接池，而不是直接关闭
                    session.pooledConnection?.let { pool?.returnConnection(it) }
                    logger.debug("Returned stale connection to pool: ${redactConnectionKey(entry.key)}")
                } catch (e: Exception) {
                    logger.warn("Failed to return stale connection to pool", e)
                }
            }
            isExpired
        }
    }
    
    /**
     * 处理回包（从远程服务器读取响应并注入TUN）
     * 使用平滑指数退避算法减少空闲时的CPU轮询
     */
    private suspend fun processReturnTraffic() {
        var idleRounds = 0
        while (_status.value.state == VpnState.RUNNING) {
            try {
                var hadData = false
                // 遍历所有活跃连接，检查是否有数据可读
                activeConnections.forEach { (key, session) ->
                    if (session.protocol == PROTOCOL_TCP) {
                        hadData = processTcpReturn(session, key) || hadData
                    } else if (session.protocol == PROTOCOL_UDP) {
                        processUdpReturn(session, key)
                    }
                }
                if (hadData) {
                    idleRounds = 0
                } else {
                    idleRounds = (idleRounds + 1).coerceAtMost(31)
                }
                val idleDelay = calculateIdleDelay(idleRounds)
                kotlinx.coroutines.delay(idleDelay)
            } catch (e: Exception) {
                if (_status.value.state == VpnState.RUNNING) {
                    logger.error("Error processing return traffic", e)
                }
            }
        }
    }

    /**
     * 计算空闲退避延迟
     * 使用平滑指数退避算法：
     * - 有数据时：1ms（最小延迟，快速响应）
     * - 空闲时：指数增长，最大100ms
     * 公式：delay = min(base * 2^rounds, maxDelay)
     */
    private fun calculateIdleDelay(idleRounds: Int): Long {
        if (idleRounds == 0) return 1L
        // 基础延迟2ms，指数增长，最大MAX_RETURN_TRAFFIC_IDLE_DELAY_MS
        val baseDelay = 2L
        val exponent = minOf(idleRounds - 1, 6) // 限制指数最大为6，避免过大数值
        return minOf(baseDelay shl exponent, MAX_RETURN_TRAFFIC_IDLE_DELAY_MS)
    }
    
    /**
     * 处理TCP回包
     */
    private fun processTcpReturn(session: ConnectionSession, sessionKey: String): Boolean {
        val pooledConn = session.pooledConnection ?: return false
        val socket = pooledConn.socket
        if (socket.isClosed || !pooledConn.isValid()) {
            // 连接无效，归还到连接池并移除会话
            socks5ConnectionPool?.returnConnection(pooledConn)
            activeConnections.remove(sessionKey)
            return false
        }
        
        try {
            val input = socket.getInputStream()
            val available = input.available()
            if (available > 0) {
                val buffer = ByteArray(PACKET_BUFFER_SIZE)
                val payloadOffset = 40 // 20-byte IP header + 20-byte TCP header
                val read = input.read(buffer, payloadOffset, minOf(available, buffer.size - payloadOffset))
                if (read > 0) {
                    // 构造回包IP头+TCP头
                    val packetLen = constructReturnPacket(buffer, session, read)
                    if (packetLen <= 0) {
                        logger.warn("Drop invalid TCP return packet for ${redactConnectionKey(sessionKey)}")
                        socks5ConnectionPool?.returnConnection(pooledConn)
                        activeConnections.remove(sessionKey)
                        return false
                    }
                    // 注入TUN
                    if (!injectPacket(buffer, packetLen)) {
                        logger.warn("Failed to inject TCP return packet for ${redactConnectionKey(sessionKey)}, closing session")
                        socks5ConnectionPool?.returnConnection(pooledConn)
                        activeConnections.remove(sessionKey)
                        return false
                    }
                    session.updateActivity()
                    return true
                }
            }
            return false
        } catch (e: Exception) {
            logger.warn("TCP return traffic error for ${redactConnectionKey(sessionKey)}: ${e.message}")
            // 连接出错，归还到连接池并移除会话
            socks5ConnectionPool?.returnConnection(pooledConn)
            activeConnections.remove(sessionKey)
            return false
        }
    }
    
    /**
     * 处理UDP回包
     */
    private fun processUdpReturn(session: ConnectionSession, sessionKey: String) {
        // UDP回包处理（类似TCP，但协议号不同）
        // 当前实现中UDP使用DatagramSocket，处理方式略有不同
        // 简化实现：UDP通常在forwardViaWifi中直接处理
    }
    
    /**
     * 构造回包（IP头 + TCP头 + payload）
     * @return 完整包长度
     */
    private fun constructReturnPacket(buffer: ByteArray, session: ConnectionSession, payloadLen: Int): Int {
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
        buffer[24] = 0 // Seq number (简化)
        buffer[25] = 0
        buffer[26] = 0
        buffer[27] = 0
        buffer[28] = 0 // Ack number
        buffer[29] = 0
        buffer[30] = 0
        buffer[31] = 0
        buffer[32] = TCP_DATA_OFFSET.toByte() // Data offset = 5
        buffer[33] = TCP_FLAGS_PSH_ACK.toByte() // PSH + ACK
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
    
    /**
     * 计算IP校验和
     */
    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
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
    private fun calculateTcpChecksum(
        buffer: ByteArray,
        srcIp: List<Int>,
        dstIp: List<Int>,
        protocol: Int,
        tcpHeaderLen: Int,
        payloadLen: Int
    ): Int {
        var sum = 0
        
        // 伪头
        sum += (srcIp[0] shl 8) or srcIp[1]
        sum += (srcIp[2] shl 8) or srcIp[3]
        sum += (dstIp[0] shl 8) or dstIp[1]
        sum += (dstIp[2] shl 8) or dstIp[3]
        sum += protocol
        sum += tcpHeaderLen + payloadLen
        
        // TCP头和payload
        for (i in 20 until 20 + tcpHeaderLen + payloadLen step 2) {
            if (i + 1 < buffer.size) {
                sum += ((buffer[i].toInt() and 0xFF) shl 8) or (buffer[i + 1].toInt() and 0xFF)
            } else {
                sum += (buffer[i].toInt() and 0xFF) shl 8
            }
        }
        
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }
    
    /**
     * 注入包到TUN接口
     */
    private fun injectPacket(packet: ByteArray, length: Int): Boolean {
        return try {
            val stream = vpnOutputStream
            if (stream == null) {
                logger.warn("vpnOutputStream is null, cannot inject packet")
                false
            } else {
                stream.write(packet, 0, length)
                stream.flush()
                true
            }
        } catch (e: Exception) {
            logger.error("Failed to inject packet to TUN", e)
            false
        }
    }
    
    /**
     * 获取或分配虚拟 IP
     */
    private fun getOrAllocateVirtualIp(realDstIp: String): String {
        return virtualIpAllocator.getOrAllocateVirtualIp(
            realDstIp = realDstIp,
            virtualIpPool = virtualIpPool,
            reverseIpMap = reverseIpMap,
            nextVirtualIp = nextVirtualIp,
            onPoolReset = { logger.error("Virtual IP pool exhausted! Resetting pool.") },
            onNewAllocation = { allocatedIp, dstIp -> logDebug("Allocated virtual IP ${redactIp(allocatedIp)} for ${redactIp(dstIp)}") }
        )
    }

    private fun logMissingSession(destinationIp: String) {
        val nowMs = System.currentTimeMillis()
        val lastMs = lastMissingSessionLogAt.get()
        if (nowMs - lastMs >= SESSION_MISSING_LOG_INTERVAL_MS &&
            lastMissingSessionLogAt.compareAndSet(lastMs, nowMs)
        ) {
            logger.warn("Skip SOCKS5 forward: missing auth session for ${redactIp(destinationIp)}")
        }
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            logger.debug(message)
        }
    }

    private fun parseProtocol(packet: ByteArray): Int {
        return packet[9].toInt() and 0xFF
    }

    private fun parseDestinationPort(packet: ByteArray, length: Int): Int? {
        if (length < 20) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (length < headerLength + 4) return null
        return ((packet[headerLength + 2].toInt() and 0xFF) shl 8) or (packet[headerLength + 3].toInt() and 0xFF)
    }

    /**
     * 提取传输层payload，返回payload在packet中的起始位置和长度（避免创建新数组）
     * @return Pair<起始位置, 长度>，如果无payload返回null
     */
    private fun extractTransportPayloadInfo(packet: ByteArray, length: Int): Pair<Int, Int>? {
        if (length < 20) return null
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        val protocol = parseProtocol(packet)
        val transportHeaderLength = when (protocol) {
            PROTOCOL_TCP -> {
                if (length < ipHeaderLength + 13) return null
                ((packet[ipHeaderLength + 12].toInt() shr 4) and 0x0F) * 4
            }
            PROTOCOL_UDP -> 8
            else -> 0
        }
        val payloadStart = ipHeaderLength + transportHeaderLength
        if (payloadStart >= length) return null
        return Pair(payloadStart, length - payloadStart)
    }
    
    @Deprecated("使用 extractTransportPayloadInfo 避免数组拷贝", ReplaceWith("extractTransportPayloadInfo(packet, length)"))
    private fun extractTransportPayload(packet: ByteArray, length: Int): ByteArray {
        val info = extractTransportPayloadInfo(packet, length)
        return if (info != null) {
            packet.copyOfRange(info.first, info.first + info.second)
        } else {
            EMPTY_BYTE_ARRAY
        }
    }
    
    /**
     * 路由类型枚举
     */
    private enum class RouteType {
        LOCAL_NETWORK,  // 内网 - WiFi 直连
        DNS,           // DNS - WiFi
        CLOUD_SERVER,  // 云服务器 - 蜂窝（排除）
        PROXY          // 其他 - SOCKS5 代理
    }
    
    /**
     * 连接会话（完整四元组映射）- 使用连接池管理SOCKS5连接
     */
    private data class ConnectionSession(
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
    
    /**
     * 连接信息（兼容旧代码）
     */
    @Deprecated("使用 ConnectionSession", ReplaceWith("ConnectionSession"))
    private data class ConnectionInfo(
        val remoteAddress: String,
        val remotePort: Int,
        val localSocket: Socket?,
        val createdAt: Long = System.currentTimeMillis()
    )

    private fun startProxyService() {
        val intent = Intent(this, Socks5ProxyService::class.java)
        startForegroundService(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                AppLocale.getString(this, R.string.notification_vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = AppLocale.getString(this@GatewayVpnService, R.string.notification_vpn_channel_description)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(AppLocale.getString(this, R.string.app_name))
            .setContentText(AppLocale.getString(this, R.string.notification_vpn_content_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * H27: 注册语言变更监听
     * 当用户切换语言时，刷新运行中的 VPN 通知文案
     */
    private fun registerLanguageChangeListener() {
        AppLocale.registerLanguageChangeListener(LANGUAGE_LISTENER_ID) { _ ->
            // 仅在服务运行时刷新通知
            if (_status.value.state == VpnState.RUNNING) {
                updateNotification()
            }
        }
    }

    /**
     * H27: 注销语言变更监听
     */
    private fun unregisterLanguageChangeListener() {
        AppLocale.unregisterLanguageChangeListener(LANGUAGE_LISTENER_ID)
    }

    /**
     * H27: 刷新运行中的前台服务通知文案
     * 当语言切换时调用，更新通知内容为当前语言
     */
    private fun updateNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel()
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            val updatedNotification = createNotification()
            notificationManager.notify(NOTIFICATION_ID, updatedNotification)
            logger.debug("VPN service notification updated for language change")
        } catch (e: Exception) {
            logger.error("Failed to update VPN service notification", e)
        }
    }

    private fun stopVpn() {
        // 先读取当前状态，避免在已经停止的状态下继续执行
        val currentState = _status.value.state
        if (currentState == VpnState.STOPPED || currentState == VpnState.STOPPING) {
            return
        }

        // 使用 CAS 确保只有一个线程能执行停止逻辑
        if (!isStopping.compareAndSet(false, true)) {
            return
        }

        // 双重检查：CAS 成功后再次确认状态，防止 CAS 前状态被其他线程改变
        if (_status.value.state == VpnState.STOPPED) {
            isStopping.set(false)
            return
        }

        try {
            // 立即更新状态为 STOPPING，通知其他观察者服务正在停止
            _status.value = VpnStatus(state = VpnState.STOPPING)

            // 先取消协程作用域，停止所有后台任务
            serviceScope?.cancel()
            serviceScope = null

            // 清理资源（内部有 isCleaningUp 保护，确保只执行一次）
            cleanupVpnResources()

            stopProxyService()

            // stopForeground() 让服务脱离前台状态，但不停止服务本身
            stopForeground(STOP_FOREGROUND_REMOVE)

            // 所有资源清理完成后，更新状态为 STOPPED
            _status.value = VpnStatus(state = VpnState.STOPPED)
        } finally {
            // 重置停止标志，允许下次停止操作
            // startVpn() 中也会重置，双重保险确保状态一致性
            isStopping.set(false)
        }
    }

    /**
     * 清理 VPN 相关资源
     * 提取为独立方法以便在 stopVpn() 和 onDestroy() 中复用
     * 使用原子标志确保只执行一次，避免重复清理导致的资源泄漏或状态不一致
     */
    private fun cleanupVpnResources() {
        // 使用原子操作确保资源清理只执行一次
        if (!isCleaningUp.compareAndSet(false, true)) {
            logger.debug("cleanupVpnResources() already executed, skipping")
            return
        }

        logger.debug("Executing cleanupVpnResources()")

        try {
            vpnOutputStream?.close()
            vpnOutputStream = null
        } catch (e: Exception) {
            logger.warn("Failed to close VPN output stream", e)
        }

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            logger.warn("Failed to close VPN interface", e)
        }

        // 归还所有活跃会话中的连接池连接
        val pool = socks5ConnectionPool
        if (pool != null) {
            activeConnections.values.forEach { session ->
                try {
                    session.pooledConnection?.let { connection ->
                        pool.returnConnection(connection)
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to return connection for session ${redactIp(session.srcIp)}:${session.srcPort}", e)
                }
            }
        } else {
            // 连接池已不存在，直接关闭所有连接
            activeConnections.values.forEach { session ->
                try {
                    session.pooledConnection?.close()
                } catch (e: Exception) {
                    logger.warn("Failed to close connection for session ${redactIp(session.srcIp)}:${session.srcPort}", e)
                }
            }
        }
        activeConnections.clear()

        // 关闭连接池
        try {
            socks5ConnectionPool?.shutdown()
            socks5ConnectionPool = null
        } catch (e: Exception) {
            logger.warn("Failed to shutdown connection pool", e)
        }

        virtualIpPool.clear()
        reverseIpMap.clear()
    }

    private fun stopProxyService() {
        val intent = Intent(this, Socks5ProxyService::class.java)
        stopService(intent)
    }

    override fun onDestroy() {
        // onDestroy() 由系统在服务停止时调用
        // 不要在这里调用 stopVpn()，避免循环调用

        // 使用 _status 判断 stopVpn() 是否已被调用过
        val currentState = _status.value.state
        val stopVpnNotCalled = currentState != VpnState.STOPPED && currentState != VpnState.STOPPING

        if (stopVpnNotCalled) {
            // stopVpn() 没有被调用过（如系统强制回收服务），需要兜底清理资源
            logger.warn("onDestroy() called without stopVpn(), performing cleanup")
            // 更新状态为 STOPPED
            _status.value = VpnStatus(state = VpnState.STOPPED)
            // 停止代理服务
            stopProxyService()
        }

        // 无论 stopVpn() 是否被调用过，都执行资源清理
        // cleanupVpnResources() 内部有 isCleaningUp 保护，确保只执行一次
        cleanupVpnResources()

        // 取消协程作用域，停止所有后台任务，并置null
        serviceScope?.cancel()
        serviceScope = null

        // H27: 注销语言变更监听
        unregisterLanguageChangeListener()

        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}


