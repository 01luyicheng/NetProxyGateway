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
import com.netproxy.gateway.ui.MainActivity
import com.netproxy.gateway.proxy.Socks5ProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.io.OutputStream
import java.io.InputStream
import java.net.Socket

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

class GatewayVpnService : AndroidVpnService() {

    companion object {
        private const val TAG = "VpnService"
        private const val NOTIFICATION_CHANNEL_ID = "vpn_service_channel"
        private const val NOTIFICATION_ID = 100
        
        const val VPN_ADDRESS = "10.0.0.2"
        const val VPN_ROUTE = "0.0.0.0"
        const val VPN_DNS = "8.8.8.8"
        const val VPN_MTU = 1500
        
        // SOCKS5 代理本地端口
        const val SOCKS5_PROXY_HOST = "127.0.0.1"
        const val SOCKS5_PROXY_PORT = 1080
        
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
    private var vpnInterface: ParcelFileDescriptor? = null
    
    // 活跃的代理连接映射
    private val activeConnections = ConcurrentHashMap<String, ConnectionInfo>()
    
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
        val packet = ByteArray(32767)

        try {
            while (_status.value.state == VpnState.RUNNING) {
                val length = inputStream.read(packet)
                if (length > 0) {
                    processPacket(packet, length)
                }
            }
        } catch (e: Exception) {
            if (_status.value.state == VpnState.RUNNING) {
                _status.value = VpnStatus(
                    state = VpnState.ERROR,
                    errorMessage = e.message
                )
            }
        }
    }

    private fun processPacket(packet: ByteArray, length: Int) {
        // 解析 IP 包获取目标地址
        val destinationIp = parseDestinationIp(packet, length) ?: return
        
        // 根据目标 IP 判断流量类型
        val routeType = determineRouteType(destinationIp)
        
        when (routeType) {
            RouteType.LOCAL_NETWORK -> {
                // 内网流量：通过 WiFi 网卡直连
                // 在实际实现中，需要绕过 VPN 直接发送
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
            
            val ipInt = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
            
            // 10.0.0.0/8
            val range10 = (10 shl 24) // 10.0.0.0
            if ((ipInt and (0xFF shl 24)) == range10) return true
            
            // 172.16.0.0/12
            val range172 = (172 shl 24) or (16 shl 16)
            val range172End = (172 shl 24) or (31 shl 16)
            if (ipInt >= range172 && ipInt < range172End) return true
            
            // 192.168.0.0/16
            val range192 = (192 shl 24) or (168 shl 16)
            if ((ipInt and 0xFFFF0000.toInt()) == range192) return true
            
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
        // TODO: 实现通过 WiFi 直连
        // 方法1: 使用 Network.bindSocket() 绑定到特定网络
        // 方法2: 在 Android 13+ 使用 excludeRoute 排除内网段
        
        // 当前为存根实现，日志记录
        android.util.Log.d(TAG, "Forward to WiFi: $destinationIp")
    }
    
    /**
     * 通过本地 SOCKS5 代理转发
     */
    private fun forwardViaSocks5(packet: ByteArray, length: Int, destinationIp: String) {
        // TODO: 实现 SOCKS5 代理转发
        // 1. 解析 TCP/UDP 头获取端口
        // 2. 建立到 SOCKS5 代理的连接
        // 3. 转发数据包
        
        // 当前为存根实现
        android.util.Log.d(TAG, "Forward to SOCKS5 proxy: $destinationIp")
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
            // Handle cleanup
        }
        
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
