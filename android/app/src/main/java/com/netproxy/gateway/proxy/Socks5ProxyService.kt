package com.netproxy.gateway.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.netproxy.gateway.R
import com.netproxy.gateway.connection.AuthSessionStore
import com.netproxy.gateway.i18n.AppLocale
import com.netproxy.gateway.ui.MainActivity
import com.netproxy.gateway.utils.securelyClear
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
        private const val LANGUAGE_LISTENER_ID = "socks5_service"
        private const val NOTIFICATION_ID = 1
        const val PROXY_PORT = 1080
        const val CHANNEL_ID = "proxy_service_channel"
        private const val MAX_WORKER_THREADS = 2
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null
    private @Volatile var serverChannel: Channel? = null

    @Inject
    lateinit var authSessionStore: AuthSessionStore

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // H27: 注册语言变更监听，运行中的通知会自动刷新
        // I1: 防御性注销，防止系统强制杀死后残留监听器
        AppLocale.unregisterLanguageChangeListener(LANGUAGE_LISTENER_ID)
        registerLanguageChangeListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
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
                                        // Netty SOCKS5 处理器传入的是 String，这是库的限制
                                        val passwordArray = password.toCharArray()
                                        try {
                                            authSessionStore.isValid(username, passwordArray)
                                        } finally {
                                            passwordArray.securelyClear()
                                        }
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
                bossGroup?.shutdownGracefully()
                workerGroup?.shutdownGracefully()
                stopSelf()
            } finally {
                serverChannel = null
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                AppLocale.getString(this, R.string.notification_proxy_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = AppLocale.getString(this@Socks5ProxyService, R.string.notification_proxy_channel_description)
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
            .setContentTitle(AppLocale.getString(this, R.string.app_name))
            .setContentText(AppLocale.getString(this, R.string.notification_proxy_content_text_format, PROXY_PORT))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * H27: 注册语言变更监听
     * 当用户切换语言时，刷新运行中的代理服务通知文案
     */
    private fun registerLanguageChangeListener() {
        AppLocale.registerLanguageChangeListener(LANGUAGE_LISTENER_ID) { _ ->
            if (shouldRefreshNotificationOnLanguageChange()) {
                updateNotification()
            }
        }
    }

    internal fun shouldRefreshNotificationOnLanguageChangeForTesting(): Boolean =
        shouldRefreshNotificationOnLanguageChange()

    private fun shouldRefreshNotificationOnLanguageChange(): Boolean = isProxyChannelActive()

    internal fun isProxyChannelActiveForTesting(): Boolean = isProxyChannelActive()

    internal fun setServerChannelForTesting(channel: Channel?) {
        serverChannel = channel
    }

    private fun isProxyChannelActive(): Boolean = serverChannel?.isActive == true

    /**
     * H27: 刷新前台服务通知文案
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
            logger.debug("Proxy service notification updated for language change")
        } catch (e: Exception) {
            logger.error("Failed to update proxy service notification", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // H27: 注销语言变更监听
        AppLocale.unregisterLanguageChangeListener(LANGUAGE_LISTENER_ID)

        serviceScope.cancel()
        bossGroup?.shutdownGracefully()
        workerGroup?.shutdownGracefully()
        serverChannel?.close()
        super.onDestroy()
    }
}
