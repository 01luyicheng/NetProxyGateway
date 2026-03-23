# Task 3C: Proxy Module - SOCKS5 代理服务

> **任务级别**: 核心任务  
> **前置依赖**: Task 2 (Core Application)  
> **后续任务**: Task 3E (UI 依赖此模块)  
> **预计工作量**: 4 小时

## 任务目标

使用 Netty 实现 SOCKS5 代理服务器，支持 TCP 流量转发。

## 交付物

1. `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyService.kt`
2. `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt`
3. `android/app/src/main/java/com/netproxy/gateway/proxy/ProxyChannelInitializer.kt`

## 详细步骤

### Step 1: 创建 SOCKS5 常量

创建 `android/app/src/main/java/com/netproxy/gateway/proxy/ProxyConfig.kt`:

```kotlin
package com.netproxy.gateway.proxy

object ProxyConfig {
    const val PROXY_HOST = "127.0.0.1"
    const val PROXY_PORT = 1080
    const val CHANNEL_ID = "proxy_service_channel"
}
```

### Step 2: 实现 SOCKS5 Proxy Service

创建 `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyService.kt`:

```kotlin
package com.netproxy.gateway.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
import java.net.InetSocketAddress
import javax.inject.Inject

@AndroidEntryPoint
class Socks5ProxyService : Service() {

    companion object {
        private const val TAG = "Socks5ProxyService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null
    private var serverChannel: Channel? = null

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
                workerGroup = NioEventLoopGroup()

                val bootstrap = ServerBootstrap()
                    .group(bossGroup!!, workerGroup!!)
                    .channel(NioServerSocketChannel::class.java)
                    .childHandler(object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            ch.pipeline().addLast(
                                LoggingHandler(LogLevel.DEBUG),
                                SocksPortUnificationServerHandler(),
                                Socks5ProxyHandler()
                            )
                        }
                    })

                val socketAddress = InetSocketAddress(
                    ProxyConfig.PROXY_HOST,
                    ProxyConfig.PROXY_PORT
                )
                serverChannel = bootstrap.bind(socketAddress).sync().channel()
                serverChannel?.closeFuture?.await()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ProxyConfig.CHANNEL_ID,
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

        return NotificationCompat.Builder(this, ProxyConfig.CHANNEL_ID)
            .setContentTitle("NetProxyGateway")
            .setContentText("SOCKS5 Proxy Running on port ${ProxyConfig.PROXY_PORT}")
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
```

### Step 3: 实现 SOCKS5 Handler

创建 `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt`:

```kotlin
package com.netproxy.gateway.proxy

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.socksx.SocksAuthScheme
import io.netty.handler.codec.socksx.SocksCmdStatus
import io.netty.handler.codec.socksx.SocksCmdType
import io.netty.handler.codec.socksx.v5.*
import org.slf4j.LoggerFactory

class Socks5ProxyHandler : ChannelInboundHandlerAdapter() {

    private val logger = LoggerFactory.getLogger(Socks5ProxyHandler::class.java)

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is SocksInitialRequest -> {
                handleInitialRequest(ctx, msg)
            }
            is SocksAuthRequest -> {
                handleAuthRequest(ctx, msg)
            }
            is SocksCmdRequest -> {
                handleCmdRequest(ctx, msg)
            }
        }
    }

    private fun handleInitialRequest(ctx: ChannelHandlerContext, msg: SocksInitialRequest) {
        // Respond with no authentication required (0x00)
        // For production, implement username/password auth if needed
        val response = DefaultSocksInitialResponse(SocksAuthScheme.NO_AUTH)
        ctx.writeAndFlush(response)
    }

    private fun handleAuthRequest(ctx: ChannelHandlerContext, msg: SocksAuthRequest) {
        // If username/password auth is used
        val response = DefaultSocksAuthResponse(SocksAuthStatus.SUCCESS)
        ctx.writeAndFlush(response)
    }

    private fun handleCmdRequest(ctx: ChannelHandlerContext, msg: SocksCmdRequest) {
        when (msg.type()) {
            SocksCmdType.CONNECT -> {
                handleConnect(ctx, msg)
            }
            SocksCmdType.BIND -> {
                // Not supported
                val response = DefaultSocksCmdResponse(
                    SocksCmdStatus.COMMAND_UNSUPPORTED,
                    msg.dstAddr(),
                    msg.dstPort()
                )
                ctx.writeAndFlush(response)
            }
            SocksCmdType.UDP_ASSOCIATE -> {
                // Not supported
                val response = DefaultSocksCmdResponse(
                    SocksCmdStatus.COMMAND_UNSUPPORTED,
                    msg.dstAddr(),
                    msg.dstPort()
                )
                ctx.writeAndFlush(response)
            }
        }
    }

    private fun handleConnect(ctx: ChannelHandlerContext, msg: SocksCmdRequest) {
        logger.info("SOCKS5 CONNECT request to ${msg.dstAddr()}:${msg.dstPort()}")
        
        // TODO: Implement actual connection forwarding
        // 1. Create outbound connection to target (msg.dstAddr(), msg.dstPort())
        // 2. Bridge between client channel and outbound channel
        //
        // For now, just acknowledge success
        val response = DefaultSocksCmdResponse(
            SocksCmdStatus.SUCCESS,
            msg.dstAddr(),
            msg.dstPort()
        )
        ctx.writeAndFlush(response)
        
        // Remove handlers and forward data
        ctx.pipeline().remove(this)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error("SOCKS5 handler exception: ${cause.message}")
        ctx.close()
    }
}
```

### Step 4: 更新 DI Module

在 `di/AppModule.kt` 中添加 Proxy 配置:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object ProxyModule {
    
    @Provides
    @Singleton
    fun provideProxyHost(): String = ProxyConfig.PROXY_HOST
    
    @Provides
    @Singleton
    fun provideProxyPort(): Int = ProxyConfig.PROXY_PORT
}
```

## 验证标准

- [ ] `./gradlew assembleDebug` 编译成功
- [ ] SOCKS5 服务可以启动并监听 1080 端口
- [ ] 可以处理 CONNECT 请求
- [ ] 正确创建前台服务通知

## 注意事项

1. SOCKS5 Handler 中的连接转发逻辑需要根据实际需求实现
2. 需要处理连接失败、超时等异常情况
3. 考虑添加连接统计和日志记录

## 下一步

完成后请:
1. 运行 `./gradlew assembleDebug` 验证编译
2. 提交代码到 git
3. 通知 Task 3E (UI) 开发者可以开始集成
