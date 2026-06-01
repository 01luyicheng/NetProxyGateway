package com.netproxy.gateway.proxy

import org.slf4j.LoggerFactory

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.socksx.v5.*
import io.netty.util.ReferenceCountUtil

import com.netproxy.gateway.result.getOrDefault
import com.netproxy.gateway.utils.IpAddressUtils

class Socks5ProxyHandler(
    private val connector: OutboundConnector = NettyOutboundConnector(),
    private val credentialValidator: (String, String) -> Boolean = { _, _ -> false }
) : ChannelInboundHandlerAdapter() {

    private val logger = LoggerFactory.getLogger(Socks5ProxyHandler::class.java)
    private var authenticated = false
    private var authNegotiated = false

    /**
     * 处理入站 SOCKS5 消息，执行方法协商、密码认证和命令请求的相应流程并在必要时发送响应或关闭连接。
     *
     * 该方法根据 msg 的实际类型执行不同操作：
     * - Socks5InitialRequest：选择是否使用 PASSWORD 认证，发送初始响应；若不支持或写入失败则关闭连接。
     * - Socks5PasswordAuthRequest：在已协商且未认证时校验凭据，发送认证成功或失败响应；验证失败则关闭连接。
     * - Socks5CommandRequest：仅在已认证时处理命令请求（如 CONNECT）；未认证时返回 FORBIDDEN 并关闭连接。
     * - 其它类型：释放消息并关闭连接。
     *
     * 所有分支均负责释放接收到的 msg，必要时会写出相应的 SOCKS5 响应并可能关闭 ctx。
     *
     * @param ctx Netty 的 ChannelHandlerContext，用于写入响应、关闭通道及修改流水线。
     * @param msg 接收到的 SOCKS5 消息，期望是 Socks5InitialRequest、Socks5PasswordAuthRequest 或 Socks5CommandRequest（其他类型会导致连接关闭）。
     */
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is Socks5InitialRequest -> {
                if (authenticated || authNegotiated) {
                    ctx.close()
                    ReferenceCountUtil.release(msg)
                    return
                }

                val supportsPasswordAuth = msg.authMethods().contains(Socks5AuthMethod.PASSWORD)
                val selectedAuth = if (supportsPasswordAuth) {
                    authNegotiated = true
                    Socks5AuthMethod.PASSWORD
                } else {
                    Socks5AuthMethod.UNACCEPTED
                }
                val response = DefaultSocks5InitialResponse(selectedAuth)
                ctx.writeAndFlush(response).addListener { future ->
                    if (!supportsPasswordAuth || !future.isSuccess) {
                        ctx.close()
                    }
                }
                ReferenceCountUtil.release(msg)
            }
            is Socks5PasswordAuthRequest -> {
                if (!authNegotiated || authenticated) {
                    val response = DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE)
                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
                    ReferenceCountUtil.release(msg)
                    return
                }

                val username = msg.username()
                val password = msg.password()

                val isValid = validateCredentials(username, password)
                val response = if (isValid) {
                    authenticated = true
                    DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS)
                } else {
                    authenticated = false
                    DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE)
                }

                ctx.writeAndFlush(response)
                if (!isValid) {
                    ctx.close()
                }
                ReferenceCountUtil.release(msg)
            }
            is Socks5CommandRequest -> {
                if (!authenticated) {
                    val response = DefaultSocks5CommandResponse(
                        Socks5CommandStatus.FORBIDDEN,
                        msg.dstAddrType(),
                        msg.dstAddr(),
                        msg.dstPort()
                    )
                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
                    ReferenceCountUtil.release(msg)
                    return
                }
                handleCmdRequest(ctx, msg)
                ReferenceCountUtil.release(msg)
            }
            else -> {
                ReferenceCountUtil.release(msg)
                ctx.close()
            }
        }
    }

    private fun validateCredentials(username: String, password: String): Boolean {
        if (username.isBlank() || password.isBlank()) {
            return false
        }
        return credentialValidator(username, password)
    }

    /**
     * 验证目标地址是否被允许作为 CONNECT 的目标。
     *
     * 仅允许端口在 1 到 65535 之间且目标为受限私有网络地址：IPv4 需为 RFC1918 私网（并排除回环、链路本地、广播和部分保留段），IPv6 需为本地私有 IPv6（`fc00::/7`）；域名和其它字面量均被拒绝。
     *
     * @param host 目标主机文本，期望为 IP 字面量（IPv6 可含 ':'，IPv4 采用点分十进制）；域名将被拒绝。
     * @param port 目标端口号。
     * @return `true` 当且仅当目标地址和端口满足上述允许条件，`false` 否则。
     */
    private fun validateTargetAddress(host: String, port: Int): Boolean {
        if (port !in 1..65535) {
            return false
        }

        if (host.contains(":")) {
            return isPrivateIpv6Address(host)
        }

        val octetsResult = IpAddressUtils.validateIpv4WithResult(host)
        if (octetsResult.getOrDefault(false)) {
            // Reject loopback, link-local metadata, broadcast, and reserved ranges
            if (host.startsWith("127.") || host.startsWith("169.254.") ||
                host == "0.0.0.0" || host == "255.255.255.255" ||
                host.startsWith("224.")) {
                return false
            }
            return IpAddressUtils.isPrivateIpv4Rfc1918(host)
        }

        // Reject domain names (SOCKS5 ATYP=0x03). The proxy is restricted to
        // RFC1918 private IP addresses only; clients must resolve names externally.
        return false
    }

    /**
     * 判断给定的 IPv6 文本地址是否属于允许的私有地址范围（以 `fc` 或 `fd` 开头）。
     *
     * @param ip IPv6 地址的文本表示（大小写无关）。
     * @return `true` 如果地址以 `fc` 或 `fd` 开头且不为回环地址 `::1`、不以 `fe8`/`fe9`/`fea`/`feb` 开头且不以 `ff` 开头；`false` 否则。
     */
    private fun isPrivateIpv6Address(ip: String): Boolean {
        val normalized = ip.lowercase()
        if (normalized == "::1") return false
        if (normalized.startsWith("fe8") || normalized.startsWith("fe9") ||
            normalized.startsWith("fea") || normalized.startsWith("feb")) {
            return false
        }
        if (normalized.startsWith("ff")) return false
        return normalized.startsWith("fc") || normalized.startsWith("fd")
    }

    /**
     * 处理单个 SOCKS5 命令请求（CONNECT、BIND、UDP_ASSOCIATE）。
     *
     * 对 CONNECT 请求：验证目标地址并尝试建立到上游的 TCP 连接；连接成功时向客户端返回成功响应并在管道中安装用于数据转发的中继处理器，连接失败或目标被拒绝时返回相应的错误响应并关闭会话。
     *
     * 对 BIND 与 UDP_ASSOCIATE 请求：返回 "COMMAND_UNSUPPORTED" 响应。
     *
     * @param ctx 当前通道的处理上下文，用于写入响应、修改管道及管理连接生命周期。
     * @param msg 收到的 SOCKS5 命令请求，包含命令类型、目标地址与端口等信息。
     */
    private fun handleCmdRequest(ctx: ChannelHandlerContext, msg: Socks5CommandRequest) {
        when (msg.type()) {
            Socks5CommandType.CONNECT -> {
                logger.info("SOCKS5 CONNECT attempt")

                val dstAddr = msg.dstAddr() ?: ""
                val dstPort = msg.dstPort()

                if (dstAddr.isBlank()) {
                    val response = DefaultSocks5CommandResponse(
                        Socks5CommandStatus.HOST_UNREACHABLE,
                        msg.dstAddrType(),
                        "0.0.0.0",
                        dstPort
                    )
                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
                    return
                }

                if (!validateTargetAddress(dstAddr, dstPort)) {
                    logger.warn("Target address rejected: {} (non-private or reserved)", dstAddr)
                    val response = DefaultSocks5CommandResponse(
                        Socks5CommandStatus.FORBIDDEN,
                        Socks5AddressType.DOMAIN,
                        dstAddr,
                        dstPort
                    )
                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
                    return
                }

                connector.connect(ctx, dstAddr, dstPort) { upstream, error ->
                    if (error != null || upstream == null) {
                        logger.warn("Failed to connect upstream - {}", error?.message)

                        val response = DefaultSocks5CommandResponse(
                            Socks5CommandStatus.HOST_UNREACHABLE,
                            Socks5AddressType.DOMAIN,
                            dstAddr,
                            dstPort
                        )
                        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
                        return@connect
                    }

                    val response = DefaultSocks5CommandResponse(
                        Socks5CommandStatus.SUCCESS,
                        Socks5AddressType.DOMAIN,
                        dstAddr,
                        dstPort
                    )

                    ctx.writeAndFlush(response).addListener {
                        if (!ctx.channel().isActive) {
                            upstream.close()
                            return@addListener
                        }

                        if (ctx.pipeline().context(this@Socks5ProxyHandler) != null) {
                            ctx.pipeline().remove(this@Socks5ProxyHandler)
                        }

                        ctx.pipeline().addLast(RelayHandler(upstream))
                    }
                }
            }
            Socks5CommandType.BIND -> {
                val response = DefaultSocks5CommandResponse(
                    Socks5CommandStatus.COMMAND_UNSUPPORTED,
                    msg.dstAddrType(),
                    msg.dstAddr() ?: "0.0.0.0",
                    msg.dstPort()
                )
                ctx.writeAndFlush(response)
            }
            Socks5CommandType.UDP_ASSOCIATE -> {
                val response = DefaultSocks5CommandResponse(
                    Socks5CommandStatus.COMMAND_UNSUPPORTED,
                    msg.dstAddrType(),
                    msg.dstAddr() ?: "0.0.0.0",
                    msg.dstPort()
                )
                ctx.writeAndFlush(response)
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error("SOCKS5 handler exception", cause)
        ctx.close()
    }
}

interface OutboundConnector {
    fun connect(
        clientCtx: ChannelHandlerContext,
        host: String,
        port: Int,
        callback: (channel: Channel?, error: Throwable?) -> Unit
    )
}

private class NettyOutboundConnector : OutboundConnector {
    override fun connect(
        clientCtx: ChannelHandlerContext,
        host: String,
        port: Int,
        callback: (channel: Channel?, error: Throwable?) -> Unit
    ) {
        val clientChannel = clientCtx.channel()
        val bootstrap = Bootstrap()
            .group(clientChannel.eventLoop())
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.AUTO_READ, true)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(RelayHandler(clientChannel))
                }
            })

        bootstrap.connect(host, port).addListener(ChannelFutureListener { future ->
            if (future.isSuccess) {
                callback(future.channel(), null)
            } else {
                callback(null, future.cause())
            }
        })
    }
}

@ChannelHandler.Sharable
private class RelayHandler(
    private val relayChannel: Channel
) : ChannelInboundHandlerAdapter() {

    /**
     * 将从当前通道读取到的消息转发到已连接的对端通道，或在对端不可用时释放并关闭本端。
     *
     * 如果 relayChannel 处于活动状态，则把 msg 写入并刷新到 relayChannel；若写入失败，则关闭当前上下文和 relayChannel（写入失败时 Netty 会自动释放 msg）。
     * 如果 relayChannel 不可用，则显式释放 msg 并关闭当前上下文。
     *
     * @param ctx 当前的 ChannelHandlerContext，用于关闭和管道操作。
     * @param msg 从当前通道接收到并需要转发或释放的消息对象。
     */
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (relayChannel.isActive) {
            relayChannel.writeAndFlush(msg).addListener(ChannelFutureListener { future ->
                if (!future.isSuccess) {
                    // Netty releases msg automatically on write failure;
                    // do NOT call ReferenceCountUtil.release(msg) here.
                    ctx.close()
                    relayChannel.close()
                }
            })
        } else {
            ReferenceCountUtil.release(msg)
            ctx.close()
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        relayChannel.close()
    }

    /**
     * 在捕获到异常时关闭当前处理器的通道上下文并关闭与之关联的中继通道。
     *
     * @param ctx 当前的 ChannelHandlerContext（客户端通道的上下文）。
     * @param cause 导致该方法被调用的异常。
     */
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.close()
        relayChannel.close()
    }
}

