package com.netproxy.gateway.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.netproxy.gateway.connection.AuthSessionStore
import com.netproxy.gateway.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import javax.inject.Inject

/**
 * 移动端 SOCKS5 代理服务器
 * 
 * 角色说明:
 * - 本服务在手机上运行，监听 1080 端口
 * - 工程师通过连接手机的 SOCKS5 代理来访问客户内网
 * - 流量路径: 工程师 → 手机:1080 → VPN → WiFi网卡 → 内网设备
 * 
 * 与云端 SOCKS5 的区别:
 * - 移动端 SOCKS5: 服务器模式，供工程师连接访问内网
 * - 云端 SOCKS5: 代理模式，中转外网流量到公网
 */
@AndroidEntryPoint
class Socks5ProxyService : Service() {

    companion object {
        private val logger = LoggerFactory.getLogger(Socks5ProxyService::class.java)
        const val PROXY_PORT = 1080
        const val CHANNEL_ID = "proxy_service_channel"
        private const val MAX_WORKER_THREADS = 2
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null
    private var serverChannel: Channel? = null

    @Inject
    lateinit var authSessionStore: AuthSessionStore

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())
        startProxyServer()
        return START_STICKY
    }

    private fun startProxyServer() {
        serviceScope.launch {
            try {
                bossGroup = NioEventLoopGroup(1)
                val workerThreads = minOf(MAX_WORKER_THREADS, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
                workerGroup = NioEventLoopGroup(workerThreads)

                val bootstrap = ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel::class.java)
                    .childHandler(object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            ch.pipeline().addLast(
                                LoggingHandler(LogLevel.WARN),
                                SocksPortUnificationServerHandler(),
                                Socks5ProxyHandler(
                                    credentialValidator = { username, password ->
                                        authSessionStore.isValid(username, password)
                                    }
                                )
                            )
                        }
                    })

                val socketAddress = InetSocketAddress("127.0.0.1", PROXY_PORT)
                serverChannel = bootstrap.bind(socketAddress).sync().channel()
                serverChannel?.closeFuture()?.sync()

            } catch (e: Exception) {
                logger.error("SOCKS5 server failed to start", e)
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SOCKS5 Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running SOCKS5 proxy server"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NetProxyGateway")
            .setContentText("SOCKS5 Proxy Running on port $PROXY_PORT")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        bossGroup?.shutdownGracefully()
        workerGroup?.shutdownGracefully()
        serverChannel?.close()
        super.onDestroy()
    }
}
