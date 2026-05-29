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

        return false
    }

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

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.close()
        relayChannel.close()
    }
}

