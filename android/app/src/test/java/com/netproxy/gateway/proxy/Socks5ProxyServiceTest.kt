package com.netproxy.gateway.proxy

import com.netproxy.gateway.connection.AuthSessionStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    // C82 批 3: Socks5ProxyService.kt:111 - credentialValidator lambda finally 中 passwordArray.securelyClear()
    //
    // credentialValidator lambda 嵌在 Netty ChannelInitializer 匿名内部类中，源码层面无法直接访问。
    // Kotlin 编译器将 lambda 体编译为匿名类 Socks5ProxyService$startProxyServer$1$bootstrap$1 上的
    // 静态合成方法 initChannel$lambda$0(Socks5ProxyService, String, String): boolean。
    // 通过反射加载该内部类并调用合成方法，配合注入 mock AuthSessionStore 捕获 passwordArray 引用，
    // 验证 finally 块将其填零。
    @Test
    fun credentialValidator_finally_zerosPasswordArray() {
        val controller = Robolectric.buildService(Socks5ProxyService::class.java)
        val service = controller.create().get()

        // 注入 mock AuthSessionStore（Robolectric 默认不运行 Hilt）
        val mockAuthSessionStore = mockk<AuthSessionStore>(relaxed = true)
        val capturedPassword = slot<CharArray>()
        every { mockAuthSessionStore.isValid(any(), capture(capturedPassword)) } returns true
        service.authSessionStore = mockAuthSessionStore

        // 定位编译生成的 lambda 合成方法（位于匿名 ChannelInitializer 内部类上）
        val bootstrapClassName =
            "com.netproxy.gateway.proxy.Socks5ProxyService\$startProxyServer\$1\$bootstrap\$1"
        val bootstrapClass = Class.forName(bootstrapClassName)
        val lambdaMethod = bootstrapClass.getDeclaredMethod(
            "initChannel\$lambda\$0",
            Socks5ProxyService::class.java,
            String::class.java,
            String::class.java
        )
        lambdaMethod.isAccessible = true

        val result = lambdaMethod.invoke(null, service, "user", "test-secret-password") as Boolean

        assertTrue("credentialValidator lambda should return true", result)
        verify(exactly = 1) { mockAuthSessionStore.isValid("user", any()) }
        assertNotNull("passwordArray should have been captured", capturedPassword.captured)
        assertTrue(
            "passwordArray should be zeroed after lambda's finally block (Socks5ProxyService.kt:111)",
            capturedPassword.captured.all { it == '\u0000' }
        )
    }
}
