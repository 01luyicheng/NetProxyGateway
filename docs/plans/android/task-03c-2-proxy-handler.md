# Task 3C-2: SOCKS5 代理处理器

> **任务级别**: 核心任务  
> **前置依赖**: Task 3C-1 (代理服务)  
> **后续任务**: Task 3C-3  
> **预计工作量**: 1.5 小时

## 任务目标

实现 SOCKS5 协议处理器，处理连接请求。

## 交付物

1. `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt` - SOCKS5 处理器

## 详细步骤

### Step 1: 创建 Socks5ProxyHandler.kt

```kotlin
package com.netproxy.gateway.proxy

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.socksx.SocksVersion
import io.netty.handler.codec.socksx.v5.*
import org.slf4j.LoggerFactory

class Socks5ProxyHandler : ChannelInboundHandlerAdapter() {

    private val logger = LoggerFactory.getLogger(Socks5ProxyHandler::class.java)

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is SocksInitialRequest -> {
                // 接受无认证方式
                val response = DefaultSocksInitialResponse(SocksAuthScheme.NO_AUTH)
                ctx.writeAndFlush(response)
            }
            is SocksAuthRequest -> {
                // 如果使用用户名/密码认证
                val response = DefaultSocksAuthResponse(SocksAuthStatus.SUCCESS)
                ctx.writeAndFlush(response)
            }
            is SocksCmdRequest -> {
                handleCmdRequest(ctx, msg)
            }
        }
    }

    private fun handleCmdRequest(ctx: ChannelHandlerContext, msg: SocksCmdRequest) {
        when (msg.type()) {
            SocksCmdType.CONNECT -> {
                logger.info("SOCKS5 CONNECT request to ${msg.dstAddr()}:${msg.dstPort()}")
                
                // TODO: 建立到目标的连接
                // 完整实现需要:
                // 1. 连接到目标地址
                // 2. 在客户端和服务端之间转发数据
                
                val response = DefaultSocksCmdResponse(
                    SocksCmdStatus.SUCCESS,
                    msg.dstAddr(),
                    msg.dstPort()
                )
                ctx.writeAndFlush(response)
                
                // 移除处理器并转发数据
                ctx.pipeline().remove(this)
            }
            SocksCmdType.BIND -> {
                val response = DefaultSocksCmdResponse(
                    SocksCmdStatus.COMMAND_UNSUPPORTED,
                    msg.dstAddr(),
                    msg.dstPort()
                )
                ctx.writeAndFlush(response)
            }
            SocksCmdType.UDP_ASSOCIATE -> {
                val response = DefaultSocksCmdResponse(
                    SocksCmdStatus.COMMAND_UNSUPPORTED,
                    msg.dstAddr(),
                    msg.dstPort()
                )
                ctx.writeAndFlush(response)
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error("SOCKS5 handler exception: ${cause.message}")
        ctx.close()
    }
}
```

## 验证标准

- [ ] Socks5ProxyHandler.kt 正确实现 SOCKS5 协议
- [ ] 可以处理 CONNECT 请求
- [ ] 错误处理正确
- [ ] 代码编译通过

## 下一步

完成后请:
1. 运行 `./gradlew assembleDebug` 验证编译
2. 提交代码到 git
3. 通知 Task 3C-3 开发者开始工作
