package com.netproxy.gateway.proxy

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
import io.netty.handler.codec.socksx.v5.Socks5AddressType
import io.netty.util.ReferenceCountUtil
import org.slf4j.LoggerFactory

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
        return try {
            val inetAddr = java.net.InetAddress.getByName(host)
            val ip = inetAddr.hostAddress ?: return false
            
            // Reject loopback, link-local metadata, broadcast, and reserved ranges
            if (ip.startsWith("127.") || ip.startsWith("169.254.") ||
                ip == "0.0.0.0" || ip == "255.255.255.255" ||
                ip.startsWith("224.")) {
                return false
            }
            
            // Only allow RFC1918 private addresses
            isPrivateRfc1918(ip)
        } catch (e: Exception) {
            false
        }
    }

    private fun isPrivateRfc1918(ip: String): Boolean {
        return try {
            val octets = ip.split(".").map { it.toInt() }
            when {
                octets.size != 4 -> false
                octets[0] == 10 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                else -> false
            }
        } catch (e: Exception) {
            false
        }
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
                    ReferenceCountUtil.release(msg)
                    ctx.close()
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
