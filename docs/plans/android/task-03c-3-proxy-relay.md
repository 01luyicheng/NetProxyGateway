# Task 3C-3: 流量转发逻辑

> **任务级别**: 高级任务  
> **前置依赖**: Task 3C-2 (代理处理器)  
> **后续任务**: Task 3E (UI)  
> **预计工作量**: 2 小时

## 任务目标

实现 SOCKS5 代理的流量转发逻辑，在客户端和目标服务器之间转发数据。

## 交付物

1. `android/app/src/main/java/com/netproxy/gateway/proxy/ProxyChannelInitializer.kt` - 通道初始化器
2. `android/app/src/main/java/com/netproxy/gateway/proxy/ProxyRelayHandler.kt` - 流量中转处理器

## 详细步骤

### Step 1: 创建 ProxyChannelInitializer.kt

```kotlin
package com.netproxy.gateway.proxy

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.bytes.ByteArrayDecoder
import io.netty.handler.codec.bytes.ByteArrayEncoder

class ProxyChannelInitializer(
    private val targetHost: String,
    private val targetPort: Int
) : ChannelInitializer<SocketChannel>() {

    override fun initChannel(ch: SocketChannel) {
        ch.pipeline().addLast(
            ByteArrayDecoder(),
            ByteArrayEncoder(),
            ProxyRelayHandler(targetHost, targetPort)
        )
    }
}
```

### Step 2: 创建 ProxyRelayHandler.kt

```kotlin
package com.netproxy.gateway.proxy

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.nio.NioSocketChannel
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

class ProxyRelayHandler(
    private val targetHost: String,
    private val targetPort: Int
) : ChannelInboundHandlerAdapter() {

    private val logger = LoggerFactory.getLogger(ProxyRelayHandler::class.java)
    private var remoteChannel: Channel? = null

    override fun channelActive(ctx: ChannelHandlerContext) {
        // 连接到目标服务器
        val bootstrap = io.netty.bootstrap.Bootstrap()
        bootstrap.group(ctx.channel().eventLoop())
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                override fun initChannel(ch: io.netty.channel.socket.SocketChannel) {
                    ch.pipeline().addLast(RelayHandler(ctx.channel()))
                }
            })

        try {
            remoteChannel = bootstrap.connect(targetHost, targetPort).sync().channel()
            logger.info("Connected to target: $targetHost:$targetPort")
        } catch (e: Exception) {
            logger.error("Failed to connect to target: ${e.message}")
            ctx.close()
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        // 将客户端数据转发到目标服务器
        remoteChannel?.writeAndFlush(msg)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        // 客户端断开，关闭到目标的连接
        remoteChannel?.close()
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error("Proxy relay error: ${cause.message}")
        ctx.close()
        remoteChannel?.close()
    }
}

/**
 * 从目标服务器转发数据到客户端
 */
class RelayHandler(
    private val clientChannel: Channel
) : ChannelInboundHandlerAdapter() {

    private val logger = LoggerFactory.getLogger(RelayHandler::class.java)

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        // 将目标服务器数据转发到客户端
        clientChannel.writeAndFlush(msg)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        // 目标断开，关闭客户端连接
        clientChannel.close()
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error("Relay error: ${cause.message}")
        ctx.close()
        clientChannel.close()
    }
}
```

### Step 3: 更新 Socks5ProxyHandler 连接逻辑

在 Task 3C-2 的基础上，更新 CONNECT 处理以启动流量转发：

```kotlin
SocksCmdType.CONNECT -> {
    logger.info("SOCKS5 CONNECT request to ${msg.dstAddr()}:${msg.dstPort()}")
    
    // 响应成功
    val response = DefaultSocksCmdResponse(
        SocksCmdStatus.SUCCESS,
        msg.dstAddr(),
        msg.dstPort()
    )
    ctx.writeAndFlush(response)
    
    // 初始化流量转发
    // 注意：完整实现需要在 CONNECT 成功后建立到目标的连接
}
```

## 验证标准

- [ ] ProxyChannelInitializer.kt 正确配置
- [ ] ProxyRelayHandler.kt 可以转发流量
- [ ] 双向流量转发正常
- [ ] 连接管理正确
- [ ] 代码编译通过

## 注意事项

1. 流量转发涉及复杂的状态管理
2. 需要处理连接失败、超时等情况
3. 需要考虑性能优化（缓冲区大小、零拷贝等）

## 下一步

完成后请:
1. 运行 `./gradlew assembleDebug` 验证编译
2. 提交代码到 git
3. 通知 Task 3E 开发者开始工作
