package com.netproxy.gateway.di

import com.netproxy.gateway.vpn.VirtualIpAllocator
import com.netproxy.gateway.vpn.VirtualIpAllocatorImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindCoreModule(impl: CoreModuleImpl): CoreModule

    @Binds
    @Singleton
    abstract fun bindCommunicationModule(impl: CommunicationModuleImpl): CommunicationModule

    @Binds
    @Singleton
    abstract fun bindNetworkModule(impl: NetworkModuleImpl): NetworkModule

    @Binds
    @Singleton
    abstract fun bindWiFiModule(impl: WiFiModuleImpl): WiFiModule

    @Binds
    @Singleton
    abstract fun bindUIModule(impl: UIModuleImpl): UIModule

    /**
     * 将 `ConfigModuleImpl` 绑定为 `ConfigModule` 的单例实现供依赖注入使用。
     *
     * @param impl 要绑定的 `ConfigModule` 实现实例（`ConfigModuleImpl`）。
     * @return 被绑定到依赖图中的 `ConfigModule` 单例实例。
     */
    @Binds
    @Singleton
    abstract fun bindConfigModule(impl: ConfigModuleImpl): ConfigModule

    /**
     * 将 VirtualIpAllocatorImpl 作为 VirtualIpAllocator 的单例绑定到依赖注入图。
     *
     * @param impl 用于注入的 `VirtualIpAllocatorImpl` 实例，将被作为 `VirtualIpAllocator` 提供。
     * @return 被绑定并提供为 `VirtualIpAllocator` 的实现类型。
     */
    @Binds
    @Singleton
    abstract fun bindVirtualIpAllocator(impl: VirtualIpAllocatorImpl): VirtualIpAllocator
}
