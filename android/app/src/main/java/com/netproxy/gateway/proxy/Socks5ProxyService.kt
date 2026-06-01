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

    /**
     * 在附加基础 Context 前使用 AppLocale 包装以启用应用级本地化。
     *
     * @param newBase 要附加的基础 Context，会被 AppLocale.wrap 包装后传递给超类。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null
    private @Volatile var serverChannel: Channel? = null

    @Inject
    lateinit var authSessionStore: AuthSessionStore

    /**
     * 初始化服务：创建通知通道并注册语言变更监听器以在语言切换时刷新前台通知。
     *
     * 在注册前会先尝试注销同 ID 的监听器以防止系统在进程被强制终止后遗留的监听器造成重复注册或冲突。
     */
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // H27: 注册语言变更监听，运行中的通知会自动刷新
        // I1: 防御性注销，防止系统强制杀死后残留监听器
        AppLocale.unregisterLanguageChangeListener(LANGUAGE_LISTENER_ID)
        registerLanguageChangeListener()
    }

    /**
     * 将服务提升为前台并启动 SOCKS5 代理服务器。
     *
     * 启动前台通知以保持服务运行并异步启动代理服务器的网络组件。
     *
     * @return `START_STICKY` — 如果系统在资源允许时终止该服务，会尝试重建服务（重建时传入的 Intent 可能为 null）。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        startProxyServer()
        return START_STICKY
    }

    /**
     * 在后台协程中启动并运行本地 SOCKS5 代理服务器，管理其生命周期和运行状态。
     *
     * 此方法会启动 Netty 事件循环组并在 127.0.0.1:PROXY_PORT 上绑定一个服务器通道，将绑定的通道保存到 `serverChannel` 以表示代理处于激活状态；
     * 代理的连接处理使用支持 SOCKS5 认证的处理器，认证由 `authSessionStore` 提供。
     *
     * 在启动或运行过程中发生异常时，会优雅地关闭已创建的事件循环组并调用 `stopSelf()` 停止服务。
     * 无论成功或失败，方法结束时会将 `serverChannel` 置为 `null` 以释放状态。
     */
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
                bossGroup?.shutdownGracefully()
                workerGroup?.shutdownGracefully()
                stopSelf()
            } finally {
                serverChannel = null
            }
        }
    }

    /**
     * 在 Android O（API 26）及以上创建并注册代理服务使用的通知通道。
     *
     * 通道使用 `CHANNEL_ID`，通道名称和描述均从资源获取，重要性设置为低；仅在 `Build.VERSION.SDK_INT >= O` 时执行创建并注册。
     */
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

    /**
     * 构建用于前台服务的通知，显示应用名称与包含代理端口的提示文本，并在点击时打开主界面。
     *
     * @return 带有应用名称作为标题、使用 `PROXY_PORT` 格式化的内容文本、默认小图标以及跳转到 `MainActivity` 的 `PendingIntent` 的 `Notification`。
     */
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
     * 注册应用语言变更监听器，在语言变更且代理正在运行时刷新前台通知。
     *
     * 向 AppLocale 注册一个使用常量 `LANGUAGE_LISTENER_ID` 的监听回调；当收到语言变更事件且代理通道处于活动状态时，会重新构建并更新前台通知以反映新的语言资源。
     */
    private fun registerLanguageChangeListener() {
        AppLocale.registerLanguageChangeListener(LANGUAGE_LISTENER_ID) { _ ->
            if (shouldRefreshNotificationOnLanguageChange()) {
                updateNotification()
            }
        }
    }

    /**
         * 确定在语言变更时是否应刷新前台通知（用于测试）。
         *
         * @return `true` 如果在语言变更时应刷新通知，`false` 否则。
         */
        internal fun shouldRefreshNotificationOnLanguageChangeForTesting(): Boolean =
        shouldRefreshNotificationOnLanguageChange()

    /**
 * 判断在语言变更时是否需要刷新前台通知。
 *
 * @return `true` 如果代理通道当前处于活动状态，`false` 否则。
 */
private fun shouldRefreshNotificationOnLanguageChange(): Boolean = isProxyChannelActive()

    /**
 * 检查代理通道是否处于活动状态（供测试使用）。
 *
 * @return `true` 如果代理通道处于活动状态，`false` 否则。
 */
internal fun isProxyChannelActiveForTesting(): Boolean = isProxyChannelActive()

    /**
     * 在测试中注入或清除服务使用的 `serverChannel`，以控制代理的激活状态。
     *
     * @param channel 要设置的 Netty `Channel` 实例；传入 `null` 用于清除或模拟未激活状态。
     */
    internal fun setServerChannelForTesting(channel: Channel?) {
        serverChannel = channel
    }

    /**
 * 检查代理服务器的 Netty 通道是否当前处于活动状态。
 *
 * @return `true` 如果已存在通道且该通道处于活动状态，`false` 否则。
 */
private fun isProxyChannelActive(): Boolean = serverChannel?.isActive == true

    /**
     * 在语言更改时刷新并推送前台服务的通知，使通知内容使用当前语言的文案。
     *
     * 在 Android O 及以上会（必要时）重建通知通道并替换已展示的前台通知。
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

    /**
 * 表明此服务不支持客户端绑定，仅作为独立的前台服务运行。
 *
 * @return 始终返回 `null`，表示不提供绑定接口。
 */
override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 释放并清理服务在运行期间持有的资源与监听器。
     *
     * 注销语言变更监听器，取消用于异步任务的协程作用域，优雅关闭 Netty 的 boss/worker 事件循环组，关闭已绑定的服务器通道，并调用父类的 onDestroy 完成销毁流程。
     */
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
