package com.netproxy.gateway.proxy

import io.netty.channel.embedded.EmbeddedChannel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Socks5ProxyServiceTest {

    @Test
    fun languageChangeDecision_whenProxyChannelInactive_returnsFalse() {
        val controller = Robolectric.buildService(Socks5ProxyService::class.java)
        val service = controller.create().get()

        assertFalse(service.shouldRefreshNotificationOnLanguageChangeForTesting())
    }

    @Test
    fun languageChangeDecision_whenProxyChannelActive_returnsTrue() {
        val controller = Robolectric.buildService(Socks5ProxyService::class.java)
        val service = controller.create().get()
        val channel = EmbeddedChannel()

        service.setServerChannelForTesting(channel)

        assertTrue(service.shouldRefreshNotificationOnLanguageChangeForTesting())
    }

    @Test
    fun proxyRunningState_followsServerChannelActivity() {
        val controller = Robolectric.buildService(Socks5ProxyService::class.java)
        val service = controller.create().get()
        assertFalse(service.isProxyChannelActiveForTesting())

        val channel = EmbeddedChannel()
        service.setServerChannelForTesting(channel)
        assertTrue(service.isProxyChannelActiveForTesting())

        channel.close().syncUninterruptibly()
        assertFalse(service.isProxyChannelActiveForTesting())
    }
}
