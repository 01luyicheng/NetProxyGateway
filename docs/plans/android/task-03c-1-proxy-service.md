# Task 3C-1: SOCKS5 代理服务

> **任务级别**: 核心任务  
> **前置依赖**: Task 2 (Core Application)  
> **后续任务**: Task 3C-2, Task 3C-3  
> **预计工作量**: 1.5 小时

## 任务目标

创建 SOCKS5 代理服务的基础框架，使用 Netty 实现。

## 交付物

1. `android/app/src/main/java/com/netproxy/gateway/proxy/ProxyConfig.kt` - 代理配置
2. `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyService.kt` - SOCKS5 服务

## 详细步骤

### Step 1: 创建 ProxyConfig.kt

```kotlin
package com.netproxy.gateway.proxy

object ProxyConfig {
    const val PROXY_HOST = "127.0.0.1"
    const val PROXY_PORT = 1080
    const val BUFFER_SIZE = 8192
    const val MAX_FRAME_SIZE = 1024 * 1024
}
```

### Step 2: 创建 Socks5ProxyService.kt

```kotlin
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
        const val PROXY_PORT = 1080
        const val CHANNEL_ID = "proxy_service_channel"
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
                    .group(bossGroup, workerGroup)
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

                val socketAddress = InetSocketAddress(PROXY_PORT)
                serverChannel = bootstrap.bind(socketAddress).sync().channel()
                serverChannel?.closeFuture?.await()

            } catch (e: Exception) {
                e.printStackTrace()
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
```

## 验证标准

- [ ] ProxyConfig.kt 正确配置
- [ ] Socks5ProxyService.kt 可以启动 Netty 服务器
- [ ] 前台通知正确显示
- [ ] 代码编译通过

## 下一步

完成后请:
1. 运行 `./gradlew assembleDebug` 验证编译
2. 提交代码到 git
3. 通知 Task 3C-2 开发者开始工作
