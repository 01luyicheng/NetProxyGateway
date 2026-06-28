package com.netproxy.gateway.proxy

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandRequest
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialRequest
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthRequest
import io.netty.handler.codec.socksx.v5.Socks5AddressType
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod
import io.netty.handler.codec.socksx.v5.Socks5CommandResponse
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus
import io.netty.handler.codec.socksx.v5.Socks5CommandType
import io.netty.handler.codec.socksx.v5.Socks5InitialResponse
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthResponse
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Socks5ProxyHandlerTest {

    companion object {
        private const val TEST_USERNAME = "test_user"
        private const val TEST_PASSWORD = "test_password_only"
    }

    private val testValidator: (String, String) -> Boolean = { username, password ->
        username == TEST_USERNAME && password == TEST_PASSWORD
    }

    @Test
    fun initialRequest_returnsNoAuth() {
        val channel = EmbeddedChannel(Socks5ProxyHandler(credentialValidator = testValidator))
        try {
            channel.writeInbound(DefaultSocks5InitialRequest(Socks5AuthMethod.PASSWORD))

            val response = channel.readOutbound<Socks5InitialResponse>()
            assertNotNull(response)
            assertEquals(Socks5AuthMethod.PASSWORD, response.authMethod())
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun authRequest_returnsSuccess() {
        val channel = EmbeddedChannel(Socks5ProxyHandler(credentialValidator = testValidator))
        try {
            negotiatePasswordAuth(channel)
            channel.writeInbound(DefaultSocks5PasswordAuthRequest(TEST_USERNAME, TEST_PASSWORD))

            val response = channel.readOutbound<Socks5PasswordAuthResponse>()
            assertNotNull(response)
            assertEquals(Socks5PasswordAuthStatus.SUCCESS, response.status())
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun bindRequest_returnsUnsupported() {
        val channel = EmbeddedChannel(Socks5ProxyHandler(credentialValidator = testValidator))
        try {
            authenticate(channel)

            channel.writeInbound(
                DefaultSocks5CommandRequest(
                    Socks5CommandType.BIND,
                    Socks5AddressType.DOMAIN,
                    "example.com",
                    443
                )
            )

            val response = channel.readOutbound<Socks5CommandResponse>()
            assertNotNull(response)
            assertEquals(Socks5CommandStatus.COMMAND_UNSUPPORTED, response.status())
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun connectRequest_callsConnectorAndReturnsSuccess() {
        val fakeConnector = FakeConnector(success = true)
        val channel = EmbeddedChannel(Socks5ProxyHandler(fakeConnector, testValidator))

        try {
            authenticate(channel)

            channel.writeInbound(
                DefaultSocks5CommandRequest(
                    Socks5CommandType.CONNECT,
                    Socks5AddressType.IPv4,
                    "192.168.1.10",
                    443
                )
            )

            val response = channel.readOutbound<Socks5CommandResponse>()
            assertNotNull(response)
            assertEquals(Socks5CommandStatus.SUCCESS, response.status())
            assertTrue(fakeConnector.called)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun connectRequest_whenUpstreamFails_returnsHostUnreachable() {
        val fakeConnector = FakeConnector(success = false)
        val channel = EmbeddedChannel(Socks5ProxyHandler(fakeConnector, testValidator))

        try {
            authenticate(channel)

            channel.writeInbound(
                DefaultSocks5CommandRequest(
                    Socks5CommandType.CONNECT,
                    Socks5AddressType.IPv4,
                    "10.0.0.5",
                    443
                )
            )

            val response = channel.readOutbound<Socks5CommandResponse>()
            assertNotNull(response)
            assertEquals(Socks5CommandStatus.HOST_UNREACHABLE, response.status())
            assertTrue(fakeConnector.called)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun connectRequest_withoutAuth_returnsForbidden() {
        val fakeConnector = FakeConnector(success = true)
        val channel = EmbeddedChannel(Socks5ProxyHandler(fakeConnector, testValidator))

        try {
            channel.writeInbound(
                DefaultSocks5CommandRequest(
                    Socks5CommandType.CONNECT,
                    Socks5AddressType.IPv4,
                    "10.0.0.5",
                    443
                )
            )

            val response = channel.readOutbound<Socks5CommandResponse>()
            assertNotNull(response)
            assertEquals(Socks5CommandStatus.FORBIDDEN, response.status())
            assertTrue(!fakeConnector.called)
        } finally {
            channel.finishAndReleaseAll()
        }
    }
    @Test
    fun connectRequest_privateBoundary17231_returnsSuccess() {
        val fakeConnector = FakeConnector(success = true)
        val channel = EmbeddedChannel(Socks5ProxyHandler(fakeConnector, testValidator))

        try {
            authenticate(channel)

            channel.writeInbound(
                DefaultSocks5CommandRequest(
                    Socks5CommandType.CONNECT,
                    Socks5AddressType.IPv4,
                    "172.31.255.255",
                    443
                )
            )

            val response = channel.readOutbound<Socks5CommandResponse>()
            assertNotNull(response)
            assertEquals(Socks5CommandStatus.SUCCESS, response.status())
            assertTrue(fakeConnector.called)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun connectRequest_outsidePrivateBoundary17232_returnsForbidden() {
        val fakeConnector = FakeConnector(success = true)
        val channel = EmbeddedChannel(Socks5ProxyHandler(fakeConnector, testValidator))

        try {
            authenticate(channel)

            channel.writeInbound(
                DefaultSocks5CommandRequest(
                    Socks5CommandType.CONNECT,
                    Socks5AddressType.IPv4,
                    "172.32.0.0",
                    443
                )
            )

            val response = channel.readOutbound<Socks5CommandResponse>()
            assertNotNull(response)
            assertEquals(Socks5CommandStatus.FORBIDDEN, response.status())
            assertTrue(!fakeConnector.called)
        } finally {
            channel.finishAndReleaseAll()
        }
    }
    @Test
    fun connectRequest_domain_callsConnectorAndReturnsSuccess() {
        val fakeConnector = FakeConnector(success = true)
        val channel = EmbeddedChannel(Socks5ProxyHandler(fakeConnector, testValidator))

        try {
            authenticate(channel)

            channel.writeInbound(
                DefaultSocks5CommandRequest(
                    Socks5CommandType.CONNECT,
                    Socks5AddressType.DOMAIN,
                    "proxy.local",
                    1080
                )
            )

            val response = channel.readOutbound<Socks5CommandResponse>()
            assertNotNull(response)
            assertEquals(Socks5CommandStatus.SUCCESS, response.status())
            assertTrue(fakeConnector.called)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun connectRequest_domainWithUpstreamFailure_returnsHostUnreachable() {
        val fakeConnector = FakeConnector(success = false)
        val channel = EmbeddedChannel(Socks5ProxyHandler(fakeConnector, testValidator))

        try {
            authenticate(channel)

            channel.writeInbound(
                DefaultSocks5CommandRequest(
                    Socks5CommandType.CONNECT,
                    Socks5AddressType.DOMAIN,
                    "proxy.local",
                    1080
                )
            )

            val response = channel.readOutbound<Socks5CommandResponse>()
            assertNotNull(response)
            assertEquals(Socks5CommandStatus.HOST_UNREACHABLE, response.status())
            assertTrue(fakeConnector.called)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun authRequest_withoutNegotiation_returnsFailure() {
        val channel = EmbeddedChannel(Socks5ProxyHandler(credentialValidator = testValidator))
        try {
            channel.writeInbound(DefaultSocks5PasswordAuthRequest(TEST_USERNAME, TEST_PASSWORD))

            val response = channel.readOutbound<Socks5PasswordAuthResponse>()
            assertNotNull(response)
            assertEquals(Socks5PasswordAuthStatus.FAILURE, response.status())
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    private fun negotiatePasswordAuth(channel: EmbeddedChannel) {
        channel.writeInbound(DefaultSocks5InitialRequest(Socks5AuthMethod.PASSWORD))
        val response = channel.readOutbound<Socks5InitialResponse>()
        assertNotNull(response)
        assertEquals(Socks5AuthMethod.PASSWORD, response.authMethod())
    }

    private fun authenticate(channel: EmbeddedChannel) {
        negotiatePasswordAuth(channel)
        channel.writeInbound(DefaultSocks5PasswordAuthRequest(TEST_USERNAME, TEST_PASSWORD))
        val response = channel.readOutbound<Socks5PasswordAuthResponse>()
        assertNotNull(response)
        assertEquals(Socks5PasswordAuthStatus.SUCCESS, response.status())
    }

    private class FakeConnector(
        private val success: Boolean
    ) : OutboundConnector {
        var called: Boolean = false

        override fun connect(
            clientCtx: ChannelHandlerContext,
            host: String,
            port: Int,
            callback: (channel: Channel?, error: Throwable?) -> Unit
        ) {
            called = true
            if (success) {
                callback(EmbeddedChannel(), null)
            } else {
                callback(null, IllegalStateException("connect failed"))
            }
        }
    }


    // C77: Socks5ProxyHandler double-free 修复缺少 write-failure 回归测试
    @Test
    fun relayHandler_onWriteFailure_closesChannelsAndDoesNotDoubleFree() {
        val upstreamChannel = io.mockk.mockk<io.netty.channel.Channel>(relaxed = true)
        val mockFuture = io.mockk.mockk<io.netty.channel.ChannelFuture>(relaxed = true)

        io.mockk.every { upstreamChannel.isActive } returns true
        io.mockk.every { upstreamChannel.writeAndFlush(any()) } returns mockFuture
        io.mockk.every { mockFuture.addListener(any()) } answers {
            val listener = it.invocation.args[0] as io.netty.util.concurrent.GenericFutureListener<io.netty.channel.ChannelFuture>

            val failedFuture = io.mockk.mockk<io.netty.channel.ChannelFuture>(relaxed = true)
            io.mockk.every { failedFuture.isSuccess } returns false

            listener.operationComplete(failedFuture)
            mockFuture
        }

        val relayHandlerClass = Class.forName("com.netproxy.gateway.proxy.RelayHandler")
        val constructor = relayHandlerClass.getDeclaredConstructor(io.netty.channel.Channel::class.java)
        constructor.isAccessible = true
        val relayHandler = constructor.newInstance(upstreamChannel) as io.netty.channel.ChannelInboundHandlerAdapter

        val clientChannel = io.netty.channel.embedded.EmbeddedChannel(relayHandler)
        val msg = io.netty.buffer.Unpooled.wrappedBuffer(byteArrayOf(1, 2, 3))

        try {
            clientChannel.writeInbound(msg)
        } catch (e: io.netty.util.IllegalReferenceCountException) {
            org.junit.Assert.fail("Double free detected: \${e.message}")
        }

        org.junit.Assert.assertFalse(clientChannel.isActive)
        io.mockk.verify { upstreamChannel.close() }
    }
}
