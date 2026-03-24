package com.netproxy.gateway.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService as AndroidVpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.netproxy.gateway.connection.AuthSessionStore
import com.netproxy.gateway.ui.MainActivity
import com.netproxy.gateway.proxy.Socks5ProxyService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

enum class VpnState {
    STOPPED,
    STARTING,
    RUNNING,
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
        private const val TAG = "VpnService"
        private const val NOTIFICATION_CHANNEL_ID = "vpn_service_channel"
        private const val NOTIFICATION_ID = 100
        private const val PACKET_BUFFER_SIZE = 32 * 1024
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        
        const val VPN_ADDRESS = "10.0.0.2"
        const val VPN_ROUTE = "0.0.0.0"
        const val VPN_DNS = "8.8.8.8"
        const val VPN_MTU = 1500
        
        // SOCKS5 代理本地端口
        const val SOCKS5_PROXY_HOST = "127.0.0.1"
        const val SOCKS5_PROXY_PORT = 1080
        
        // 预分配的静态缓冲区，用于SOCKS5握手（避免频繁创建小数组）
        private val SOCKS5_METHOD_REQUEST = byteArrayOf(0x05, 0x01, 0x02)
        private val SOCKS5_AUTH_VERSION = byteArrayOf(0x01)
        private val SOCKS5_CONNECT_HEADER = byteArrayOf(0x05, 0x01, 0x00, 0x01)
        private val EMPTY_BYTE_ARRAY = ByteArray(0)
        
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject
    lateinit var authSessionStore: AuthSessionStore
    private var vpnInterface: ParcelFileDescriptor? = null
    
    // 活跃的代理连接映射
    private val activeConnections = ConcurrentHashMap<String, ConnectionInfo>()
    private val packetBuffer = ByteArray(PACKET_BUFFER_SIZE)
    
    private val _status = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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

        startForeground(NOTIFICATION_ID, createNotification())

        _status.value = VpnStatus(state = VpnState.STARTING)

        try {
            val builder = Builder()
                .setSession("NetProxyGateway")
                .setMtu(VPN_MTU)
                .addAddress(VPN_ADDRESS, 32)
                .addRoute(VPN_ROUTE, 0)
                .addDnsServer(VPN_DNS)
                .setBlocking(true)

            val configureIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setConfigureIntent(configureIntent)

            vpnInterface = builder.establish()
            
            if (vpnInterface != null) {
                _status.value = VpnStatus(state = VpnState.RUNNING)
                
                serviceScope.launch {
                    processVpnTraffic()
                }
                
                startProxyService()
            } else {
                _status.value = VpnStatus(
                    state = VpnState.ERROR,
                    errorMessage = "Failed to establish VPN"
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
        val inputStream = FileInputStream(vpnFd.fileDescriptor)

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
                android.util.Log.e(TAG, "VPN traffic loop failed", e)
                _status.value = VpnStatus(
                    state = VpnState.ERROR,
                    errorMessage = e.message
                )
            }
        }
    }

    private fun processPacket(packet: ByteArray, length: Int) {
        if (length <= 0) return

        // 解析 IP 包获取目标地址
        val destinationIp = parseDestinationIp(packet, length) ?: return
        
        // 根据目标 IP 判断流量类型
        val routeType = determineRouteType(destinationIp)
        
        when (routeType) {
            RouteType.LOCAL_NETWORK -> {
                // 内网流量：通过 WiFi 网卡直连
                forwardViaWifi(packet, length, destinationIp)
            }
            RouteType.DNS -> {
                // DNS 查询：走 WiFi（使用内网 DNS）
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
    private fun determineRouteType(destinationIp: String): RouteType {
        // 检查是否是 DNS
        if (destinationIp in DNS_SERVERS) {
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
     * 判断是否是私有 IP 地址
     */
    private fun isPrivateIp(ip: String): Boolean {
        try {
            val parts = ip.split(".").map { it.toInt() }
            if (parts.size != 4) return false

            // 10.0.0.0/8
            if (parts[0] == 10) return true

            // 172.16.0.0/12
            if (parts[0] == 172 && parts[1] in 16..31) return true

            // 192.168.0.0/16
            if (parts[0] == 192 && parts[1] == 168) return true

            return false
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * 通过 WiFi 网卡直连（内网流量）
     * 注意：Android VPN 模式下需要使用 Network.bindSocket() 
     * 或者配置 excludeRoute 来绕过 VPN
     */
    private fun forwardViaWifi(packet: ByteArray, length: Int, destinationIp: String) {
        try {
            val protocol = parseProtocol(packet)
            val destinationPort = parseDestinationPort(packet, length) ?: return
            val payload = extractTransportPayload(packet, length)

            when (protocol) {
                17 -> {
                    // UDP
                    DatagramSocket().use { socket ->
                        protect(socket)
                        val datagram = DatagramPacket(payload, payload.size, InetAddress.getByName(destinationIp), destinationPort)
                        socket.send(datagram)
                    }
                }
                6 -> {
                    // TCP best-effort forwarding
                    Socket().use { socket ->
                        protect(socket)
                        socket.connect(InetSocketAddress(destinationIp, destinationPort), 3000)
                        socket.getOutputStream().write(payload)
                        socket.getOutputStream().flush()
                    }
                }
                else -> {
                    android.util.Log.d(TAG, "Skip unsupported protocol=$protocol for WiFi route")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Forward via WiFi failed for $destinationIp", e)
        }
    }
    
    /**
     * 通过本地 SOCKS5 代理转发
     */
    private fun forwardViaSocks5(packet: ByteArray, length: Int, destinationIp: String) {
        val destinationPort = parseDestinationPort(packet, length) ?: return
        val payload = extractTransportPayload(packet, length)
        val session = authSessionStore.getCurrentSession() ?: return
        val connectionKey = "$destinationIp:$destinationPort"

        try {
            val connection = activeConnections[connectionKey]
            val socket = if (connection?.localSocket?.isConnected == true) {
                connection.localSocket
            } else {
                createSocks5Tunnel(
                    destinationIp = destinationIp,
                    destinationPort = destinationPort,
                    username = session.deviceId,
                    password = session.authToken
                ).also {
                    activeConnections[connectionKey] = ConnectionInfo(
                        remoteAddress = destinationIp,
                        remotePort = destinationPort,
                        localSocket = it
                    )
                }
            }

            socket?.getOutputStream()?.write(payload)
            socket?.getOutputStream()?.flush()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Forward via SOCKS5 failed for $destinationIp:$destinationPort", e)
            activeConnections.remove(connectionKey)?.localSocket?.close()
        }
    }

    private fun createSocks5Tunnel(
        destinationIp: String,
        destinationPort: Int,
        username: String,
        password: String
    ): Socket {
        val socket = Socket().apply {
            protect(this)
            connect(InetSocketAddress(SOCKS5_PROXY_HOST, SOCKS5_PROXY_PORT), 3000)
            soTimeout = 3000
        }

        val output = socket.getOutputStream()
        val input = socket.getInputStream()

        // auth method negotiation - 使用预分配的静态缓冲区
        output.write(SOCKS5_METHOD_REQUEST)
        output.flush()
        val methodResponse = ByteArray(2)
        readFully(input, methodResponse)
        require(methodResponse[0].toInt() == 0x05 && methodResponse[1].toInt() == 0x02) {
            "SOCKS5 password auth negotiation failed"
        }

        // username/password auth - 复用临时缓冲区
        val userBytes = username.toByteArray(Charsets.UTF_8)
        val passBytes = password.toByteArray(Charsets.UTF_8)
        require(userBytes.size <= 255 && passBytes.size <= 255) { "SOCKS5 credentials too long" }
        
        // 使用临时缓冲区数组避免多次小数组创建
        output.write(SOCKS5_AUTH_VERSION)
        output.write(userBytes.size)
        output.write(userBytes)
        output.write(passBytes.size)
        output.write(passBytes)
        output.flush()
        
        val authResponse = ByteArray(2)
        readFully(input, authResponse)
        require(authResponse[1].toInt() == 0x00) { "SOCKS5 authentication failed" }

        // connect to destination - 使用预分配的静态缓冲区
        val addressBytes = InetAddress.getByName(destinationIp).address
        output.write(SOCKS5_CONNECT_HEADER)
        output.write(addressBytes)
        output.write(byteArrayOf((destinationPort shr 8).toByte(), (destinationPort and 0xFF).toByte()))
        output.flush()

        val connectHeader = ByteArray(4)
        readFully(input, connectHeader)
        require(connectHeader[1].toInt() == 0x00) { "SOCKS5 connect failed: ${connectHeader[1].toInt()}" }

        val boundAddressLength = when (connectHeader[3].toInt()) {
            0x01 -> 4
            0x03 -> {
                val lenBuffer = ByteArray(1)
                readFully(input, lenBuffer)
                lenBuffer[0].toInt() and 0xFF
            }
            0x04 -> 16
            else -> throw IllegalStateException("Unsupported SOCKS5 ATYP ${connectHeader[3].toInt()}")
        }
        // 复用packetBuffer读取绑定地址（避免创建临时大数组）
        if (boundAddressLength + 2 <= PACKET_BUFFER_SIZE) {
            readFully(input, packetBuffer, 0, boundAddressLength + 2)
        } else {
            readFully(input, ByteArray(boundAddressLength + 2))
        }

        return socket
    }

    private fun readFully(input: java.io.InputStream, target: ByteArray) {
        readFully(input, target, 0, target.size)
    }
    
    private fun readFully(input: java.io.InputStream, target: ByteArray, offset: Int, length: Int) {
        var currentOffset = offset
        val endOffset = offset + length
        while (currentOffset < endOffset) {
            val read = input.read(target, currentOffset, endOffset - currentOffset)
            if (read < 0) {
                throw IllegalStateException("Unexpected EOF while reading SOCKS5 stream")
            }
            currentOffset += read
        }
    }

    private fun cleanupStaleConnections() {
        val now = System.currentTimeMillis()
        activeConnections.entries.removeIf { entry ->
            val isExpired = now - entry.value.createdAt > CONNECTION_TIMEOUT_MS
            if (isExpired) {
                try {
                    entry.value.localSocket?.close()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to close stale connection socket", e)
                }
            }
            isExpired
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
     * 连接信息
     */
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
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "NetProxyGateway VPN is running"
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
            .setContentTitle("NetProxyGateway")
            .setContentText("VPN service is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun stopVpn() {
        _status.value = VpnStatus(state = VpnState.STOPPED)
        
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to close VPN interface", e)
        }

        activeConnections.values.forEach { info ->
            try {
                info.localSocket?.close()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to close connection socket during VPN stop", e)
            }
        }
        activeConnections.clear()
        
        stopProxyService()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun stopProxyService() {
        val intent = Intent(this, Socks5ProxyService::class.java)
        stopService(intent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}
