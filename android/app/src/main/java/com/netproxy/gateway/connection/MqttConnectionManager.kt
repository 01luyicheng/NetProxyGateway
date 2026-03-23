package com.netproxy.gateway.connection

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import java.util.concurrent.atomic.AtomicLong

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
        
        // MQTT Broker 配置 - 使用 TLS 加密
        // 生产环境应使用实际服务器地址
        private const val BROKER_URL = "ssl://your-server:8883"
        private const val BROKER_URL_PLAIN = "tcp://your-server:1883"  // 开发环境使用
        
        private const val CLIENT_ID = "NetProxyGateway"
        private const val HEARTBEAT_INTERVAL = 30000L
        private const val MAX_RECONNECT_DELAY = 60000L
        
        // 是否启用 TLS（生产环境应为 true）
        const val USE_TLS = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mqttClient: MqttClient? = null
    private var reconnectDelay = 5000L
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    @Volatile private var shouldStayConnected: Boolean = false
    private val connectionGeneration = AtomicLong(0)

    private val _connectionState = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
    val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    fun connect(deviceId: String, authToken: String) {
        shouldStayConnected = true
        reconnectJob?.cancel()
        val generation = connectionGeneration.incrementAndGet()
        scope.launch {
            try {
                _connectionState.value = MqttConnectionState.Connecting

                // 根据配置选择 MQTT 连接 URL
                val brokerUrl = if (USE_TLS) BROKER_URL else BROKER_URL_PLAIN
                val clientId = "${CLIENT_ID}_$deviceId"
                mqttClient?.close()
                mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 30
                    keepAliveInterval = 30
                    userName = deviceId
                    password = authToken.toCharArray()
                    setAutomaticReconnect(false) // We handle reconnection manually
                    
                    // TLS 配置 (生产环境使用)
                    if (USE_TLS) {
                        // 使用默认 TrustManager 的 SSLContext
                        val sslContext = SSLContext.getInstance("TLSv1.2")
                        sslContext.init(null, null, null)
                        socketFactory = sslContext.socketFactory
                    }
                }

                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        if (generation != connectionGeneration.get()) {
                            return
                        }
                        Log.w(TAG, "Connection lost: ${cause?.message}")
                        _connectionState.value = MqttConnectionState.Error(cause?.message ?: "Connection lost")
                        if (shouldStayConnected) {
                            scheduleReconnect(deviceId, authToken, generation)
                        }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        message?.let {
                            _messages.value = String(it.payload)
                            Log.d(TAG, "Message received: $topic - ${String(it.payload)}")
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        // Message delivered
                    }
                })

                mqttClient?.connect(options)
                if (!shouldStayConnected || generation != connectionGeneration.get()) {
                    mqttClient?.disconnect()
                    mqttClient?.close()
                    mqttClient = null
                    _connectionState.value = MqttConnectionState.Disconnected
                    return@launch
                }
                _connectionState.value = MqttConnectionState.Connected
                reconnectDelay = 5000L

                subscribe("device/$deviceId/control")
                startHeartbeat(deviceId)

            } catch (e: MqttException) {
                if (generation != connectionGeneration.get()) {
                    return@launch
                }
                Log.e(TAG, "MQTT connection error: ${e.message}")
                _connectionState.value = MqttConnectionState.Error(e.message ?: "Connection failed")
                if (shouldStayConnected) {
                    scheduleReconnect(deviceId, authToken, generation)
                }
            }
        }
    }

    private fun scheduleReconnect(deviceId: String, authToken: String, generation: Long) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(reconnectDelay)
            if (!shouldStayConnected || generation != connectionGeneration.get()) {
                return@launch
            }
            reconnectDelay = minOf(reconnectDelay * 2, MAX_RECONNECT_DELAY)
            connect(deviceId, authToken)
        }
    }

    private fun startHeartbeat(deviceId: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (shouldStayConnected && _connectionState.value == MqttConnectionState.Connected) {
                delay(HEARTBEAT_INTERVAL)
                publish("device/$deviceId/heartbeat", "{\"status\":\"alive\"}")
            }
        }
    }

    fun publish(topic: String, payload: String, qos: Int = 0) {
        try {
            val message = MqttMessage(payload.toByteArray()).apply {
                this.qos = qos
            }
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
        shouldStayConnected = false
        connectionGeneration.incrementAndGet()
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
            mqttClient = null
            _connectionState.value = MqttConnectionState.Disconnected
        } catch (e: MqttException) {
            Log.e(TAG, "Disconnect error: ${e.message}")
        }
    }
}
