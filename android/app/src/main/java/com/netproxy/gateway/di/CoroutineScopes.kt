package com.netproxy.gateway.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineScopesModule {

    /**
     * 提供一个应用级别的 [CoroutineScope]，用于在应用生命周期内启动和监督协程。
     *
     * 该作用域以 `SupervisorJob` 作为父作业并在 IO 调度器上执行，适用于在子协程失败时不影响其他同级协程且以 IO 为主的后台任务。
     *
     * @return 提供的 [CoroutineScope]，可在应用范围内用于启动长期或后台协程。
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
