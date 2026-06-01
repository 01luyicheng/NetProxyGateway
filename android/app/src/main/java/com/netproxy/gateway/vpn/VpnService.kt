package com.netproxy.gateway.vpn

import kotlin.math.min

import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
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
import com.netproxy.gateway.proxy.PooledSocks5Connection
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
import kotlinx.coroutines.isActive
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

        /**
         * 将 VPN 状态重置为默认初始值。
         *
         * 将内部状态流 `_status` 的值设置为新的默认 `VpnStatus` 实例。
         */
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

    /**
     * 将传入的基础 Context 包装为 AppLocale 后传递给父类以应用语言/区域设置封装。
     *
     * @param newBase 要包装的原始 Context。
     */
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

    /**
     * 初始化服务：创建通知渠道、构建用于 IO 的协程作用域并重置 VPN 状态，同时（防御性地）重新注册语言变更监听器以确保运行时通知能随语言变更刷新。
     */
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

    /**
     * 处理启动服务的 Intent 并根据 action 控制 VPN 生命周期。
     *
     * 识别的 intent.action:
     * - `"START"`：启动 VPN。
     * - `"STOP"`：停止 VPN。
     *
     * @param intent 可能包含 `"START"` 或 `"STOP"` 的启动命令；为 null 时不执行任何操作。
     * @return `START_STICKY`，表示当系统终止服务后会尝试重建服务并重传最后一个 Intent。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startVpn()
            "STOP" -> stopVpn()
        }
        return START_STICKY
    }

    /**
     * 启动并配置 VPN 服务，建立 TUN 接口、初始化代理连接池并启动数据转发与回包处理协程。
     *
     * 在启动过程中会将服务置于前台并把状态先设置为 STARTING；解析并应用 DNS 配置后尝试建立 TUN（VPN 接口），
     * 成功时初始化 SOCKS5 连接池、创建用于回包注入的输出流、将状态置为 RUNNING，并启动处理 TUN 输入与代理回包的协程与代理前台服务；
     * 若建立失败或发生异常，则将状态设置为 ERROR 并包含错误信息。
     */
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

    /**
     * 从已建立的 TUN 接口循环读取数据包并交由处理逻辑分发，直至 VPN 状态不再为 RUNNING。
     *
     * 在循环中每次读取到有效数据包时会调用 processPacket(...) 进行路由与转发并触发过期会话清理。若在运行期间发生未捕获的异常，
     * 会将服务状态设置为 `VpnState.ERROR` 并记录异常信息。
     */
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

    /**
     * 根据包内的目标 IP、协议与端口信息，将单个从 TUN 读取到的 IP 数据报分流到对应的转发路径。
     *
     * 可能的分流结果：
     * - 内网流量（LOCAL_NETWORK）：绕过 VPN 并通过系统路由直接发送；
     * - DNS（DNS）：绕过 VPN 使用内网 DNS 解析并发送；
     * - 云服务器（CLOUD_SERVER）：在软件层面忽略（由系统或硬件分流处理）；
     * - 代理（PROXY）：通过本地 SOCKS5 代理转发到外网。
     *
     * @param packet 包含完整 IP 数据报的字节数组（至少包含 IP 头和必要的传输层头部）。
     * @param length packet 中有效数据的字节长度。
     */
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
     * 从 IPv4 包中提取目的 IPv4 地址的点分十进制字符串。
     *
     * @param packet 包字节数组（预计为完整或部分 IP 数据包）。
     * @param length packet 中有效字节长度。
     * @return `null` 如果包无效、不是 IPv4 或长度不足；否则返回目的 IPv4 地址（例如 "192.0.2.1"）。
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
     * 确定给定目标地址和传输信息应使用的路由类型。
     *
     * @param destinationIp 目标 IPv4 地址的点分十进制字符串。
     * @param protocol IP 层的协议号（例如 TCP=6、UDP=17）。
     * @param destinationPort 目标传输层端口；如果不可用则为 null。
     * @return `RouteType.DNS` 表示应走本地 DNS（Wi‑Fi）；`RouteType.LOCAL_NETWORK` 表示目标为内网地址并应直连；`RouteType.PROXY` 表示应走代理转发。 */
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

    /**
     * 解析并返回用于 VPN 的 DNS 服务器列表。
     *
     * 从应用配置中读取用户指定的 DNS 列表（如存在），否则使用内置默认列表，并返回最终用于 VPN 的 DNS 服务器地址字符串列表。
     *
     * @return 用于 VPN 的 DNS 服务器地址列表（字符串形式），优先使用用户配置，若无配置则返回默认服务器列表。
     */
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
     * 检查给定 IPv4 地址是否属于 RFC1918 定义的私有地址范围。
     *
     * @param ip 要检查的点分十进制 IPv4 字符串（例如 "192.168.0.1"）。
     * @return `true` 如果该地址是 RFC1918 私有 IPv4 地址，`false` 否则。
     */
    private fun isPrivateIp(ip: String): Boolean {
        return IpAddressUtils.isPrivateIpv4Rfc1918(ip)
    }

    /**
     * 将传入的 IPv4 TCP/UDP 数据包的传输层负载通过本地网络直连发送，尝试绕过 VPN 隧道。
     *
     * 详细行为：
     * - 仅对 TCP 与 UDP 生效；其它协议将被忽略。
     * - 使用系统的 protect(...) 使创建的 Socket 不走 VPN，但不保证流量一定走 WiFi（在多网络或厂商加速场景下路由可能仍被系统调整）。
     * - 对 UDP 使用 DatagramSocket，按单次数据包发送；对 TCP 建立到目标的短连接并发送负载（连接超时约 3000ms）。
     * - 任何异常会被记录并吞掉，不会抛出。
     *
     * @param packet 包含完整 IPv4 包的字节数组（用于解析传输层负载与端口）。
     * @param length packet 中有效字节长度。
     * @param destinationIp 目标 IPv4 地址的点分十进制字符串。
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
     * 将来自 TUN 的单个 IPv4 传输数据包通过本地 SOCKS5 代理转发并在连接池中复用或创建会话。
     *
     * 根据包内源/目的 IP 与端口以及协议构建会话键，在存在有效会话时复用其池中连接，
     * 否则从连接池借出连接、分配虚拟源 IP 并尝试建立新的会话；然后将传输层负载写入 SOCKS5 连接的输出流。
     * 在连接池不可用或发生错误时会记录警告并在可能的情况下将连接归还到池中。
     *
     * @param packet 包含完整 IPv4 报文的字节数组（IP 头 + 传输层头 + 负载）。
     * @param length packet 中有效字节长度。
     * @param destinationIp 目标 IPv4 地址的点分十进制字符串（用于向 SOCKS5 请求目标地址）。
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
            // 使用computeIfPresent原子检查并更新现有会话
            var sessionToUse: ConnectionSession? = activeConnections.computeIfPresent(connectionKey) { _, existingSession ->
                if (existingSession.pooledConnection?.isValid() == true) {
                    existingSession.updateActivity()
                    existingSession
                } else {
                    // 连接无效，在compute块内标记为null，后续清理
                    existingSession.pooledConnection?.let { pool.returnConnection(it) }
                    null
                }
            }

            // 如果没有有效会话，创建新会话
            if (sessionToUse == null) {
                val conn = pool.borrowConnection(
                    destinationIp = destinationIp,
                    destinationPort = destinationPort,
                    protectSocket = { protect(it) }
                )

                if (conn != null) {
                    val virtualSrcIp = getOrAllocateVirtualIp(destinationIp)
                    val newSession = ConnectionSession(
                        srcIp = srcIp,
                        srcPort = srcPort,
                        dstIp = destinationIp,
                        dstPort = destinationPort,
                        protocol = protocol,
                        pooledConnection = conn,
                        virtualSrcIp = virtualSrcIp
                    )
                    newSession.updateActivity()

                    // putIfAbsent确保不会覆盖其他线程刚创建的会话
                    val existing = activeConnections.putIfAbsent(connectionKey, newSession)
                    sessionToUse = if (existing != null) {
                        // 其他线程已创建会话，归还我们借用的连接
                        pool.returnConnection(conn)
                        existing
                    } else {
                        logDebug("Borrowed connection from pool: ${redactConnectionKey(connectionKey)} -> virtualIP: ${redactIp(virtualSrcIp)}")
                        newSession
                    }
                } else {
                    logger.warn("Failed to borrow connection from pool for ${redactConnectionKey(connectionKey)}")
                }
            }

            sessionToUse?.pooledConnection?.let { pooledConn ->
                pooledConn.socket.getOutputStream()?.write(packet, payloadInfo.first, payloadInfo.second)
                pooledConn.socket.getOutputStream()?.flush()
            }
        } catch (e: Exception) {
            logger.warn("Forward via SOCKS5 failed for ${redactConnectionKey(connectionKey)}", e)
            activeConnections.remove(connectionKey)?.pooledConnection?.let { pool.returnConnection(it) }
        }
    }

    /**
     * 从 IPv4 报文中提取源地址并以点分十进制字符串返回。
     *
     * 仅在输入包含完整 IPv4 首部且版本字段为 4 时返回地址；否则返回 `null`。
     *
     * @param packet 包含 IP 报文的数据缓冲区（整个报文或当前可用片段）。
     * @param length 缓冲区中有效数据的字节长度，用于边界检查。
     * @return 源 IPv4 地址，格式为 `a.b.c.d`；如果报文不是 IPv4 或长度不足（小于 20 字节）则返回 `null`。
     */
    private fun parseSourceIp(packet: ByteArray, length: Int): String? {
        if (length < 20) return null
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return null
        // 源IP在第12-15字节
        return "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}.${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
    }

    /**
     * 从 IPv4 数据包中解析出传输层的源端口。
     *
     * @param packet 包含完整或部分 IPv4 报文的字节数组（以网络字节序存放）。
     * @param length 数组中有效数据长度（报文总长），用于边界检查。
     * @return 源端口号（0 到 65535），无法解析或长度不足时返回 `null`。
     */
    private fun parseSourcePort(packet: ByteArray, length: Int): Int? {
        if (length < 20) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (length < headerLength + 2) return null
        // 源端口在传输层头的前2字节
        return ((packet[headerLength].toInt() and 0xFF) shl 8) or (packet[headerLength + 1].toInt() and 0xFF)
    }

    /**
     * 扫描并清理超时的会话：对已超过 CONNECTION_TIMEOUT_MS 未活动的会话，将其连接归还到连接池并从 activeConnections 中移除。
     *
     * 操作对每个会话以原子方式检查并移除，确保“检查-归还-移除”在并发环境下的一致性。
     */
    private fun cleanupStaleConnections() {
        val now = System.currentTimeMillis()
        val pool = socks5ConnectionPool

        // 使用ConcurrentHashMap的computeIfPresent原子操作，避免与forwardViaSocks5的竞态
        activeConnections.forEach { (key, session) ->
            activeConnections.computeIfPresent(key) { _, existingSession ->
                val isExpired = now - existingSession.lastActivity > CONNECTION_TIMEOUT_MS
                if (isExpired) {
                    try {
                        // 原子块内归还连接到连接池，确保"检查-归还-移除"三步一致
                        existingSession.pooledConnection?.let { pool?.returnConnection(it) }
                        logger.debug("Returned stale connection to pool: ${redactConnectionKey(key)}")
                    } catch (e: Exception) {
                        logger.warn("Failed to return stale connection to pool", e)
                    }
                    null // 返回null以移除该entry
                } else {
                    existingSession // 未过期，保留
                }
            }
        }
    }

    /**
     * 处理远端返回流量并将收到的数据包注入到 TUN 接口。
     *
     * 轮询处于活动状态的会话快照，分别处理 TCP 和 UDP 的回包（仅 TCP 实际注入），
     * 在无数据时采用平滑的指数退避以减少空闲期间的 CPU 轮询；在运行状态发生异常时记录错误日志。
     */
    private suspend fun processReturnTraffic() {
        var idleRounds = 0
        while (_status.value.state == VpnState.RUNNING) {
            try {
                var hadData = false
                // 创建快照避免遍历期间 map 修改导致视图不一致（P20 / C33）
                val snapshot = activeConnections.entries.toList()
                snapshot.forEach { (key, session) ->
                    // C33: 快照后验证session仍是当前活跃值，避免竞态下操作已归还的socket
                    val stillActive = activeConnections[key] === session
                    if (!stillActive) return@forEach
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
     * 计算基于连续空闲轮次的退避延迟。
     *
     * 使用平滑的指数退避：当有数据（idleRounds 为 0）时返回 1ms，空闲时按 base*2^(idleRounds-1) 增长并上限为 MAX_RETURN_TRAFFIC_IDLE_DELAY_MS。
     *
     * @param idleRounds 连续未收到数据的轮次数（0 表示本轮有数据）。
     * @return 退避延迟，单位为毫秒；当 `idleRounds == 0` 返回 `1`，否则返回计算后的延迟值并保证不超过 `MAX_RETURN_TRAFFIC_IDLE_DELAY_MS`。
     */
    private fun calculateIdleDelay(idleRounds: Int): Long {
        if (idleRounds == 0) return 1L
        // 基础延迟2ms，指数增长，最大MAX_RETURN_TRAFFIC_IDLE_DELAY_MS
        val baseDelay = 2L
        val exponent = minOf(idleRounds - 1, 6) // 限制指数最大为6，避免过大数值
        return minOf(baseDelay shl exponent, MAX_RETURN_TRAFFIC_IDLE_DELAY_MS)
    }

    /**
     * 原子地移除指定会话并（如有）将其连接归还到 SOCKS5 连接池。
     *
     * @return `true` 如果映射中存在与提供的 `session` 相同的会话且已被移除并归还连接，`false` 否则。
     */
    private fun removeSessionAndReturnConnection(sessionKey: String, session: ConnectionSession): Boolean {
        var removed = false
        activeConnections.computeIfPresent(sessionKey) { _, existing ->
            if (existing === session) {
                existing.pooledConnection?.let { socks5ConnectionPool?.returnConnection(it) }
                removed = true
                null
            } else existing
        }
        return removed
    }

    /**
     * 处理并注入来自 SOCKS5 连接的 TCP 回包到 TUN。
     *
     * 从指定会话的已池化连接读取可用字节，构造 IPv4+TCP 的回包并写入到 TUN 输出流。若会话不再是当前映射值、连接不可用、构造包失败或注入失败，函数会原子地从 activeConnections 移除该会话并尝试将连接归还到连接池。
     *
     * @param session 会话对象，包含 pooledConnection、虚拟源 IP、源/目的端口及活动时间等信息。
     * @param sessionKey 用于在 activeConnections 中验证当前会话实例并在必要时移除该会话的键。
     * @return `true` 如果成功读取数据并成功构造且注入回包，`false` 否则。
     */
    private fun processTcpReturn(session: ConnectionSession, sessionKey: String): Boolean {
        // C33: 验证session仍是当前活跃值，防止快照后session被并发移除/替换
        if (activeConnections[sessionKey] !== session) return false
        val pooledConn = session.pooledConnection ?: return false
        if (!pooledConn.isValid()) {
            // P12: isValid()已包含socket.isClosed检查，移除冗余条件
            // 连接无效，原子移除并归还到连接池（仅当session仍是当前值时）
            removeSessionAndReturnConnection(sessionKey, session)
            return false
        }

        try {
            val input = pooledConn.socket.getInputStream()
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
                        removeSessionAndReturnConnection(sessionKey, session)
                        return false
                    }
                    // 注入TUN
                    if (!injectPacket(buffer, packetLen)) {
                        logger.warn("Failed to inject TCP return packet for ${redactConnectionKey(sessionKey)}, closing session")
                        removeSessionAndReturnConnection(sessionKey, session)
                        return false
                    }
                    session.updateActivity()
                    return true
                }
            }
            return false
        } catch (e: Exception) {
            logger.warn("TCP return traffic error for ${redactConnectionKey(sessionKey)}: ${e.message}")
            // P13: 原子移除并归还，仅当session仍是当前值时
            removeSessionAndReturnConnection(sessionKey, session)
            return false
        }
    }

    /**
     * 处理来自 SOCKS5 会话的 UDP 返回数据并将其注入到 TUN，以支持从代理回包的转发（当前为简化实现，不进行实际转发）。
     *
     * 此函数保留用于将来实现：读取会话的 UDP 返回数据并构建/注入对应的回包到虚拟网卡。当前实现为空操作，UDP 的正常转发在 forwardViaWifi 中处理。
     *
     * @param session 活动的连接会话，包含用于读写的 pooledConnection、分配的虚拟源 IP、源/目的端口等会话元数据。
     * @param sessionKey 标识该会话的键（格式为 "$srcIp:$srcPort-$dstIp:$dstPort"），用于在会话映射中验证或移除对应条目。
     */
    private fun processUdpReturn(session: ConnectionSession, sessionKey: String) {
        // UDP回包处理（类似TCP，但协议号不同）
        // 当前实现中UDP使用DatagramSocket，处理方式略有不同
        // 简化实现：UDP通常在forwardViaWifi中直接处理
    }

    /**
     * 在提供的缓冲区中构造一个完整的 IPv4 + TCP 回包，用于注入到 TUN 接口。
     *
     * 函数会将会话的虚拟源 IP 作为 IP 包的源地址、会话的原始源 IP 作为目标地址，填充固定长度的 IP 与 TCP 头并计算相应校验和，然后在头部之后保留 payloadLen 字节用于载荷。
     *
     * @param buffer 用于写入构造好包的目标缓冲区；必须至少能容纳 IP 头 + TCP 头 + payloadLen 字节。
     * @param session 提供虚拟源 IP、原始源/目标端口及协议等信息的会话对象。
     * @param payloadLen 要在包中保留的载荷长度（字节数）。
     * @return 返回写入到缓冲区的完整包长度（IP 头 + TCP 头 + payloadLen），在以下情况返回 `0`：
     * - payloadLen 小于 0 或超出缓冲区可用空间；
     * - 解析会话中 IP 地址失败或其他校验不通过时。
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

    /**
     * 将点分十进制的 IPv4 字符串解析为四个 0–255 范围内的整数组成的列表。
     *
     * @param ip 要解析的 IPv4 字符串（例如 "192.168.0.1"）。
     * @return 包含四个 0–255 整数的 `List<Int>`，如果输入不是四段或任一段无法解析为 0–255 的整数则返回 `null`。
     */
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
     * 计算并返回给定字节数组指定范围的一位反码（16 位）校验和，用于 IPv4 头或 TCP/UDP 伪首部校验。
     *
     * @param data 要计算的字节数组。
     * @param offset 起始字节索引（包含）。
     * @param length 要计算的字节长度。
     * @return 取值范围为 0 到 0xFFFF 的 16 位校验和值。
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
     * 计算并返回给定 TCP 报文（含伪头）的校验和。
     *
     * @param buffer 包含完整 IPv4 首部（20 字节）后接 TCP 首部与负载的字节数组。
     * @param srcIp 源 IPv4 地址的 4 个字节表示（每项 0..255），按顺序 [b0, b1, b2, b3]。
     * @param dstIp 目的 IPv4 地址的 4 个字节表示（每项 0..255），按顺序 [b0, b1, b2, b3]。
     * @param protocol IP 协议号（例如 TCP 为 6）。
     * @param tcpHeaderLen TCP 首部长度（字节数）。
     * @param payloadLen TCP 负载长度（字节数）。
     * @return 16 位的 TCP 校验和（0..65535）。
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
     * 将给定字节数组中的数据写入并注入到 TUN 接口的输出流。
     *
     * @param packet 要注入的字节数组（包含 IP/传输层报文）。
     * @param length 要写入的字节数，从 `packet` 开始处计数。
     * @return `true` 表示数据已成功写入并刷新到输出流，`false` 表示未写入（例如输出流不可用或发生错误）。
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
     * 为指定的目标真实 IP 获取对应的虚拟 IP；若尚未分配则分配一个新的虚拟 IP。
     *
     * @param realDstIp 目标主机的真实 IPv4 地址（点分十进制表示）。
     * @return 已分配或已存在的虚拟 IPv4 地址字符串。
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

    /**
     * 在缺少身份验证会话时记录一次被节流的警告日志。
     *
     * 以 SESSION_MISSING_LOG_INTERVAL_MS 为间隔进行节流，超过该间隔才会记录新的警告。
     *
     * @param destinationIp 发生缺失会话的目标 IP（用于日志记录，会被部分脱敏）。 
     */
    private fun logMissingSession(destinationIp: String) {
        val nowMs = System.currentTimeMillis()
        val lastMs = lastMissingSessionLogAt.get()
        if (nowMs - lastMs >= SESSION_MISSING_LOG_INTERVAL_MS &&
            lastMissingSessionLogAt.compareAndSet(lastMs, nowMs)
        ) {
            logger.warn("Skip SOCKS5 forward: missing auth session for ${redactIp(destinationIp)}")
        }
    }

    /**
     * 在调试构建（BuildConfig.DEBUG 为 true）时记录一条调试级别日志。
     *
     * @param message 要记录的日志消息
     */
    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            logger.debug(message)
        }
    }

    /**
     * 读取 IPv4 包头中的协议字段并返回协议号。
     *
     * @param packet 包含 IPv4 报文（从 IPv4 首部起始处）的字节数组。
     * @return IPv4 协议字段的数值（0 到 255）。
     */
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
     * 查找 IPv4 数据报中传输层有效负载在 packet 内的起始偏移和长度（不分配新数组）。
     *
     * 支持 TCP 与 UDP；当包无效、太短或没有有效负载时返回 null。
     *
     * @param packet 包含完整或部分 IP 数据报的字节数组
     * @param length packet 中有效数据的字节数
     * @return 起始偏移与长度的 Pair（offset, length），或在包无效/无负载时返回 `null`
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

    /**
     * 从 IPv4 包中提取传输层（TCP 或 UDP）的载荷并返回其字节副本。
     *
     * 已弃用：此方法会进行数组拷贝。请使用 `extractTransportPayloadInfo(packet, length)` 获取载荷的起始偏移与长度以避免拷贝。
     *
     * @param packet 包含完整 IPv4 数据包的字节数组。
     * @param length packet 中有效数据的长度（通常为从 TUN 读取的字节数）。
     * @return 若能解析出传输层载荷则返回该载荷的字节副本；否则返回空字节数组。
     */
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
        /**
         * 更新会话的活动时间并标记其关联的池化连接为已使用。
         *
         * 将 `lastActivity` 设置为当前时间戳；如果 `pooledConnection` 不为空，则调用其 `markUsed()` 来刷新连接的使用状态。
         */
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

    /**
     * 为 VPN 前台通知创建并注册通知渠道（在 Android O 及以上生效）。
     *
     * 通道使用低重要性（NotificationManager.IMPORTANCE_LOW），并采用本地化的名称与描述。
     * 所用通道 ID 为 `NOTIFICATION_CHANNEL_ID`。
     */
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

    /**
     * 构建用于前台服务的通知，显示应用名称与 VPN 状态文本并在点击时打开主界面。
     *
     * 通知使用已定义的通知渠道 ID、不可变的 PendingIntent 指向 MainActivity，设置为 ongoing（不可滑动移除）。
     *
     * @return 已配置好的 Notification 实例，用于 startForeground()。
     */
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
     * 注册语言变更监听器，在语言切换时刷新正在运行的 VPN 前台通知。
     *
     * 只有当 VPN 当前状态为 `VpnState.RUNNING` 时才会调用 `updateNotification()` 以更新通知文案。
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
     * 注销已注册的应用语言变更监听器。
     *
     * 调用后将移除使用固定监听器 ID 注册的语言变更回调，防止在服务销毁或不再需要时继续接收语言更新通知。
     */
    private fun unregisterLanguageChangeListener() {
        AppLocale.unregisterLanguageChangeListener(LANGUAGE_LISTENER_ID)
    }

    /**
     * 刷新并发布前台服务通知以反映当前语言设置。
     *
     * 在 API 26 及以上会确保通知渠道存在，然后通过 NotificationManager 更新已存在的前台通知。
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

    /**
     * 以原子方式停止 VPN 服务并清理相关资源。
     *
     * 在调用时会：若当前已处于 `STOPPED` 或 `STOPPING` 则立即返回；使用 CAS 保证只有一个停止流程执行；
     * 将状态设置为 `STOPPING`，取消并释放协程作用域，执行一次性资源清理（包含关闭 TUN、归还/关闭代理连接等），
     * 停止代理服务并使服务脱离前台，最后将状态设置为 `STOPPED`。方法在完成或异常退出时会重置停止标志，允许后续再次启动/停止。
     */
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
     * 释放并清理与 VPN 相关的所有资源。
     *
     * 关闭并置空 VPN 输出流与接口，归还或关闭所有会话中持有的 SOCKS5 连接，关闭并清空连接池，清除虚拟 IP 池与反向映射。
     * 该操作为幂等且只会执行一次（受原子标志保护），在关闭各类资源时会捕获异常以避免中途失败导致未完成的清理。
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

    /**
     * 停止已启动的 Socks5 代理服务（如果正在运行）。
     */
    private fun stopProxyService() {
        val intent = Intent(this, Socks5ProxyService::class.java)
        stopService(intent)
    }

    /**
     * 在服务被系统销毁时执行必要的清理并保证 VPN 与代理相关资源被安全释放。
     *
     * 如果此前未调用 `stopVpn()`，将把状态置为 `STOPPED` 并停止代理服务以作兜底；无论 `stopVpn()` 是否已调用，
     * 都会调用 `cleanupVpnResources()`、取消并置空协程作用域、注销语言变更监听，最后委托给父类的 `onDestroy()`。
     */
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

    /**
     * 在系统撤销 VPN 授权时停止正在运行的 VPN 并委托父类处理回收。
     */
    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}


