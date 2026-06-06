package com.netproxy.gateway.vpn

import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

import javax.inject.Inject

import org.slf4j.LoggerFactory

import dagger.hilt.android.AndroidEntryPoint

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
import com.netproxy.gateway.proxy.Socks5ConnectionPool
import com.netproxy.gateway.proxy.Socks5ConnectionPoolConfig
import com.netproxy.gateway.proxy.Socks5ProxyService
import com.netproxy.gateway.ui.MainActivity
import com.netproxy.gateway.utils.IpAddressUtils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    // 数据包处理器（纯逻辑，与 Service 生命周期无关）
    private val packetProcessor = VpnPacketProcessor()

    // 连接会话管理器（管理活跃连接、连接池、回包处理）
    private lateinit var sessionManager: ConnectionSessionManager

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

                // 初始化连接会话管理器
                sessionManager = ConnectionSessionManager(
                    packetProcessor = packetProcessor,
                    connectionTimeoutMs = CONNECTION_TIMEOUT_MS,
                    packetBufferSize = PACKET_BUFFER_SIZE
                )
                sessionManager.socks5ConnectionPool = socks5ConnectionPool

                // 初始化TUN输出流用于回包注入
                vpnOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
                sessionManager.vpnOutputStream = vpnOutputStream

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
        val destinationIp = packetProcessor.parseDestinationIp(packet, length) ?: return
        val protocol = packetProcessor.parseProtocol(packet)
        val destinationPort = packetProcessor.parseDestinationPort(packet, length)

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
                sessionManager.forwardViaSocks5(packet, length, destinationIp) { protect(it) }
            }
        }
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
    private fun extractTransportPayload(packet: ByteArray, length: Int): ByteArray {
        val info = packetProcessor.extractTransportPayloadInfo(packet, length)
        return if (info != null) {
            packet.copyOfRange(info.first, info.first + info.second)
        } else {
            EMPTY_BYTE_ARRAY
        }
    }

    private fun forwardViaWifi(packet: ByteArray, length: Int, destinationIp: String) {
        try {
            val protocol = packetProcessor.parseProtocol(packet)
            val destinationPort = packetProcessor.parseDestinationPort(packet, length) ?: return
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

    private fun cleanupStaleConnections() {
        if (::sessionManager.isInitialized) {
            sessionManager.cleanupStaleConnections()
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
                // 创建快照避免遍历期间 map 修改导致视图不一致（P20 / C33）
                val snapshot = if (::sessionManager.isInitialized) {
                    sessionManager.getActiveConnectionsSnapshot()
                } else emptyList()
                snapshot.forEach { (key, session) ->
                    // C33: 快照后验证session仍是当前活跃值，避免竞态下操作已归还的socket
                    val stillActive = sessionManager.getActiveConnectionsSnapshot().any { it.first == key && it.second === session }
                    if (!stillActive) return@forEach
                    if (session.protocol == PROTOCOL_TCP) {
                        hadData = sessionManager.processTcpReturn(session, key) || hadData
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
     * 处理UDP回包
     */
    private fun processUdpReturn(session: ConnectionSession, sessionKey: String) {
        // UDP回包处理（类似TCP，但协议号不同）
        // 当前实现中UDP使用DatagramSocket，处理方式略有不同
        // 简化实现：UDP通常在forwardViaWifi中直接处理
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            logger.debug(message)
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

        // VPN停止时丢弃所有活跃会话的连接（N86：过期/无效会话不应归还连接池）
        if (::sessionManager.isInitialized) {
            sessionManager.clearAllConnections()
        }

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


