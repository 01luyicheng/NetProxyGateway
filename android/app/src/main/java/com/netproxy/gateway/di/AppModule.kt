package com.netproxy.gateway.di

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

    @Binds
    @Singleton
    abstract fun bindConfigModule(impl: ConfigModuleImpl): ConfigModule
}
