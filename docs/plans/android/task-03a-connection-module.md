# Task 3A: Connection Module - 网络连接管理

> **任务级别**: 核心任务  
> **前置依赖**: Task 2 (Core Application)  
> **后续任务**: Task 3E (UI 依赖此模块)  
> **预计工作量**: 3 小时

## 任务目标

实现双网卡检测（WiFi/蜂窝）和 MQTT 控制通道连接管理。

## 交付物

1. `android/app/src/main/java/com/netproxy/gateway/connection/NetworkStateManager.kt`
2. `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt`
3. 单元测试文件

## 详细步骤

### Step 1: 创建网络状态模型

在 `connection/` 目录下创建:

```kotlin
// NetworkType.kt
sealed class NetworkType {
    object Wifi : NetworkType()
    object Cellular : NetworkType()
    object Ethernet : NetworkType()
    object None : NetworkType()
}

// NetworkState.kt
data class NetworkState(
    val isConnected: Boolean = false,
    val networkType: NetworkType = NetworkType.None,
    val isValidated: Boolean = false,
    val network: android.net.Network? = null
)
```

### Step 2: 实现 NetworkStateManager

创建 `NetworkStateManager.kt`:

```kotlin
package com.netproxy.gateway.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkStateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val networkState: Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getCurrentNetworkState(network))
            }

            override fun onLost(network: Network) {
                trySend(NetworkState(isConnected = false, networkType = NetworkType.None))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(getCurrentNetworkState(network))
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)
        trySend(getCurrentNetworkState(null))

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    private fun getCurrentNetworkState(network: Network?): NetworkState {
        val activeNetwork = network ?: connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        if (capabilities == null) {
            return NetworkState(isConnected = false, networkType = NetworkType.None)
        }

        val networkType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.Wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.Cellular
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.Ethernet
            else -> NetworkType.None
        }

        return NetworkState(
            isConnected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            networkType = networkType,
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            network = activeNetwork
        )
    }

    fun isWifiConnected(): Boolean {
        return getCurrentNetworkState(null).networkType == NetworkType.Wifi
    }

    fun isCellularConnected(): Boolean {
        return getCurrentNetworkState(null).networkType == NetworkType.Cellular
    }
}
```

### Step 3: 实现 MQTT 连接管理器

创建 `MqttConnectionManager.kt`:

```kotlin
package com.netproxy.gateway.connection

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.*
import javax.inject.Inject
import javax.inject.Singleton

sealed class MqttConnectionState {
    object Disconnected : MqttConnectionState()
    object Connecting : MqttConnectionState()
    object Connected : MqttConnectionState()
    data class Error(val message: String) : MqttConnectionState()
}

@Singleton
class MqttConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MqttConnectionManager"
        private const val BROKER_URL = "tcp://your-server:1883"
        private const val CLIENT_ID = "NetProxyGateway"
        private const val HEARTBEAT_INTERVAL = 30000L
        private const val MAX_RECONNECT_DELAY = 60000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mqttClient: MqttClient? = null
    private var reconnectDelay = 5000L

    private val _connectionState = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
    val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    fun connect(deviceId: String, authToken: String) {
        scope.launch {
            try {
                _connectionState.value = MqttConnectionState.Connecting

                val clientId = "${CLIENT_ID}_$deviceId"
                mqttClient = MqttClient(BROKER_URL, clientId, MqttDefaultFilePersistence())

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 30
                    keepAliveInterval = 30
                    userName = deviceId
                    password = authToken.toCharArray()
                }

                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "Connection lost: ${cause?.message}")
                        _connectionState.value = MqttConnectionState.Error(cause?.message ?: "Connection lost")
                        scheduleReconnect(deviceId, authToken)
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        message?.let {
                            _messages.value = String(it.payload)
                            Log.d(TAG, "Message received: $topic - ${String(it.payload)}")
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                mqttClient?.connect(options)
                _connectionState.value = MqttConnectionState.Connected
                reconnectDelay = 5000L

                subscribe("device/$deviceId/control")
                startHeartbeat(deviceId)

            } catch (e: MqttException) {
                Log.e(TAG, "MQTT connection error: ${e.message}")
                _connectionState.value = MqttConnectionState.Error(e.message ?: "Connection failed")
                scheduleReconnect(deviceId, authToken)
            }
        }
    }

    private fun scheduleReconnect(deviceId: String, authToken: String) {
        scope.launch {
            delay(reconnectDelay)
            reconnectDelay = minOf(reconnectDelay * 2, MAX_RECONNECT_DELAY)
            connect(deviceId, authToken)
        }
    }

    private fun startHeartbeat(deviceId: String) {
        scope.launch {
            while (_connectionState.value == MqttConnectionState.Connected) {
                delay(HEARTBEAT_INTERVAL)
                publish("device/$deviceId/heartbeat", "{\"status\":\"alive\"}")
            }
        }
    }

    fun publish(topic: String, payload: String, qos: Int = 0) {
        try {
            val message = MqttMessage(payload.toByteArray()).apply { this.qos = qos }
            mqttClient?.publish(topic, message)
        } catch (e: MqttException) {
            Log.e(TAG, "Publish error: ${e.message}")
        }
    }

    fun subscribe(topic: String, qos: Int = 0) {
        try {
            mqttClient?.subscribe(topic, qos)
        } catch (e: MqttException) {
            Log.e(TAG, "Subscribe error: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
            _connectionState.value = MqttConnectionState.Disconnected
        } catch (e: MqttException) {
            Log.e(TAG, "Disconnect error: ${e.message}")
        }
    }
}
```

### Step 4: 更新 DI Module

在 `di/AppModule.kt` 中添加:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    
    @Provides
    @Singleton
    fun provideNetworkStateManager(
        @ApplicationContext context: Context
    ): NetworkStateManager {
        return NetworkStateManager(context)
    }
    
    @Provides
    @Singleton
    fun provideMqttConnectionManager(
        @ApplicationContext context: Context
    ): MqttConnectionManager {
        return MqttConnectionManager(context)
    }
}
```

### Step 5: 创建单元测试 (可选)

创建 `android/app/src/test/java/com/netproxy/gateway/connection/NetworkStateManagerTest.kt`:

```kotlin
package com.netproxy.gateway.connection

import org.junit.Assert.*
import org.junit.Test

class NetworkStateManagerTest {
    @Test
    fun testNetworkTypeEnum() {
        assertTrue(NetworkType.Wifi is NetworkType)
        assertTrue(NetworkType.Cellular is NetworkType)
    }
    
    @Test
    fun testNetworkStateDefault() {
        val state = NetworkState()
        assertFalse(state.isConnected)
        assertEquals(NetworkType.None, state.networkType)
    }
}
```

## 验证标准

- [ ] `./gradlew assembleDebug` 编译成功
- [ ] NetworkStateManager 可以正确检测 WiFi/蜂窝网络状态
- [ ] MqttConnectionManager 可以建立/断开连接
- [ ] 单元测试通过 (如有)

## 注意事项

1. MQTT Broker URL 需要在后续配置文件中改为真实服务器地址
2. 网络状态变化应该通过 StateFlow 正确通知给 UI
3. 考虑添加网络切换时的日志记录

## 下一步

完成后请:
1. 运行 `./gradlew assembleDebug` 验证编译
2. 提交代码到 git
3. 通知 Task 3E (UI) 开发者可以开始集成
